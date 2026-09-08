package com.fantamomo.hc.dns.task.sc

import com.fantamomo.hc.dns.App
import com.fantamomo.hc.dns.data.Config
import com.fantamomo.hc.dns.data.SharedConstants
import com.fantamomo.hc.dns.db.PersistentDataTable
import com.fantamomo.hc.dns.db.SiteProblemTable
import com.fantamomo.hc.dns.db.SlackListColumnTable
import com.fantamomo.hc.dns.manager.DatabaseManager
import com.fantamomo.hc.dns.model.SiteProblem
import com.fantamomo.hc.dns.model.SiteProblemSeverity
import com.fantamomo.hc.dns.model.SiteProblemType
import com.fantamomo.hc.dns.model.dns.RecordType
import com.fantamomo.hc.dns.util.DestructuringComponent
import com.fantamomo.hc.dns.util.humanReadable
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toSet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant
import kotlin.time.measureTime

// The list in slack looks the following (format: `"<name>"("<id>"): <type><metadata (optional)>; <description>`):
// "Site"("name"): text, primary column; contains the site name (e.g., "example.com")
// "Severity"("severity"): options(com.fantamomo.hc.dns.model.SiteProblemSeverity); the severity of the problem
// "Problem"("problems")): options(com.fantamomo.hc.dns.model.SiteProblemType); the type of the problem
// "Record Type"("record_type"): options(com.fantamomo.hc.dns.model.dns.RecordType); the type of the record
// "Record Target"("record_target"): text; the target of the record
// "Endpoint"("endpoint"): link; the site in its link format (e.g., "https://example.com")
// "Details"("details"): text; human-readable details in English to explain the problem
// "Remote address"("remote_address"): text; the remote address of the site (e.g., "1.2.3.4:443")
// "Exception"("exception"): text; the exception that was thrown during the check in the format "TYPE: MESSAGE" (e.g., "javax.net.ssl.SSLException: (internal_error) Received fatal alert: internal_error")
// "First occurred"("first_occurred"): date; the date when the problem first occurred
// "Problem since"("problem_since"): text; the days since the problem first occurred formatted as "X days"
// "Tech facts"("tech_facts"): text; technical facts about the problem in English, can be an enumeration or list, does not need to be nice readable like "Details"
//
// slack requires only the Site field, but we require the following fields:
// severity, problems, record_type, record_target, endpoint, details
//
// the following fields are automatically provided by this connector via Database Tables:
// First occurred, Problem since
object SlackSiteCheckerConnector {
    private val logger = LoggerFactory.getLogger(SlackSiteCheckerConnector::class.java)

    private const val PERSISTENT_LIST_ID_KEY = "slack_site_checker_list_id"
    private const val MAX_DELETE_BATCH_SIZE = 200

    private class RateLimiter(maxRequestsPerMinute: Int) {
        init {
            require(maxRequestsPerMinute > 0) {
                "maxRequestsPerMinute must be greater than 0"
            }
        }

        private val intervalNanos = 60_000_000_000L / maxRequestsPerMinute
        private val lock = Any()
        private var nextRequestAt = System.nanoTime()

        suspend fun awaitRateLimit() {
            val delayNanos = synchronized(lock) {
                val now = System.nanoTime()
                val scheduledAt = maxOf(nextRequestAt, now)
                nextRequestAt = scheduledAt + intervalNanos
                scheduledAt - now
            }

            if (delayNanos > 0) {
                delay(delayNanos.nanoseconds)
            }
        }
    }

    private data class ExistingProblem(
        val itemId: String,
        val problem: SiteProblem,
        val firstOccurred: Instant
    )

    private val createUpdateLimiter = RateLimiter(50)
    private val deleteLimiter = RateLimiter(20)
    private val deleteMultiLimiter = RateLimiter(20)
    private val postMessageLimiter = RateLimiter(60)
    private val listItemsLimiter = RateLimiter(20)

    private val deleteChannel = Channel<String>(Channel.BUFFERED)

    private val running = AtomicBoolean(false)
    private val job = SupervisorJob(App.scope.coroutineContext.job)

    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        logger.error("A task in SlackSiteCheckerConnector encountered an exception", exception)
    }

    private val scope = CoroutineScope(
        App.scope.coroutineContext + job + exceptionHandler
    )

    private val createListMutex = Mutex()
    private val accessMutex = Mutex()
    private val accessUpdated = AtomicBoolean(false)

    private var cachedListId: String? = null
    private val cachedColumnIds = ConcurrentHashMap<String, String>()

    private val fullListCheckMutex = Mutex()

    private val databaseSemaphore = Semaphore(8)

    private suspend fun <T> database(block: suspend R2dbcTransaction.() -> T): T =
        databaseSemaphore.withPermit {
            DatabaseManager.transaction(block = block)
        }

    // starts a coroutine scope
    // which is used by the following two methods to schedule tasks
    suspend fun start() {
        if (!running.compareAndSet(expectedValue = false, newValue = true)) {
            return
        }
        fullListCheckMutex.lock()

        if (Config.SLACK_BOT_TOKEN.isBlank()) {
            logger.warn("Slack bot token is not set, SlackSiteCheckerConnector will not be started")
            return
        }

        logger.info("SlackSiteCheckerConnector started")

        scope.launch {
            try {
                val listId = try {
                    getOrCreateSlackListId()
                } catch (e: Exception) {
                    logger.error("Failed to initialize Slack list during start", e)
                    null
                }
                if (listId != null) {
                    try {
                        val duration = measureTime {
                            runFullListCheck(listId)
                        }
                        logger.info("Full list check completed in ${duration.humanReadable()}")
                    } catch (e: Exception) {
                        logger.error("Failed to run full list check", e)
                    }
                }
            } finally {
                fullListCheckMutex.unlock()
            }
        }

        scope.launch {
            runDeletionWorker()
        }
    }

    suspend fun waitForFullListCheck() {
        if (!fullListCheckMutex.isLocked) return
        fullListCheckMutex.lock()
        fullListCheckMutex.unlock()
    }

    private suspend fun runFullListCheck(listId: String) {
        logger.info("Starting full list check for list $listId")

        val itemIds = mutableSetOf<String>()

        var cursor: String? = null
        var requestCount = 0

        do {
            val startCursor = cursor
            try {
                listItemsLimiter.awaitRateLimit()
                requestCount++
                val response = SharedConstants.client.post("https://slack.com/api/slackLists.items.list") {
                    bearerAuth(Config.SLACK_BOT_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("list_id", listId)
                        put("limit", 1000)
                        if (cursor != null) {
                            put("cursor", cursor)
                        }
                    })
                }
                val jsonObject = response.body<JsonObject>()
                if ((jsonObject["ok"] as? JsonPrimitive)?.booleanOrNull == true) {
                    val items = jsonObject["items"] as? JsonArray
                    if (items != null) {
                        if (items.isEmpty()) break
                        for (item in items) {
                            val itemId = ((item as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
                            if (itemId != null) {
                                itemIds.add(itemId)
                            }
                        }
                    }
                    val metadata = jsonObject["response_metadata"] as? JsonObject ?: break
                    cursor = (metadata["next_cursor"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                } else {
                    logger.error("Slack api request was not successful: $jsonObject")
                    break
                }
            } catch (e: Exception) {
                logger.error("Error while fetching items from Slack list ", e)
                break
            }
        } while (cursor != null && cursor != startCursor)

        logger.info("Found ${itemIds.size} items in the slack list with $requestCount requests")

        val idsInDb = database {
            SiteProblemTable.select(SiteProblemTable.itemId)
                .map { it[SiteProblemTable.itemId] }
                .toSet()
        }

        logger.info("Found ${idsInDb.size} items in the database")

        val idsNotInDatabase = itemIds.filter { it !in idsInDb }
        val idsNotInSlack = idsInDb.filterTo(mutableSetOf()) { it !in itemIds }

        if (idsNotInDatabase.isEmpty() && idsNotInSlack.isEmpty()) {
            logger.info("No items to delete from the database or slack list")
            return
        }

        logger.info("Found ${idsNotInDatabase.size} items not in the database and ${idsNotInSlack.size} items not in slack")

        try {
            val duration = measureTime {
                flushDeletions(idsNotInDatabase)
            }
            logger.info("Deleted ${idsNotInDatabase.size} items from the slack list in ${duration.humanReadable()}")
        } catch (e: Exception) {
            logger.error("Failed to delete ${idsNotInDatabase.size} items from the slack list", e)
        }

        try {
            val duration = measureTime {
                database {
                    SiteProblemTable.deleteWhere {
                        SiteProblemTable.itemId inList idsNotInSlack
                    }
                }
            }
            logger.info("Deleted ${idsNotInSlack.size} items from the database in ${duration.humanReadable()}")
        } catch (e: Exception) {
            logger.error("Failed to delete ${idsNotInSlack.size} items from the database", e)
        }
    }

    // call this method with a problem, the method will store it in the database and send it to slack
    // if the problem is new, it will create a new list item to store it
    // if the problem is not new, it will update the existing list item
    suspend fun problem(problem: SiteProblem) {
        try {
            val now = Clock.System.now()
            val existing = findExistingProblem(problem.site)

            if (existing == null) {
                createProblem(problem, now)
            } else {
                updateProblem(problem, existing, now)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to process problem for site ${problem.site}", e)
        }
    }

    private suspend fun findExistingProblem(site: String): ExistingProblem? =
        database {
            SiteProblemTable.select(
                SiteProblemTable.site,
                SiteProblemTable.itemId,
                SiteProblemTable.problem,
                SiteProblemTable.recordType,
                SiteProblemTable.recordTarget,
                SiteProblemTable.endpoint,
                SiteProblemTable.details,
                SiteProblemTable.remoteAddress,
                SiteProblemTable.exception,
                SiteProblemTable.techFacts,
                SiteProblemTable.key,
                SiteProblemTable.firstOccurred
            )
                .where { SiteProblemTable.site eq site }
                .singleOrNull()
                ?.let {
                    ExistingProblem(
                        itemId = it[SiteProblemTable.itemId],
                        firstOccurred = it[SiteProblemTable.firstOccurred],
                        problem = SiteProblem(
                            site = it[SiteProblemTable.site],
                            problem = it[SiteProblemTable.problem],
                            recordType = it[SiteProblemTable.recordType],
                            recordTarget = it[SiteProblemTable.recordTarget],
                            url = Url(it[SiteProblemTable.endpoint]),
                            details = it[SiteProblemTable.details],
                            remoteAddress = it[SiteProblemTable.remoteAddress],
                            exception = it[SiteProblemTable.exception],
                            techFacts = it[SiteProblemTable.techFacts],
                            key = it[SiteProblemTable.key]
                        )
                    )
                }
        }

    private suspend fun createProblem(
        problem: SiteProblem,
        now: Instant
    ) {
        val firstOccurredDate = now.toLocalDateTime(TimeZone.UTC).date.toString()
        val listId = getOrCreateSlackListId()

        if (listId == null) {
            logger.error("No Slack list ID found, could not create Slack item")
            return
        }

        val fields = buildCells(
            problem = problem,
            firstOccurredDate = firstOccurredDate,
            problemSince = "0 days"
        )

        val itemId = createSlackListItem(listId, fields) ?: return

        sendNewProblemMessage(problem, itemId, listId)

        database {
            SiteProblemTable.insert {
                it[SiteProblemTable.site] = problem.site
                it[SiteProblemTable.itemId] = itemId
                it[SiteProblemTable.key] = problem.key
                it[SiteProblemTable.problem] = problem.problem
                it[SiteProblemTable.severity] = problem.severity
                it[SiteProblemTable.recordType] = problem.recordType
                it[SiteProblemTable.recordTarget] = problem.recordTarget
                it[SiteProblemTable.endpoint] = problem.url.toString()
                it[SiteProblemTable.details] = problem.details
                it[SiteProblemTable.remoteAddress] = problem.remoteAddress
                it[SiteProblemTable.exception] = problem.exception
                it[SiteProblemTable.techFacts] = problem.techFacts
                it[SiteProblemTable.firstOccurred] = now
                it[SiteProblemTable.lastOccurred] = now
            }
        }
    }

    private suspend fun updateProblem(
        problem: SiteProblem,
        existing: ExistingProblem,
        now: Instant
    ) {
        val days = maxOf(0L, (now - existing.firstOccurred).inWholeDays)
        val firstOccurredDate = existing.firstOccurred
            .toLocalDateTime(TimeZone.UTC)
            .date
            .toString()
        val problemSince = "$days days"

        getOrCreateSlackListId()?.let { listId ->
            updateSlackListItem(
                listId = listId,
                cells = buildCells(
                    rowId = existing.itemId,
                    problem = problem,
                    firstOccurredDate = firstOccurredDate,
                    problemSince = problemSince
                )
            )

            if (existing.problem.isDifferentFrom(problem)) {
                sendUpdateProblemMessage(problem, existing.itemId, listId)
            }
        }

        database {
            SiteProblemTable.update({ SiteProblemTable.site eq problem.site }) {
                it[SiteProblemTable.key] = problem.key
                it[SiteProblemTable.problem] = problem.problem
                it[SiteProblemTable.severity] = problem.severity
                it[SiteProblemTable.recordType] = problem.recordType
                it[SiteProblemTable.recordTarget] = problem.recordTarget
                it[SiteProblemTable.endpoint] = problem.url.toString()
                it[SiteProblemTable.details] = problem.details
                it[SiteProblemTable.remoteAddress] = problem.remoteAddress
                it[SiteProblemTable.exception] = problem.exception
                it[SiteProblemTable.techFacts] = problem.techFacts
                it[SiteProblemTable.lastOccurred] = now
            }
        }
    }

    // call this method when a site does not produce any problems
    // if the site was previously a problem, it will be removed from the database and the list item will be deleted
    // if the site was not previously a problem, nothing will happen
    suspend fun success(site: String) {
        try {
            val problem = database {
                SiteProblemTable.select(
                    SiteProblemTable.site,
                    SiteProblemTable.itemId,
                    SiteProblemTable.endpoint,
                    SiteProblemTable.problem
                )
                    .where { SiteProblemTable.site eq site }
                    .singleOrNull()
                    ?.let {
                        DestructuringComponent(
                            it[SiteProblemTable.site],
                            it[SiteProblemTable.itemId],
                            it[SiteProblemTable.endpoint],
                            it[SiteProblemTable.problem]
                        )
                    }
            } ?: return

            database {
                SiteProblemTable.deleteWhere { SiteProblemTable.site eq site }
            }

            sendNoLongerProblemMessage(
                problemUrl = Url(problem.component3()),
                problemSite = problem.component1(),
                problemType = problem.component4()
            )

            if (!deleteChannel.trySend(problem.component2()).isSuccess || !running.load()) {
                deleteSingleItem(problem.component2())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to process success for site $site", e)
        }
    }

    private suspend fun getOrCreateSlackListId(): String? {
        cachedListId?.let { return it }

        val persisted = runCatching {
            PersistentDataTable.getValue(PERSISTENT_LIST_ID_KEY)
        }.getOrNull()

        if (!persisted.isNullOrBlank()) {
            cachedListId = persisted
            runCatching {
                SlackListColumnTable.getAllColumnIds()
            }.getOrNull()?.let(cachedColumnIds::putAll)

            updateListAccess(persisted)
            return persisted
        }

        return createListMutex.withLock {
            cachedListId?.let { return@withLock it }

            createSlackList()?.also { created ->
                cachedListId = created
                updateListAccess(created)
            }
        }
    }

    private suspend fun getColumnId(key: String): String? {
        cachedColumnIds[key]?.let { return it }

        return runCatching {
            SlackListColumnTable.getColumnId(key)
        }.getOrNull()?.also {
            cachedColumnIds[key] = it
        }
    }

    private suspend fun createSlackList(): String? {
        if (Config.SLACK_BOT_TOKEN.isBlank()) {
            logger.warn("SLACK_BOT_TOKEN is blank, skipping createSlackList")
            return null
        }

        return try {
            val response = SharedConstants.client.post("https://slack.com/api/slackLists.create") {
                bearerAuth(Config.SLACK_BOT_TOKEN)
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("name", "Site Checker Issues")
                        putJsonArray("schema") {
                            addJsonObject {
                                // we need to set the key to name, because Slack uses the column with the key "name"
                                // when displaying the list record in a message. If there was no a column with the key "name",
                                // all the references in message would be called "Untitled Item", and we don't want that
                                // (took me some time to figure that out, because Slack doesn't tell you this in its documentation)
                                put("key", "name")
                                put("name", "Site")
                                put("type", "text")
                                put("is_primary_column", true)
                            }
                            addSelectColumn(
                                key = "severity",
                                name = "Severity",
                                choices = SiteProblemSeverity.entries.map {
                                    Triple(it.slackOptionId, it.slackOptionName, it.slackColor)
                                }
                            )
                            addSelectColumn(
                                key = "problems",
                                name = "Problem",
                                choices = SiteProblemType.entries.map {
                                    Triple(it.slackOptionId, it.slackOptionName, it.slackColor)
                                }
                            )
                            addSelectColumn(
                                key = "record_type",
                                name = "Record Type",
                                choices = RecordType.checkable.map {
                                    Triple(it.slackOptionId, it.slackOptionName, it.slackOptionColor)
                                }
                            )
                            addTextColumn("record_target", "Record Target")
                            addLinkColumn("endpoint", "Endpoint")
                            addTextColumn("details", "Details")
                            addTextColumn("remote_address", "Remote address")
                            addTextColumn("exception", "Exception")
                            addDateColumn("first_occurred", "First occurred")
                            addTextColumn("problem_since", "Problem since")
                            addTextColumn("tech_facts", "Tech facts")
                        }
                    }
                )
            }

            val text = response.bodyAsText()
            val json = Json.parseToJsonElement(text).jsonObject

            if (json["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                logger.error("Failed to create Slack list: $text")
                return null
            }

            val listId = json["list_id"]?.jsonPrimitive?.contentOrNull
                ?: json["list"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                ?: json["id"]?.jsonPrimitive?.contentOrNull

            if (listId == null) {
                logger.warn("Created Slack list but could not parse list_id from response: $text")
                return null
            }

            PersistentDataTable.setValue(PERSISTENT_LIST_ID_KEY, listId)
            logger.info("Created Slack list with ID: $listId")

            val schemaArray = json["list_metadata"]?.jsonObject?.get("schema")?.jsonArray
                ?: json["schema"]?.jsonArray

            schemaArray
                ?.mapNotNull { element ->
                    val column = element.jsonObject
                    val key = column["key"]?.jsonPrimitive?.contentOrNull
                    val id = column["id"]?.jsonPrimitive?.contentOrNull
                    key?.let { value -> id?.let { value to it } }
                }
                ?.toMap()
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    SlackListColumnTable.saveColumnIds(it)
                    cachedColumnIds.putAll(it)
                }

            listId
        } catch (e: Exception) {
            logger.error("Exception while creating Slack list", e)
            null
        }
    }

    private fun JsonArrayBuilder.addSelectColumn(
        key: String,
        name: String,
        choices: List<Triple<String, String, String>>
    ) {
        addJsonObject {
            put("key", key)
            put("name", name)
            put("type", "select")
            putJsonObject("options") {
                put("format", "single_select")
                putJsonArray("choices") {
                    choices.forEach { (value, label, color) ->
                        addJsonObject {
                            put("value", value)
                            put("label", label)
                            put("color", color)
                        }
                    }
                }
            }
        }
    }

    private fun JsonArrayBuilder.addTextColumn(key: String, name: String) {
        addJsonObject {
            put("key", key)
            put("name", name)
            put("type", "text")
        }
    }

    private fun JsonArrayBuilder.addLinkColumn(key: String, name: String) {
        addJsonObject {
            put("key", key)
            put("name", name)
            put("type", "link")
        }
    }

    private fun JsonArrayBuilder.addDateColumn(key: String, name: String) {
        addJsonObject {
            put("key", key)
            put("name", name)
            put("type", "date")
        }
    }

    private suspend fun updateListAccess(id: String) {
        if (accessUpdated.load()) return
        accessMutex.withLock {
            if (accessUpdated.load()) return

            suspend fun call(
                accessLevel: String,
                channelIds: List<String> = emptyList(),
                userIds: List<String> = emptyList()
            ) {
                val jsonBody = buildJsonObject {
                    put("list_id", id)
                    put("access_level", accessLevel)

                    when {
                        channelIds.isNotEmpty() -> putJsonArray("channel_ids") {
                            channelIds.forEach(::add)
                        }

                        userIds.isNotEmpty() -> putJsonArray("user_ids") {
                            userIds.forEach(::add)
                        }

                        else -> throw IllegalStateException("No access level specified")
                    }
                }

                SharedConstants.client.post("https://slack.com/api/slackLists.access.set") {
                    bearerAuth(Config.SLACK_BOT_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody)
                }
            }

            val channelId = Config.SLACK_CHANNEL_FOR_ALERTS
            if (channelId.isNotEmpty()) {
                try {
                    call("read", channelIds = listOf(channelId))
                    logger.info("Set read access for list to channel $channelId")
                } catch (e: Exception) {
                    logger.error("Exception while trying to set read access for list to channel $channelId", e)
                }
            } else {
                logger.warn("Could not set read access for list to alert channel because no channel ID was provided")
            }

            val userOwnerId = Config.SLACK_LIST_OWNER_ID
            if (userOwnerId.isNotEmpty()) {
                try {
                    call("owner", userIds = listOf(userOwnerId))
                    logger.info("Set owner of list to user $userOwnerId")
                } catch (e: Exception) {
                    logger.error("Exception while trying to set owner of list to user $userOwnerId", e)
                }
            } else {
                logger.warn("Could not set owner of list because no user ID was provided")
            }

            accessUpdated.store(true)
        }
    }

    private suspend fun sendNewProblemMessage(
        problem: SiteProblem,
        itemId: String,
        listId: String
    ) = sendProblemMessage(
        emoji = "siren1",
        title = "Site problem detected: ",
        problem = problem,
        itemId = itemId,
        listId = listId
    )

    private suspend fun sendUpdateProblemMessage(
        problem: SiteProblem,
        itemId: String,
        listId: String
    ) = sendProblemMessage(
        emoji = "arrows_counterclockwise",
        title = "Site problem updated: ",
        problem = problem,
        itemId = itemId,
        listId = listId
    )

    private suspend fun sendProblemMessage(
        emoji: String,
        title: String,
        problem: SiteProblem,
        itemId: String,
        listId: String
    ) = postMessage(
        buildJsonObject {
            putJsonArray("blocks") {
                addJsonObject {
                    put("type", "rich_text")
                    putJsonArray("elements") {
                        addJsonObject {
                            put("type", "rich_text_section")
                            putJsonArray("elements") {
                                addJsonObject {
                                    put("type", "emoji")
                                    put("name", emoji)
                                }
                                addJsonObject {
                                    put("type", "text")
                                    put("text", " $title")
                                    putJsonObject("style") {
                                        put("bold", true)
                                    }
                                }
                                addJsonObject {
                                    put("type", "link")
                                    put("url", problem.url.toString())
                                    put("text", problem.site)
                                    // when truncated is true, slack will display the link in a much nicer way,
                                    // instead of just the plain link
                                    put("truncated", true)
                                }
                                addJsonObject {
                                    put("type", "text")
                                    put("text", " ")
                                }
                                addJsonObject {
                                    // the tag element is pretty cool (https://docs.slack.dev/reference/block-kit/block-elements/tag-element)
                                    // it allows us to display text as a tag or pill with color
                                    put("type", "tag")
                                    put("text", "(${problem.problem.slackOptionName})")
                                    put("color", problem.problem.slackColor)
                                }
                                addJsonObject {
                                    put("type", "text")
                                    put("text", " ")
                                }
                                addJsonObject {
                                    put("type", "list_record")
                                    put("file_id", listId)
                                    put("record_id", itemId)
                                    // I don't know why list_record (https://docs.slack.dev/reference/block-kit/block-elements/list-record-element)
                                    // has a property called "text", because it does absolutely nothing
                                    put("text", "View in Lists")
                                    put(
                                        "url",
                                        "https://hackclub.enterprise.slack.com/lists/T0266FRGM/$listId?record_id=$itemId"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    )

    private suspend fun sendNoLongerProblemMessage(
        problemUrl: Url,
        problemSite: String,
        problemType: SiteProblemType
    ) = postMessage(
        buildJsonObject {
            putJsonArray("blocks") {
                addJsonObject {
                    put("type", "rich_text")
                    putJsonArray("elements") {
                        addJsonObject {
                            put("type", "rich_text_section")
                            putJsonArray("elements") {
                                addJsonObject {
                                    put("type", "emoji")
                                    put("name", "white_check_mark")
                                }
                                addJsonObject {
                                    put("type", "text")
                                    put("text", " Site problem resolved: ")
                                    putJsonObject("style") {
                                        put("bold", true)
                                    }
                                }
                                addJsonObject {
                                    put("type", "link")
                                    put("url", problemUrl.toString())
                                    put("text", problemSite)
                                    put("truncated", true)
                                }
                                addJsonObject {
                                    put("type", "text")
                                    put("text", " ")
                                }
                                addJsonObject {
                                    put("type", "tag")
                                    put("text", "(${problemType.slackOptionName})")
                                    put("color", problemType.slackColor)
                                }
                                addJsonObject {
                                    put("type", "text")
                                    put("text", " is no longer a problem.")
                                }
                            }
                        }
                    }
                }
            }
        }
    )

    private suspend fun postMessage(payload: JsonObject) {
        val slackChannelForAlerts = Config.SLACK_CHANNEL_FOR_ALERTS
        if (slackChannelForAlerts.isBlank()) return

        postMessageLimiter.awaitRateLimit()

        val payloadJson = JsonObject(
            payload.toMutableMap().apply {
                put("channel", JsonPrimitive(slackChannelForAlerts))

                // this would spam the channel with the link and list_record previews
                putIfAbsent("unfurl_links", JsonPrimitive(false))
                putIfAbsent("unfurl_media", JsonPrimitive(false))
            }
        )

        runCatching {
            val response = SharedConstants.client.post("https://slack.com/api/chat.postMessage") {
                bearerAuth(Config.SLACK_BOT_TOKEN)
                contentType(ContentType.Application.Json)
                setBody(payloadJson)
            }

            val responseJson = response.body<JsonObject>()
            if (response.status.isSuccess() &&
                responseJson["ok"]?.jsonPrimitive?.booleanOrNull == true
            ) {
                //                logger.info("Slack notification sent successfully")
                return@runCatching
            }

            val error = responseJson["error"]?.jsonPrimitive?.contentOrNull
            when {
                response.status.value == 400 && error == "invalid_blocks" -> {
                    // this should never happen in a production environment,
                    // the only reason for this to happen if we changed the building mechanics and did an error
                    logger.error("Failed to send Slack notification due to invalid blocks: $payload")
                }

                error == "not_in_channel" -> {
                    logger.error("Failed to send Slack notification due to bot not in channel (please add the bot to the channel): $payload")
                }

                error == "channel_not_found" -> {
                    logger.error("Failed to send Slack notification due to channel not found (check the channel you specified): $payload")
                }

                else -> {
                    logger.error("Slack notification failed — status: ${response.status}, body: $responseJson")
                }
            }
        }.onFailure {
            logger.error("Slack notification error", it)
        }
    }

    private fun buildRichTextValue(text: String): JsonArray =
        buildJsonArray {
            addJsonObject {
                put("type", "rich_text")
                putJsonArray("elements") {
                    addJsonObject {
                        put("type", "rich_text_section")
                        putJsonArray("elements") {
                            addJsonObject {
                                put("type", "text")
                                put("text", text)
                            }
                        }
                    }
                }
            }
        }

    private fun buildSelectValue(value: String): JsonArray =
        buildJsonArray {
            add(JsonPrimitive(value))
        }

    private fun buildLinkValue(url: String): JsonArray =
        buildJsonArray {
            addJsonObject {
                put("original_url", url)
                put("display_as_url", true)
            }
        }

    private fun buildDateValue(date: String): JsonArray =
        buildJsonArray {
            add(JsonPrimitive(date))
        }

    private suspend fun buildCells(
        problem: SiteProblem,
        firstOccurredDate: String,
        problemSince: String,
        rowId: String? = null
    ): JsonArray {
        val values = listOf(
            CellValue("name", "rich_text", buildRichTextValue(problem.site)),
            CellValue("severity", "select", buildSelectValue(problem.severity.slackOptionId)),
            CellValue("problems", "select", buildSelectValue(problem.problem.slackOptionId)),
            CellValue("record_type", "select", buildSelectValue(problem.recordType.slackOptionId)),
            CellValue("record_target", "rich_text", buildRichTextValue(problem.recordTarget)),
            CellValue("endpoint", "link", buildLinkValue(problem.url.toString())),
            CellValue("details", "rich_text", buildRichTextValue(problem.details)),
            CellValue("first_occurred", "date", buildDateValue(firstOccurredDate)),
            CellValue("problem_since", "rich_text", buildRichTextValue(problemSince))
        ) + listOfNotNull(
            problem.remoteAddress?.let {
                CellValue("remote_address", "rich_text", buildRichTextValue(it))
            },
            problem.exception?.let {
                CellValue("exception", "rich_text", buildRichTextValue(it))
            },
            problem.techFacts?.let {
                CellValue("tech_facts", "rich_text", buildRichTextValue(it))
            }
        )

        return buildJsonArray {
            values.forEach { value ->
                getColumnId(value.columnKey)?.let { columnId ->
                    addJsonObject {
                        put("column_id", columnId)
                        rowId?.let { put("row_id", it) }
                        put(value.type, value.value)
                    }
                }
            }
        }
    }

    private data class CellValue(
        val columnKey: String,
        val type: String,
        val value: JsonArray
    )

    private suspend fun createSlackListItem(
        listId: String,
        fields: JsonArray
    ): String? {
        createUpdateLimiter.awaitRateLimit()

        return try {
            val json = slackRequest(
                endpoint = "https://slack.com/api/slackLists.items.create",
                body = buildJsonObject {
                    put("list_id", listId)
                    put("initial_fields", fields)
                }
            ) ?: return null

            json["item"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                ?: json["item_id"]?.jsonPrimitive?.contentOrNull
                ?: json["id"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            logger.error("Exception while creating Slack list item", e)
            null
        }
    }

    private suspend fun updateSlackListItem(
        listId: String,
        cells: JsonArray
    ) {
        createUpdateLimiter.awaitRateLimit()

        try {
            slackRequest(
                endpoint = "https://slack.com/api/slackLists.items.update",
                body = buildJsonObject {
                    put("list_id", listId)
                    put("cells", cells)
                }
            )
        } catch (e: Exception) {
            logger.error("Exception while updating Slack list item", e)
        }
    }

    private suspend fun deleteSingleItem(itemId: String) {
        deleteLimiter.awaitRateLimit()

        val listId = getOrCreateSlackListId() ?: return

        try {
            slackRequest(
                endpoint = "https://slack.com/api/slackLists.items.delete",
                body = buildJsonObject {
                    put("list_id", listId)
                    put("id", itemId)
                }
            )
        } catch (e: Exception) {
            logger.error("Exception while deleting Slack list item $itemId", e)
        }
    }

    private suspend fun flushDeletions(itemIds: List<String>) {
        if (itemIds.isEmpty()) return

        val listId = getOrCreateSlackListId() ?: return

        try {
            if (itemIds.size == 1) {
                deleteLimiter.awaitRateLimit()

                slackRequest(
                    endpoint = "https://slack.com/api/slackLists.items.delete",
                    body = buildJsonObject {
                        put("list_id", listId)
                        put("id", itemIds.first())
                    }
                )
            } else {
                deleteMultiLimiter.awaitRateLimit()

                slackRequest(
                    endpoint = "https://slack.com/api/slackLists.items.deleteMultiple",
                    body = buildJsonObject {
                        put("list_id", listId)
                        putJsonArray("ids") {
                            itemIds.forEach(::add)
                        }
                    }
                )
            }
        } catch (e: Exception) {
            logger.error("Exception while deleting Slack list items: $itemIds", e)
        }
    }

    private suspend fun slackRequest(
        endpoint: String,
        body: JsonObject
    ): JsonObject? {
        val response = SharedConstants.client.post(endpoint) {
            bearerAuth(Config.SLACK_BOT_TOKEN)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        val text = response.bodyAsText()
        val json = Json.parseToJsonElement(text).jsonObject

        if (json["ok"]?.jsonPrimitive?.booleanOrNull == true) {
            return json
        }

        logger.error("Slack request failed: endpoint=$endpoint, status=${response.status}, body=$text")
        return null
    }

    private suspend fun runDeletionWorker() {
        val batch = mutableListOf<String>()

        while (scope.isActive) {
            try {
                val item = withTimeoutOrNull(100.milliseconds) {
                    deleteChannel.receive()
                }

                if (item != null) {
                    batch.add(item)

                    while (batch.size < MAX_DELETE_BATCH_SIZE) { // i dont know where the limit is, it is not in the slack api docs. One time I deleted 351 rows, so I set it to 200
                        deleteChannel.tryReceive().getOrNull()?.let(batch::add) ?: break
                    }
                }

                if (batch.isNotEmpty()) {
                    flushDeletions(batch)
                    batch.clear()
                }
            } catch (e: CancellationException) {
                if (batch.isNotEmpty()) {
                    flushDeletions(batch)
                    batch.clear()
                }

                throw e
            } catch (e: Exception) {
                logger.error("Error in Slack deletion worker", e)
            }
        }
    }
}