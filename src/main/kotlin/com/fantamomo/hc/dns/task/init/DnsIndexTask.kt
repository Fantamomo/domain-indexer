package com.fantamomo.hc.dns.task.init

import com.fantamomo.hc.dns.db.ForkProposalTable
import com.fantamomo.hc.dns.db.ForkProposalTimelineTable
import com.fantamomo.hc.dns.db.RecordTable
import com.fantamomo.hc.dns.db.RecordTimelineTable
import com.fantamomo.hc.dns.manager.DatabaseManager
import com.fantamomo.hc.dns.manager.DnsManager
import com.fantamomo.hc.dns.model.dns.*
import com.fantamomo.hc.dns.task.InitTask
import com.fantamomo.hc.dns.util.humanReadable
import org.jetbrains.exposed.v1.r2dbc.batchUpsert
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

object DnsIndexTask : InitTask(
    "dns-index",
    SyncCommitsTask,
    shortDescription = "Builds the DNS index",
    longDescription = "Builds the DNS index by parsing the DNS records in the git repository and then stores them in the db"
) {
    override suspend fun run() {
        val (index, duration) = measureTimedValue { DnsManager.index() }
        logger.info(
            "DNS index built in ${duration.humanReadable()}: " +
                    "${index.mainTimelines.size} main-records, " +
                    "${index.forkProposals.size} fork-proposals"
        )

        if (index.mainTimelines.isNotEmpty()) {
            runCatching {
                val dur = measureTime {
                    DatabaseManager.transaction {
                        RecordTable.batchUpsert(index.mainTimelines.values) { record ->
                            this[RecordTable.host] = record.key.host
                            this[RecordTable.name] = record.key.name
                            this[RecordTable.type] = record.key.type
                            this[RecordTable.state] = record.state
                            this[RecordTable.currentValue] = record.current
                        }
                    }
                }
                logger.info("${index.mainTimelines.size} records saved in ${dur.humanReadable()}")
            }.onFailure { logger.error("Error while saving records", it) }
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
                logger.info("${timelineEvents.size} Timeline-Events saved in ${dur.humanReadable()}")
            }.onFailure { logger.error("Error while saving Timeline-Events", it) }
        }

        if (index.forkProposals.isNotEmpty()) {
            runCatching {
                val dur = measureTime {
                    DatabaseManager.transaction {
                        ForkProposalTable.batchUpsert(
                            index.forkProposals.values,
                            shouldReturnGeneratedValues = false
                        ) { proposal ->
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
                logger.info("${index.forkProposals.size} fork-proposals saved in ${dur.humanReadable()}")
            }.onFailure { logger.error("Error while saving Fork-Proposals", it) }
        }

        val proposalEvents = index.forkProposals.values.flattenProposalTimelines()
        if (proposalEvents.isNotEmpty()) {
            runCatching {
                val dur = measureTime {
                    DatabaseManager.transaction {
                        ForkProposalTimelineTable.batchUpsert(proposalEvents, shouldReturnGeneratedValues = false) { (key, event) ->
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
                logger.info("${proposalEvents.size} Proposal-Timeline-Events saved in ${dur.humanReadable()}")
            }.onFailure { logger.error("Error while saving Proposal-Timelines", it) }
        }
    }

    private fun Collection<RecordTimeline>.flattenMainTimelines(): List<Pair<RecordKey, TimelineEvent>> =
        flatMap { rt -> rt.timeline.map { rt.key to it } }

    private fun Collection<ForkProposal>.flattenProposalTimelines(): List<Pair<ForkProposalKey, ForkProposalEvent>> =
        flatMap { proposal ->
            val pKey = ForkProposalKey(proposal.key, proposal.repository, proposal.branch)
            proposal.timeline.map { pKey to it }
        }
}