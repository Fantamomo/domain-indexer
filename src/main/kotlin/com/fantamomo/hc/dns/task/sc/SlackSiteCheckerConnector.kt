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
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

// The list in slack looks the following (format: `"<name>"("<id>"): <type><metadata (optional)>; <description>`):
// "Site"("site"): text, primary column; contains the site name (e.g., "example.com")
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

    private class RateLimiter(maxRequestsPerSecond: Int) {
        private val intervalNanos = 1_000_000_000L / maxRequestsPerSecond
        private val lock = Any()

        @Volatile
        private var nextRequestAt = System.nanoTime()

        suspend fun awaitRateLimit() {
            val delayNanos = synchronized(lock) {
                val now = System.nanoTime()
                val waitNanos = nextRequestAt - now
                nextRequestAt = maxOf(nextRequestAt + intervalNanos, now)
                waitNanos
            }
            if (delayNanos > 0) {
                delay(delayNanos.nanoseconds)
            }
        }
    }

    private val createUpdateLimiter = RateLimiter(50)
    private val deleteLimiter = RateLimiter(20)

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
    private val accessUpdated = AtomicBoolean(false)
    private var cachedListId: String? = null
    private val cachedColumnIds = ConcurrentHashMap<String, String>()

    private val databaseSemaphore = Semaphore(8)

    private suspend fun <T> database(block: suspend R2dbcTransaction.() -> T): T {
        databaseSemaphore.withPermit {
            return DatabaseManager.transaction(block = block)
        }
    }

    // starts a coroutine scope
    // which is used by the following two methods to schedule tasks
    suspend fun start() {
        if (!running.compareAndSet(expectedValue = false, newValue = true)) {
            return
        }

        logger.info("SlackSiteCheckerConnector started")

        val slackInitializationJob = scope.launch {
            try {
                getOrCreateSlackListId()
            } catch (e: Exception) {
                logger.error("Failed to initialize Slack list during start", e)
            }
        }

        val deletionJob = scope.launch {
            runDeletionWorker()
        }

        slackInitializationJob.join()
        deletionJob.join()
    }

    // call this method with a problem, the method will store it in the database and send it to slack
    // if the problem is new, it will create a new list item to store it
    // if the problem is not new, it will update the existing list item
    suspend fun problem(problem: SiteProblem) {
        try {
            val existing = database {
                SiteProblemTable.select(
                    SiteProblemTable.site,
                    SiteProblemTable.itemId,
                    SiteProblemTable.firstOccurred
                )
                    .where { SiteProblemTable.site eq problem.site }
                    .singleOrNull()
            }

            val now = Clock.System.now()

            if (existing == null) {
                val firstOccurred = now
                val firstOccurredDate = firstOccurred.toLocalDateTime(TimeZone.UTC).date.toString()
                val problemSince = "0 days"

                val listId = getOrCreateSlackListId()
                if (listId == null) {
                    logger.error("No Slack list ID found, could not create Slack item")
                    return
                }
                val fields = buildItemFields(
                    problem = problem,
                    firstOccurredDate = firstOccurredDate,
                    problemSince = problemSince
                )

                val itemId = createSlackListItem(listId, fields) ?: return

                database {
                    SiteProblemTable.insert {
                        it[SiteProblemTable.site] = problem.site
                        it[SiteProblemTable.itemId] = itemId
                        it[SiteProblemTable.problem] = problem.problem
                        it[SiteProblemTable.severity] = problem.severity
                        it[SiteProblemTable.recordType] = problem.recordType
                        it[SiteProblemTable.recordTarget] = problem.recordTarget
                        it[SiteProblemTable.endpoint] = problem.url.toString()
                        it[SiteProblemTable.details] = problem.details
                        it[SiteProblemTable.remoteAddress] = problem.remoteAddress
                        it[SiteProblemTable.exception] = problem.exception
                        it[SiteProblemTable.techFacts] = problem.techFacts
                        it[SiteProblemTable.firstOccurred] = firstOccurred
                        it[SiteProblemTable.lastOccurred] = now
                    }
                }
            } else {
                val itemId = existing[SiteProblemTable.itemId]
                val firstOccurred = existing[SiteProblemTable.firstOccurred]
                val days = maxOf(0L, (now - firstOccurred).inWholeDays)
                val firstOccurredDate = firstOccurred.toLocalDateTime(TimeZone.UTC).date.toString()
                val problemSince = "$days days"

                val listId = getOrCreateSlackListId()
                if (listId != null) {
                    val cells = buildUpdateCells(
                        rowId = itemId,
                        problem = problem,
                        firstOccurredDate = firstOccurredDate,
                        problemSince = problemSince
                    )
                    updateSlackListItem(listId, cells)
                }

                database {
                    SiteProblemTable.update({ SiteProblemTable.site eq problem.site }) {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to process problem for site ${problem.site}", e)
        }
    }

    suspend fun problem(
        site: String,
        problem: SiteProblemType,
        recordType: RecordType,
        recordTarget: String,
        endpoint: Url,
        details: String,
        remoteAddress: String? = null,
        exception: String? = null,
        techFacts: String? = null
    ) {
        problem(
            SiteProblem(
                site = site,
                problem = problem,
                recordType = recordType,
                recordTarget = recordTarget,
                url = endpoint,
                details = details,
                remoteAddress = remoteAddress,
                exception = exception,
                techFacts = techFacts
            )
        )
    }

    // call this method when a site does not produce any problems
    // if the site was previously a problem, it will be removed from the database and the list item will be deleted
    // if the site was not previously a problem, nothing will happen
    suspend fun success(site: String) {
        try {
            val existing = database {
                SiteProblemTable.select(SiteProblemTable.itemId)
                    .where { SiteProblemTable.site eq site }
                    .singleOrNull()
            } ?: return

            val itemId = existing[SiteProblemTable.itemId]

            database {
                SiteProblemTable.deleteWhere { SiteProblemTable.site eq site }
            }

            if (running.load()) {
                val sent = deleteChannel.trySend(itemId).isSuccess
                if (!sent) {
                    deleteSingleItem(itemId)
                }
            } else {
                deleteSingleItem(itemId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to process success for site $site", e)
        }
    }

    private suspend fun getOrCreateSlackListId(): String? {
        cachedListId?.let { return it }

        val persisted = runCatching { PersistentDataTable.getValue(PERSISTENT_LIST_ID_KEY) }.getOrNull()
        if (!persisted.isNullOrBlank()) {
            cachedListId = persisted
            val cols = runCatching { SlackListColumnTable.getAllColumnIds() }.getOrNull()
            if (cols != null) {
                cachedColumnIds.putAll(cols)
            }
            updateListAccess(persisted)
            return persisted
        }

        createListMutex.withLock {
            cachedListId?.let { return it }

            val created = createSlackList()
            if (!created.isNullOrBlank()) {
                cachedListId = created
                updateListAccess(created)
                return created
            }
        }

        return null
    }

    private suspend fun getColumnId(key: String): String? {
        cachedColumnIds[key]?.let { return it }
        val colId = runCatching { SlackListColumnTable.getColumnId(key) }.getOrNull()
        if (colId != null) {
            cachedColumnIds[key] = colId
            return colId
        }
        return null
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
                setBody(buildJsonObject {
                    put("name", "Site Checker Issues")
                    putJsonArray("schema") {
                        add(buildJsonObject {
                            put("key", "site")
                            put("name", "Site")
                            put("type", "text")
                            put("is_primary_column", true)
                        })
                        add(buildJsonObject {
                            put("key", "severity")
                            put("name", "Severity")
                            put("type", "select")
                            putJsonObject("options") {
                                put("format", "single_select")
                                putJsonArray("choices") {
                                    for (sev in SiteProblemSeverity.entries) {
                                        add(buildJsonObject {
                                            put("value", sev.slackOptionId)
                                            put("label", sev.slackOptionName)
                                            put("color", sev.slackColor)
                                        })
                                    }
                                }
                            }
                        })
                        add(buildJsonObject {
                            put("key", "problems")
                            put("name", "Problem")
                            put("type", "select")
                            putJsonObject("options") {
                                put("format", "single_select")
                                putJsonArray("choices") {
                                    for (problemType in SiteProblemType.entries) {
                                        add(buildJsonObject {
                                            put("value", problemType.slackOptionId)
                                            put("label", problemType.slackOptionName)
                                            put("color", problemType.slackColor)
                                        })
                                    }
                                }
                            }
                        })
                        add(buildJsonObject {
                            put("key", "record_type")
                            put("name", "Record Type")
                            put("type", "select")
                            putJsonObject("options") {
                                put("format", "single_select")
                                putJsonArray("choices") {
                                    for (recType in RecordType.checkable) {
                                        add(buildJsonObject {
                                            put("value", recType.slackOptionId)
                                            put("label", recType.slackOptionName)
                                            put("color", recType.slackOptionColor)
                                        })
                                    }
                                }
                            }
                        })
                        add(buildJsonObject {
                            put("key", "record_target")
                            put("name", "Record Target")
                            put("type", "text")
                        })
                        add(buildJsonObject {
                            put("key", "endpoint")
                            put("name", "Endpoint")
                            put("type", "link")
                        })
                        add(buildJsonObject {
                            put("key", "details")
                            put("name", "Details")
                            put("type", "text")
                        })
                        add(buildJsonObject {
                            put("key", "remote_address")
                            put("name", "Remote address")
                            put("type", "text")
                        })
                        add(buildJsonObject {
                            put("key", "exception")
                            put("name", "Exception")
                            put("type", "text")
                        })
                        add(buildJsonObject {
                            put("key", "first_occurred")
                            put("name", "First occurred")
                            put("type", "date")
                        })
                        add(buildJsonObject {
                            put("key", "problem_since")
                            put("name", "Problem since")
                            put("type", "text")
                        })
                        add(buildJsonObject {
                            put("key", "tech_facts")
                            put("name", "Tech facts")
                            put("type", "text")
                        })
                    }
                })
            }

            val text = response.bodyAsText()
            val json = Json.parseToJsonElement(text).jsonObject

            if (json["ok"]?.jsonPrimitive?.booleanOrNull == true) {
                val listId = json["list_id"]?.jsonPrimitive?.contentOrNull
                    ?: json["list"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                    ?: json["id"]?.jsonPrimitive?.contentOrNull

                if (listId != null) {
                    PersistentDataTable.setValue(PERSISTENT_LIST_ID_KEY, listId)
                    logger.info("Created Slack list with ID: $listId")

                    val schemaArray = json["list_metadata"]?.jsonObject?.get("schema")?.jsonArray
                        ?: json["schema"]?.jsonArray
                    if (schemaArray != null) {
                        val columns = mutableMapOf<String, String>()
                        for (element in schemaArray) {
                            val colObj = element.jsonObject
                            val key = colObj["key"]?.jsonPrimitive?.contentOrNull
                            val colId = colObj["id"]?.jsonPrimitive?.contentOrNull
                            if (key != null && colId != null) {
                                columns[key] = colId
                            }
                        }
                        if (columns.isNotEmpty()) {
                            SlackListColumnTable.saveColumnIds(columns)
                            cachedColumnIds.putAll(columns)
                        }
                    }

                    listId
                } else {
                    logger.warn("Created Slack list but could not parse list_id from response: $text")
                    null
                }
            } else {
                logger.error("Failed to create Slack list: $text")
                null
            }
        } catch (e: Exception) {
            logger.error("Exception while creating Slack list", e)
            null
        }
    }

    private suspend fun updateListAccess(id: String) {
        if (accessUpdated.compareAndExchange(expectedValue = false, newValue = true)) {
            return
        }

        suspend fun call(
            accessLevel: String,
            channelIds: List<String> = emptyList(),
            userIds: List<String> = emptyList()
        ) {
            val jsonBody = buildJsonObject {
                put("list_id", id)
                put("access_level", accessLevel)
                if (channelIds.isNotEmpty()) {
                    putJsonArray("channel_ids") {
                        channelIds.forEach { add(it) }
                    }
                } else if (userIds.isNotEmpty()) {
                    putJsonArray("user_ids") {
                        userIds.forEach { add(it) }
                    }
                } else {
                    throw IllegalStateException("No access level specified")
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
    }

    private fun buildRichTextValue(text: String): JsonArray {
        return buildJsonArray {
            add(buildJsonObject {
                put("type", "rich_text")
                putJsonArray("elements") {
                    add(buildJsonObject {
                        put("type", "rich_text_section")
                        putJsonArray("elements") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", text)
                            })
                        }
                    })
                }
            })
        }
    }

    private fun buildSelectValue(value: String): JsonArray {
        return buildJsonArray {
            add(JsonPrimitive(value))
        }
    }

    private fun buildLinkValue(url: String): JsonArray {
        return buildJsonArray {
            add(buildJsonObject {
                put("original_url", url)
                put("display_as_url", true)
            })
        }
    }

    private fun buildDateValue(date: String): JsonArray {
        return buildJsonArray {
            add(JsonPrimitive(date))
        }
    }

    private suspend fun buildItemFields(
        problem: SiteProblem,
        firstOccurredDate: String,
        problemSince: String
    ): JsonArray {
        return buildJsonArray {
            getColumnId("site")?.let { colId ->
                add(buildJsonObject {
                    put("column_id", colId)
                    put("rich_text", buildRichTextValue(problem.site))
                })
            }
            getColumnId("severity")?.let { colId ->
                add(buildJsonObject {
                    put("column_id", colId)
                    put("select", buildSelectValue(problem.severity.slackOptionId))
                })
            }
            getColumnId("problems")?.let { colId ->
                add(buildJsonObject {
                    put("column_id", colId)
                    put("select", buildSelectValue(problem.problem.slackOptionId))
                })
            }
            getColumnId("record_type")?.let { colId ->
                add(buildJsonObject {
                    put("column_id", colId)
                    put("select", buildSelectValue(problem.recordType.slackOptionId))
                })
            }
            getColumnId("record_target")?.let { colId ->
                add(buildJsonObject {
                    put("column_id", colId)
                    put("rich_text", buildRichTextValue(problem.recordTarget))
                })
            }
            getColumnId("endpoint")?.let { colId ->
                add(buildJsonObject {
                    put("column_id", colId)
                    put("link", buildLinkValue(problem.url.toString()))
                })
            }
            getColumnId("details")?.let { colId ->
                add(buildJsonObject {
                    put("column_id", colId)
                    put("rich_text", buildRichTextValue(problem.details))
                })
            }
            getColumnId("first_occurred")?.let { colId ->
                add(buildJsonObject {
                    put("column_id", colId)
                    put("date", buildDateValue(firstOccurredDate))
                })
            }
            getColumnId("problem_since")?.let { colId ->
                add(buildJsonObject {
                    put("column_id", colId)
                    put("rich_text", buildRichTextValue(problemSince))
                })
            }
            if (problem.remoteAddress != null) {
                getColumnId("remote_address")?.let { colId ->
                    add(buildJsonObject {
                        put("column_id", colId)
                        put("rich_text", buildRichTextValue(problem.remoteAddress))
                    })
                }
            }
            if (problem.exception != null) {
                getColumnId("exception")?.let { colId ->
                    add(buildJsonObject {
                        put("column_id", colId)
                        put("rich_text", buildRichTextValue(problem.exception))
                    })
                }
            }
            if (problem.techFacts != null) {
                getColumnId("tech_facts")?.let { colId ->
                    add(buildJsonObject {
                        put("column_id", colId)
                        put("rich_text", buildRichTextValue(problem.techFacts))
                    })
                }
            }
        }
    }

    private suspend fun buildUpdateCells(
        rowId: String,
        problem: SiteProblem,
        firstOccurredDate: String,
        problemSince: String
    ): JsonArray {
        return buildJsonArray {
            getColumnId("site")?.let { colId ->
                add(buildJsonObject {
                    put("column_id", colId)
                    put("row_id", rowId)
                    put("rich_text", buildRichTextValue(problem.site))
                })
            }
            getColumnId("severity")?.let { colId ->
                add(buildJsonObject {
                    put("column_id", colId)
                    put("row_id", rowId)
                    put("select", buildSelectValue(problem.severity.slackOptionId))
                })
            }
            getColumnId("problems")?.let { colId ->
                add(buildJsonObject {
                    put("column_id", colId)
                    put("row_id", rowId)
                    put("select", buildSelectValue(problem.problem.slackOptionId))
                })
            }
            getColumnId("record_type")?.let { colId ->
                add(buildJsonObject {
                    put("column_id", colId)
                    put("row_id", rowId)
                    put("select", buildSelectValue(problem.recordType.slackOptionId))
                })
            }
            getColumnId("record_target")?.let { colId ->
                add(buildJsonObject {
                    put("column_id", colId)
                    put("row_id", rowId)
                    put("rich_text", buildRichTextValue(problem.recordTarget))
                })
            }
            getColumnId("endpoint")?.let { colId ->
                add(buildJsonObject {
                    put("column_id", colId)
                    put("row_id", rowId)
                    put("link", buildLinkValue(problem.url.toString()))
                })
            }
            getColumnId("details")?.let { colId ->
                add(buildJsonObject {
                    put("column_id", colId)
                    put("row_id", rowId)
                    put("rich_text", buildRichTextValue(problem.details))
                })
            }
            getColumnId("first_occurred")?.let { colId ->
                add(buildJsonObject {
                    put("column_id", colId)
                    put("row_id", rowId)
                    put("date", buildDateValue(firstOccurredDate))
                })
            }
            getColumnId("problem_since")?.let { colId ->
                add(buildJsonObject {
                    put("column_id", colId)
                    put("row_id", rowId)
                    put("rich_text", buildRichTextValue(problemSince))
                })
            }
            if (problem.remoteAddress != null) {
                getColumnId("remote_address")?.let { colId ->
                    add(buildJsonObject {
                        put("column_id", colId)
                        put("row_id", rowId)
                        put("rich_text", buildRichTextValue(problem.remoteAddress))
                    })
                }
            }
            if (problem.exception != null) {
                getColumnId("exception")?.let { colId ->
                    add(buildJsonObject {
                        put("column_id", colId)
                        put("row_id", rowId)
                        put("rich_text", buildRichTextValue(problem.exception))
                    })
                }
            }
            if (problem.techFacts != null) {
                getColumnId("tech_facts")?.let { colId ->
                    add(buildJsonObject {
                        put("column_id", colId)
                        put("row_id", rowId)
                        put("rich_text", buildRichTextValue(problem.techFacts))
                    })
                }
            }
        }
    }

    private suspend fun createSlackListItem(listId: String, fields: JsonArray): String? {
        createUpdateLimiter.awaitRateLimit()
        return try {
            val response = SharedConstants.client.post("https://slack.com/api/slackLists.items.create") {
                bearerAuth(Config.SLACK_BOT_TOKEN)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("list_id", listId)
                    put("initial_fields", fields)
                })
            }

            val text = response.bodyAsText()
            val json = Json.parseToJsonElement(text).jsonObject

            if (json["ok"]?.jsonPrimitive?.booleanOrNull == true) {
                json["item"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                    ?: json["item_id"]?.jsonPrimitive?.contentOrNull
                    ?: json["id"]?.jsonPrimitive?.contentOrNull
            } else {
                logger.error("Failed to create Slack list item: $text")
                null
            }
        } catch (e: Exception) {
            logger.error("Exception while creating Slack list item", e)
            null
        }
    }

    private suspend fun updateSlackListItem(listId: String, cells: JsonArray) {
        createUpdateLimiter.awaitRateLimit()
        try {
            val response = SharedConstants.client.post("https://slack.com/api/slackLists.items.update") {
                bearerAuth(Config.SLACK_BOT_TOKEN)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("list_id", listId)
                    put("cells", cells)
                })
            }

            val text = response.bodyAsText()
            val json = Json.parseToJsonElement(text).jsonObject

            if (json["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                logger.error("Failed to update Slack list item: $text")
            }
        } catch (e: Exception) {
            logger.error("Exception while updating Slack list item", e)
        }
    }

    private suspend fun deleteSingleItem(itemId: String) {
        deleteLimiter.awaitRateLimit()
        val listId = getOrCreateSlackListId() ?: return
        try {
            val response = SharedConstants.client.post("https://slack.com/api/slackLists.items.delete") {
                bearerAuth(Config.SLACK_BOT_TOKEN)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("list_id", listId)
                    put("id", itemId)
                })
            }
            val text = response.bodyAsText()
            val json = Json.parseToJsonElement(text).jsonObject
            if (json["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                logger.error("Failed to delete Slack list item $itemId: $text")
            }
        } catch (e: Exception) {
            logger.error("Exception while deleting Slack list item $itemId", e)
        }
    }

    private suspend fun flushDeletions(itemIds: List<String>) {
        if (itemIds.isEmpty()) return
        deleteLimiter.awaitRateLimit()
        val listId = getOrCreateSlackListId() ?: return

        try {
            if (itemIds.size == 1) {
                val response = SharedConstants.client.post("https://slack.com/api/slackLists.items.delete") {
                    bearerAuth(Config.SLACK_BOT_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("list_id", listId)
                        put("id", itemIds.first())
                    })
                }
                val text = response.bodyAsText()
                val json = Json.parseToJsonElement(text).jsonObject
                if (json["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                    logger.error("Failed to delete Slack list item ${itemIds.first()}: $text")
                }
            } else {
                val response = SharedConstants.client.post("https://slack.com/api/slackLists.items.deleteMultiple") {
                    bearerAuth(Config.SLACK_BOT_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("list_id", listId)
                        putJsonArray("ids") {
                            itemIds.forEach { add(JsonPrimitive(it)) }
                        }
                    })
                }
                val text = response.bodyAsText()
                val json = Json.parseToJsonElement(text).jsonObject
                if (json["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                    logger.error("Failed to delete multiple Slack list items: $text")
                }
            }
        } catch (e: Exception) {
            logger.error("Exception while deleting Slack list items: $itemIds", e)
        }
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
                    while (batch.size < 200) { // i dont know where the limit is, it is not in the slack api docs. One time I deleted 250 rows, so I set it to 200
                        val next = deleteChannel.tryReceive().getOrNull() ?: break
                        batch.add(next)
                    }
                }
                if (batch.isNotEmpty()) {
                    flushDeletions(batch.toList())
                    batch.clear()
                }
            } catch (e: CancellationException) {
                if (batch.isNotEmpty()) {
                    flushDeletions(batch.toList())
                    batch.clear()
                }
                throw e
            } catch (e: Exception) {
                logger.error("Error in Slack deletion worker", e)
            }
        }
    }
}