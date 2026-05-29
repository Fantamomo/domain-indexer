package com.fantamomo.hc.dns.task.init

import com.fantamomo.hc.dns.db.ForkProposalTable
import com.fantamomo.hc.dns.db.ForkProposalTimelineTable
import com.fantamomo.hc.dns.db.RecordTable
import com.fantamomo.hc.dns.db.RecordTimelineTable
import com.fantamomo.hc.dns.manager.DatabaseManager
import com.fantamomo.hc.dns.manager.DnsManager
import com.fantamomo.hc.dns.model.dns.*
import com.fantamomo.hc.dns.task.InitTask
import com.fantamomo.hc.dns.util.SlackNotificationService
import com.fantamomo.hc.dns.util.humanReadable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.r2dbc.batchUpsert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

object DnsIndexTask : InitTask(
    "dns-index",
    SyncCommitsTask,
    shortDescription = "Builds the DNS index",
    longDescription = "Builds the DNS index by parsing DNS records and storing them in the DB"
) {

    override suspend fun run() {

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

        val (index, duration) = measureTimedValue { DnsManager.index() }

        logger.info(
            "DNS index built in ${duration.humanReadable()}: " +
                    "${index.mainTimelines.size} records, " +
                    "${index.forkProposals.size} fork-proposals"
        )

        val newRecords = index.mainTimelines.values.filter {
            it.key !in existingRecordKeys &&
                    it.timeline.any { e -> e.type == TimelineEventType.CREATED }
        }

        val changedRecords = index.mainTimelines.values.filter {
            val oldValue = existingRecords[it.key]
            oldValue != null && oldValue != it.current
        }

        val newForkProposals = index.forkProposals.values.filter {
            val key = ForkProposalKey(it.key, it.repository, it.branch)
            key !in existingForkKeys &&
                    it.timeline.any { e -> e.type == ForkProposalEventType.OPENED }
        }

        val changedForkProposals = index.forkProposals.values.filter {
            val key = ForkProposalKey(it.key, it.repository, it.branch)
            val old = existingForks[key]
            old != null && old != it.current
        }

        val closedForkProposals = index.forkProposals.values.filter {
            it.timeline.any { e -> e.type == ForkProposalEventType.CLOSED }
        }

        val hasAnyChange =
            newRecords.isNotEmpty() ||
                    changedRecords.isNotEmpty() ||
//                    removedRecords.isNotEmpty() ||
                    newForkProposals.isNotEmpty() ||
                    changedForkProposals.isNotEmpty() ||
                    closedForkProposals.isNotEmpty()

        if (hasAnyChange) {
            SlackNotificationService.sendDnsChangeNotification(
                newRecords = newRecords,
                changedRecords = changedRecords,
                removedRecords = emptyList(), // removedRecords,
                newForkProposals = newForkProposals,
                changedForkProposals = changedForkProposals,
                closedForkProposals = closedForkProposals,
            )
        }

        if (index.mainTimelines.isNotEmpty()) {
            runCatching {
                val dur = measureTime {
                    DatabaseManager.transaction {
                        RecordTable.batchUpsert(index.mainTimelines.values, shouldReturnGeneratedValues = false) { record ->
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
                    DatabaseManager.transaction {
                        ForkProposalTable.batchUpsert(index.forkProposals.values, shouldReturnGeneratedValues = false) { proposal ->
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
                logger.info("${index.forkProposals.size} fork proposals saved in ${dur.humanReadable()}")
            }.onFailure { logger.error("Error saving fork proposals", it) }
        }

        val proposalEvents = index.forkProposals.values.flattenProposalTimelines()
        if (proposalEvents.isNotEmpty()) {
            runCatching {
                val dur = measureTime {
                    DatabaseManager.transaction {
                        ForkProposalTimelineTable.batchUpsert(
                            proposalEvents,
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