package com.fantamomo.hc.dns.util

import com.fantamomo.hc.dns.model.dns.*
import kotlin.time.Instant

object DnsIndexer {

    data class ForkCommit(
        val hash: String,
        val timestamp: Instant,
        val state: Map<RecordKey, ParsedRecord>
    )

    fun processMainCommit(
        commit: String,
        timestamp: Instant,
        previousState: Map<RecordKey, ParsedRecord>,
        currentState: Map<RecordKey, ParsedRecord>,
        mainTimelines: MutableMap<RecordKey, RecordTimeline>
    ) {
        val allKeys = (previousState.keys + currentState.keys).toSet()

        for (key in allKeys) {
            val old = previousState[key]
            val new = currentState[key]

            when {
                old == null && new != null ->
                    createMainRecord(commit, timestamp, key, new, mainTimelines)

                old != null && new == null ->
                    deleteMainRecord(commit, timestamp, key, mainTimelines)

                old != null && new != null && (old.value != new.value || old.ttl != new.ttl) ->
                    updateMainRecord(commit, timestamp, key, new, mainTimelines)
            }
        }
    }

    private fun createMainRecord(
        commit: String,
        timestamp: Instant,
        key: RecordKey,
        record: ParsedRecord,
        mainTimelines: MutableMap<RecordKey, RecordTimeline>
    ) {
        val version = record.toVersion(commit, "hackclub/dns", "main", timestamp)
        mainTimelines[key] = RecordTimeline(
            key = key,
            current = version,
            state = RecordState.ACTIVE,
            timeline = mutableListOf(
                TimelineEvent(
                    type = TimelineEventType.CREATED,
                    oldVersion = null,
                    newVersion = version,
                    commit = commit,
                    timestamp = timestamp
                )
            )
        )
    }

    private fun updateMainRecord(
        commit: String,
        timestamp: Instant,
        key: RecordKey,
        record: ParsedRecord,
        mainTimelines: MutableMap<RecordKey, RecordTimeline>
    ) {
        val timeline = mainTimelines[key] ?: return
        val oldVersion = timeline.current
        val newVersion = record.toVersion(commit, "hackclub/dns", "main", timestamp)
        timeline.current = newVersion
        timeline.timeline += TimelineEvent(
            type = TimelineEventType.UPDATED,
            oldVersion = oldVersion,
            newVersion = newVersion,
            commit = commit,
            timestamp = timestamp
        )
    }

    private fun deleteMainRecord(
        commit: String,
        timestamp: Instant,
        key: RecordKey,
        mainTimelines: MutableMap<RecordKey, RecordTimeline>
    ) {
        val timeline = mainTimelines[key] ?: return
        timeline.state = RecordState.DELETED
        timeline.timeline += TimelineEvent(
            type = TimelineEventType.DELETED,
            oldVersion = timeline.current,
            newVersion = null,
            commit = commit,
            timestamp = timestamp
        )
        timeline.current = null
    }

    fun processForkBranch(
        repository: String,
        branch: String,
        mergeBase: String,
        mergeBaseState: Map<RecordKey, ParsedRecord>,
        mergeBaseTimestamp: Instant,
        forkCommits: List<ForkCommit>,
        forkProposals: MutableMap<ForkProposalKey, ForkProposal>
    ) {
        val tipState = forkCommits.last().state

        val proposalKeys = (mergeBaseState.keys + tipState.keys).toSet().filter { key ->
            val inBase = mergeBaseState[key]
            val inTip = tipState[key]
            inBase?.value != inTip?.value || inBase?.ttl != inTip?.ttl
        }.toSet()

        if (proposalKeys.isEmpty()) return

        var previousState: Map<RecordKey, ParsedRecord> = mergeBaseState

        for (forkCommit in forkCommits) {
            val currentState = forkCommit.state

            for (key in proposalKeys) {
                val old = previousState[key]
                val new = currentState[key]

                if (old?.value == new?.value && old?.ttl == new?.ttl) continue

                val proposalKey = ForkProposalKey(key, repository, branch)
                val inBase = mergeBaseState[key]

                val newState = when {
                    new == null -> ForkProposalState.DRAFT_DELETED
                    inBase == null -> ForkProposalState.DRAFT_ADDED
                    else -> ForkProposalState.DRAFT_MODIFIED
                }

                val baseVersion = inBase?.toVersion(mergeBase, "hackclub/dns", "main", mergeBaseTimestamp)
                val newVersion = new?.toVersion(forkCommit.hash, repository, branch, forkCommit.timestamp)

                val existing = forkProposals[proposalKey]
                if (existing == null) {
                    forkProposals[proposalKey] = ForkProposal(
                        key = key,
                        repository = repository,
                        branch = branch,
                        mergeBase = mergeBase,
                        state = newState,
                        current = newVersion,
                        baseVersion = baseVersion,
                        timeline = mutableListOf(
                            ForkProposalEvent(
                                type = ForkProposalEventType.OPENED,
                                oldVersion = null,
                                newVersion = newVersion,
                                commit = forkCommit.hash,
                                timestamp = forkCommit.timestamp
                            )
                        )
                    )
                } else {
                    val oldVersion = existing.current
                    existing.state = newState
                    existing.current = newVersion
                    existing.timeline += ForkProposalEvent(
                        type = ForkProposalEventType.UPDATED,
                        oldVersion = oldVersion,
                        newVersion = newVersion,
                        commit = forkCommit.hash,
                        timestamp = forkCommit.timestamp
                    )
                }
            }

            previousState = currentState
        }
    }

    private fun ParsedRecord.toVersion(
        commit: String,
        repository: String,
        branch: String,
        timestamp: Instant
    ) = RecordVersion(
        value = value,
        ttl = ttl,
        commit = commit,
        repository = repository,
        branch = branch,
        timestamp = timestamp
    )
}