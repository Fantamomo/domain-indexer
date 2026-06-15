package com.fantamomo.hc.dns.task

import com.fantamomo.hc.dns.App
import com.fantamomo.hc.dns.data.SharedConstants
import com.fantamomo.hc.dns.data.SharedValues
import com.fantamomo.hc.dns.data.SharedValues.git
import com.fantamomo.hc.dns.db.*
import com.fantamomo.hc.dns.manager.DatabaseManager
import com.fantamomo.hc.dns.manager.DnsManager
import com.fantamomo.hc.dns.model.Head
import com.fantamomo.hc.dns.model.SlackUserIdFoundState
import com.fantamomo.hc.dns.model.dns.*
import com.fantamomo.hc.dns.service.*
import com.fantamomo.hc.dns.task.init.GetForksInitTask
import com.fantamomo.hc.dns.util.SlackNotificationService
import com.fantamomo.hc.dns.util.humanReadable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.batchUpsert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

object Scheduler {
    private val TIME_BETWEEN_RUNS = 1.minutes

    private val logger = LoggerFactory.getLogger(Scheduler::class.java)
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        logger.error("A task in the scheduler encountered an exception", exception)
    }
    private val job = SupervisorJob(App.scope.coroutineContext.job)

    private val scope = CoroutineScope(App.scope.coroutineContext + job + exceptionHandler)
    private val running = AtomicBoolean(false)

    private val markerException = Exception("Marker exception for internal use, should never be printed to the logs")

    suspend fun start() {
        if (!running.compareAndSet(expectedValue = false, newValue = true)) {
            throw IllegalStateException("Scheduler is already running")
        }
        // all the task that we do in the scheduler have already run, so it does not make sense to run directly again
        logger.info("Scheduler started in ${TIME_BETWEEN_RUNS.humanReadable()}")
        delay(TIME_BETWEEN_RUNS)
        // switching to the scheduler scope
        try {
            val job = scope.launch {
                run()
            }
            job.join()
        } catch (e: Throwable) {
            logger.error("The scheduler has stopped unexpectedly. That should never happen, check logs for more details and then restart the application")
            throw e
        }
    }

    private suspend fun run() {
        var errorCount = 0

        // currently we some task, but all of them depend on the task bevor, so we can just run them all in a loop,
        // instead of launching them all at once, then independent waiting for the task bevor
        while (true) {
            try {
                logger.info("Running scheduled tasks")
                val duration = measureTime {
                    runTask()
                }
                errorCount = (errorCount--).coerceAtLeast(0)
                val toDelay = (TIME_BETWEEN_RUNS - duration).takeIf { it.isPositive() } ?: Duration.ZERO
                if (toDelay == Duration.ZERO) {
                    logger.info("Scheduled tasks took longer then ${TIME_BETWEEN_RUNS.humanReadable()} (${duration.humanReadable()}), still waiting for 1 minute")
                    delay(1.minutes)
                    continue
                } else {
                    val rerunAt = Clock.System.now() + toDelay
                    val local = rerunAt.toLocalDateTime(TimeZone.currentSystemDefault())
                    logger.info(
                        "Scheduled tasks finished in ${duration.humanReadable()}, waiting for ${toDelay.humanReadable()} (rerun at ${
                            SharedConstants.localDateTimeFormat.format(
                                local
                            )
                        })"
                    )
                }
                delay(toDelay)
                errorCount = (errorCount--).coerceAtLeast(0)
                // we start the next iteration immediately, since it is not possible that the error count has been increased if we get here
                continue
            } catch (e: Exception) {
                logger.error("An error occurred while running the scheduled tasks", e)
                errorCount += 3
            }
            if (errorCount > 20) {
                logger.error("Too many errors occurred. Stopping the scheduler")
                return
            }
            if (errorCount > 15) {
                logger.error("Too many errors occurred. Check the logs for more details")
                logger.error("Waiting for 10 minutes before trying again")
                delay(10.minutes)
            }
        }
    }

    private suspend fun runTask() {
        val originUpdated = try {
            val fetch = SharedValues.git.fetch()
            fetch.trackingRefUpdates.isNotEmpty()
        } catch (e: Exception) {
            logger.error("Failed to fetch updates for repo", e)
            // we cannot be sure if the origin has been updated or not, so we assume that it has not been updated, but we log the error
            false
        }
        val forks = GetForksService.getForks()
        val forksUpdated = when (forks.status) {
            GetForksService.Status.FAILED -> {
                if (!originUpdated) throw IllegalStateException("Failed to fetch forks, check logs for more details")
                // if origin has been updated, we cannot stop this iteration, because we need to index the new commits in main
                logger.error("Failed to fetch forks, check logs for more details")
                false
            }

            GetForksService.Status.SUCCESS -> true
            GetForksService.Status.NOT_MODIFIED -> {
                // perfect, if we get here, none of the forks have been updated since the last fetch
                false
            }
        }
        if (!originUpdated && !forksUpdated) {
            logger.info("No changes detected, skipping this iteration")
            return
        }
        try {
            UserService.updateUsers(GetForksInitTask.forks.map { it.user })
        } catch (e: Exception) {
            // bad if it happens, but we can still continue
            logger.error("Failed to update users while fetching forks", e)
        }

        if (forksUpdated) {
            // we only need to update the forks because the fetch command already updated the origin
            try {
                SyncForksService.syncForks()
            } catch (e: Exception) {
                logger.error("Unexpected exception while syncing forks", e)
            }
        }
        val newCommits = try {
            SyncCommitService.sync()
        } catch (e: Exception) {
            logger.error("Unexpected exception while syncing commits", e)
            // sadly if the sync fails, we need to stop the iteration, because the commits need to be in the db for later actions
            throw e
        }
        if (!newCommits) {
            // if there are no new commits, it could still be possible that the records have changed (due git fast-forward or any other reason)
            try {
                val dbRefs = DatabaseManager.transaction {
                    HeadTable.selectAll()
                        .map { Head(it[HeadTable.repoId], it[HeadTable.branch], it[HeadTable.commit]) }
                        .toList()
                }
                if (dbRefs.size != git.repository.refDatabase.refs.size) {
                    // fast path, if the ref's size has changed, we jump directly to the index task,
                    logger.info("The refs size has changed, starting the index task")
                    throw markerException
                }
                // slow path, maybe a head of a branch has been updated, or a branch was added and another removed,
                // so we check every head from the db with the real refs
                val headsById = dbRefs.groupBy { it.repoId }

                val originRefs = git.repository.refDatabase
                    .getRefsByPrefix("refs/heads/")

                for (ref in originRefs) {
                    val branch = ref.name.removePrefix("refs/heads/")
                    val head = headsById[SharedConstants.HACKCLUB_DNS_ID]?.find { it.branch == branch }
                    val tipHash = ref.objectId.name
                    if (head == null || tipHash != head.commit) {
                        // something has changed, leave as fast as possible
                        throw markerException
                    }
                }

                val remoteRefs = git.repository.refDatabase
                    .getRefsByPrefix("refs/remotes/fork/")


                for (ref in remoteRefs) {
                    val tipHash = ref.objectId.name
                    val path = ref.name.removePrefix("refs/remotes/fork/").substringAfter('/')
                    val forkId = path.substringBefore('/').toLongOrNull()
                    val branch = path.substringAfter('/')

                    if (forkId == null) {
//                        logger.warn("Illegal fork path $path, skipping")
                        continue
                    }
                    val head = headsById[forkId]?.find { it.branch == branch }

                    if (head == null || tipHash != head.commit) {
                        // something has changed, leave as fast as possible
                        throw markerException
                    }
                }

                logger.info("No changes detected, skipping this iteration")
                return
            } catch (e: Exception) {
                if (e !== markerException) {
                    logger.error("Failed to check if the origin has been updated", e)
                }
            }
        }
        runIndex()
    }

    // yeah, I simply copied it from DnsIndexTask, DRY comes later
    // todo: make it dry
    private suspend fun runIndex() {
        val existingRecords = DatabaseManager.transaction {
            RecordTable.selectAll().map {
                RecordKey(
                    it[RecordTable.host],
                    it[RecordTable.name],
                    it[RecordTable.type]
                ) to it[RecordTable.currentValue]
            }.toList().toMap()
        }

        val existingRecordKeys = existingRecords.keys.toSet()

        val existingForks = DatabaseManager.transaction {
            ForkProposalTable.select(
                ForkProposalTable.host,
                ForkProposalTable.name,
                ForkProposalTable.type,
                ForkProposalTable.repository,
                ForkProposalTable.branch,
                ForkProposalTable.currentValue
            ).map {
                ForkProposalKey(
                    RecordKey(
                        it[ForkProposalTable.host],
                        it[ForkProposalTable.name],
                        it[ForkProposalTable.type],
                    ),
                    it[ForkProposalTable.repository],
                    it[ForkProposalTable.branch],
                ) to it[ForkProposalTable.currentValue]
            }.toList().toMap()
        }

        val existingForkKeys = existingForks.keys.toSet()

//        // not the best way to check that, but yeah, it works
//        val firstRun = DatabaseManager.transaction { RecordTable.selectAll().empty() }

        val (index, duration) = measureTimedValue { DnsManager.index() }

        logger.info(
            "DNS index built in ${duration.humanReadable()}: " +
                    "${index.mainTimelines.size} records, " +
                    "${index.forkProposals.size} fork-proposals"
        )

        val newRecords = index.mainTimelines.values.filter {
            it.key !in existingRecordKeys
        }

        val changedRecords = index.mainTimelines.values.filter {
            val oldValue = existingRecords[it.key]
            oldValue != null && oldValue != it.current
        }

        val removedRecords = (existingRecordKeys - index.mainTimelines.keys)
            .mapNotNull { key -> existingRecords[key]?.let { RecordTimeline(key, it, RecordState.DELETED) } }

        val newForkProposals = index.forkProposals.values.filter { proposal ->
            val key = ForkProposalKey(proposal.key, proposal.repository, proposal.branch)
            key !in existingForkKeys
        }

        val changedForkProposals = index.forkProposals.values.filter { proposal ->
            val key = ForkProposalKey(proposal.key, proposal.repository, proposal.branch)
            val oldValue = existingForks[key]
            oldValue != null && oldValue != proposal.current
        }

        val closedForkProposals = index.forkProposals.values.filter { proposal ->
            val key = ForkProposalKey(proposal.key, proposal.repository, proposal.branch)
            val wasOpen = existingForkKeys.contains(key)
            wasOpen && proposal.state in setOf(ForkProposalState.MERGED, ForkProposalState.CLOSED)
        }

        val hasAnyChange =
            newRecords.isNotEmpty() ||
                    removedRecords.isNotEmpty() ||
                    changedRecords.isNotEmpty() ||
                    newForkProposals.isNotEmpty() ||
                    changedForkProposals.isNotEmpty() ||
                    closedForkProposals.isNotEmpty()

        if (hasAnyChange) {

            val allCommitIds = newRecords.mapNotNull { it.current?.commit ?: it.timeline.lastOrNull()?.commit } +
                    removedRecords.mapNotNull { it.timeline.lastOrNull()?.commit } +
                    changedRecords.mapNotNull { it.current?.commit ?: it.timeline.lastOrNull()?.commit } +
                    newForkProposals.mapNotNull { it.current?.commit ?: it.timeline.lastOrNull()?.commit } +
                    changedForkProposals.mapNotNull { it.current?.commit ?: it.timeline.lastOrNull()?.commit } +
                    closedForkProposals.mapNotNull { it.timeline.lastOrNull()?.commit }
                        .distinct()

            val authorUserAlias = UserTable.alias("author_user")
            val commiterUserAlias = UserTable.alias("commiter_user")

            val usersWithUnknownSlackId = try {
                DatabaseManager.transaction {
                    CommitTable
                        .join(
                            authorUserAlias,
                            JoinType.LEFT,
                            CommitTable.author,
                            authorUserAlias[UserTable.id]
                        )
                        .join(
                            commiterUserAlias,
                            JoinType.LEFT,
                            CommitTable.commiter,
                            commiterUserAlias[UserTable.id]
                        )
                        .select(
                            // author
                            authorUserAlias[UserTable.id],
                            authorUserAlias[UserTable.username],

                            // commiter
                            commiterUserAlias[UserTable.id],
                            commiterUserAlias[UserTable.username],
                        )
                        .where {
                            (CommitTable.id inList allCommitIds) and
                                    ((authorUserAlias[UserTable.slackIdState] eq SlackUserIdFoundState.UNKNOWN) or
                                            (commiterUserAlias[UserTable.slackIdState] eq SlackUserIdFoundState.UNKNOWN))
                        }
                        .mapNotNull { row ->
                            listOf(
                                row[authorUserAlias[UserTable.id]] to row[authorUserAlias[UserTable.username]],
                                row[commiterUserAlias[UserTable.id]] to row[commiterUserAlias[UserTable.username]],
                            )
                        }
                        .toList()
                        .flatten()
                        .distinct()
                }
            } catch (e: Exception) {
                logger.error("Failed to request all users in new/changed/removed records without slack id", e)
                emptyList()
            }

            if (usersWithUnknownSlackId.isNotEmpty()) {
                // if we have new commits contains author and/or commiter that have not been connected with a slack id yet
                // that means the FindMissingSlackUsersInitTask is still running
                // so we are prioritizing them to find their slack id faster because we want to send the notification with the user mention
                logger.info("Found ${usersWithUnknownSlackId.size} users associated with these new commits without slack id, prioritization them")

                var shouldStop = false

                val job = scope.launch {
                    for ((id, username) in usersWithUnknownSlackId) {
                        if (shouldStop) {
                            logger.info("Stopping the resolving of prioritized users, because the job has been stopped")
                            break
                        }
                        val userId = try {
                            withTimeout(10.seconds) {
                                // it should not take more than 10 seconds to find the slack id,
                                // if so, then most likely something has gone wrong
                                FindSlackUserId.find(username)
                            }
                        } catch (e: Exception) {
                            logger.warn("Failed to find slack id for prioritized user $username", e)
                            continue
                        }
                        if (userId == null) {
                            logger.warn("Failed to find slack id for prioritized user $username")
                            continue
                        }
                        try {
                            DatabaseManager.transaction {
                                UserTable.update({ UserTable.id eq id }) {
                                    it[UserTable.slackId] = userId
                                    it[UserTable.slackIdState] = SlackUserIdFoundState.FOUND
                                }
                            }
                        } catch (e: Exception) {
                            logger.warn("Failed to update slack id for prioritized user $username", e)
                            continue
                        }
                    }
                }
                // we are waiting a maximum of 1 minute for the job to finish,
                // if it takes too long, we just send the notification anyway,
                // but let the last iteration finish, so that the requests are not for nothing
                withTimeoutOrNull(1.minutes) {
                    job.join()
                }
                shouldStop = true
                if (job.isActive) {
                    logger.warn("Requesting the missing slack ids for the new commits took too long, sending the notification anyway")
                }
            }

            logger.info("Changes detected, sending Slack notification")
            SlackNotificationService.sendDnsChangeNotification(
                newRecords = newRecords,
                changedRecords = changedRecords,
                removedRecords = removedRecords,
                newForkProposals = newForkProposals,
                changedForkProposals = changedForkProposals,
                closedForkProposals = closedForkProposals,
            )
        } else {
            logger.info("No changes detected, skipping Slack notification")
        }

        if (index.mainTimelines.isNotEmpty()) {
            runCatching {
                val dur = measureTime {
                    DatabaseManager.transaction {
                        RecordTable.batchUpsert(
                            index.mainTimelines.values,
                            shouldReturnGeneratedValues = false
                        ) { record ->
                            this[RecordTable.host] = record.key.host
                            this[RecordTable.name] = record.key.name
                            this[RecordTable.type] = record.key.type
                            this[RecordTable.state] = record.state
                            this[RecordTable.currentValue] = record.current
                        }
                    }
                }
                logger.info("${index.mainTimelines.size} records saved in ${dur.humanReadable()}")
            }.onFailure { logger.error("Error saving records", it) }
        }

        val timelineEvents = index.mainTimelines.values.flattenMainTimelines()
        if (timelineEvents.isNotEmpty()) {
            runCatching {
                val dur = measureTime {
                    DatabaseManager.transaction {
                        RecordTimelineTable.batchUpsert(
                            timelineEvents,
                            shouldReturnGeneratedValues = false
                        ) { (key, event) ->
                            this[RecordTimelineTable.host] = key.host
                            this[RecordTimelineTable.name] = key.name
                            this[RecordTimelineTable.type] = key.type
                            this[RecordTimelineTable.timelineType] = event.type
                            this[RecordTimelineTable.oldValue] = event.oldVersion
                            this[RecordTimelineTable.newValue] = event.newVersion
                            this[RecordTimelineTable.commit] = event.commit
                            this[RecordTimelineTable.timestamp] = event.timestamp
                        }
                    }
                }
                logger.info("${timelineEvents.size} timeline events saved in ${dur.humanReadable()}")
            }.onFailure { logger.error("Error saving timeline events", it) }
        }

        if (index.forkProposals.isNotEmpty()) {
            runCatching {
                val dur = measureTime {
                    val chunked = index.forkProposals.values.chunked(5000)
                    for (chunk in chunked) {
                        DatabaseManager.transaction {
                            ForkProposalTable.batchUpsert(chunk, shouldReturnGeneratedValues = false) { proposal ->
                                this[ForkProposalTable.host] = proposal.key.host
                                this[ForkProposalTable.name] = proposal.key.name
                                this[ForkProposalTable.type] = proposal.key.type
                                this[ForkProposalTable.repository] = proposal.repository
                                this[ForkProposalTable.branch] = proposal.branch
                                this[ForkProposalTable.mergeBase] = proposal.mergeBase
                                this[ForkProposalTable.state] = proposal.state
                                this[ForkProposalTable.currentValue] = proposal.current
                                this[ForkProposalTable.baseValue] = proposal.baseVersion
                            }
                        }
                    }
                }
                logger.info("${index.forkProposals.size} fork proposals saved in ${dur.humanReadable()}")
            }.onFailure { logger.error("Error saving fork proposals", it) }
        }

        val proposalEvents = index.forkProposals.values.flattenProposalTimelines()
        if (proposalEvents.isNotEmpty()) {
            runCatching {
                val dur = measureTime {
                    // yeah, it might seams that this is unnecessary, but it could lead to an OutOfMemoryError (no joke, it happend)
                    val chunked = proposalEvents.chunked(10000)
                    var patchCount = 0
                    for (chunk in chunked) {
                        DatabaseManager.transaction {
                            ForkProposalTimelineTable.batchUpsert(
                                chunk,
                                shouldReturnGeneratedValues = false
                            ) { (key, event) ->
                                this[ForkProposalTimelineTable.host] = key.recordKey.host
                                this[ForkProposalTimelineTable.name] = key.recordKey.name
                                this[ForkProposalTimelineTable.type] = key.recordKey.type
                                this[ForkProposalTimelineTable.repository] = key.repository
                                this[ForkProposalTimelineTable.branch] = key.branch
                                this[ForkProposalTimelineTable.eventType] = event.type
                                this[ForkProposalTimelineTable.oldValue] = event.oldVersion
                                this[ForkProposalTimelineTable.newValue] = event.newVersion
                                this[ForkProposalTimelineTable.commit] = event.commit
                                this[ForkProposalTimelineTable.timestamp] = event.timestamp
                            }
                            logger.info("Saved ${++patchCount} of ${chunked.size} chunks of proposal events")
                        }
                    }
                }
                logger.info("${proposalEvents.size} proposal events saved in ${dur.humanReadable()}")
            }.onFailure { logger.error("Error saving proposal timelines", it) }
        }
    }

    private fun Collection<RecordTimeline>.flattenMainTimelines(): List<Pair<RecordKey, TimelineEvent>> =
        flatMap { rt -> rt.timeline.map { rt.key to it } }

    private fun Collection<ForkProposal>.flattenProposalTimelines(): List<Pair<ForkProposalKey, ForkProposalEvent>> =
        flatMap { p ->
            val key = ForkProposalKey(p.key, p.repository, p.branch)
            p.timeline.map { key to it }
        }
}