package com.fantamomo.hc.dns.util

import com.fantamomo.hc.dns.model.dns.*
import kotlin.time.Instant

object DnsIndexer {

    fun processMainCommit(
        commit: String,
        timestamp: Instant,
        oldRecords: List<ParsedRecord>,
        newRecords: List<ParsedRecord>,
        mainTimelines: MutableMap<RecordKey, RecordTimeline>
    ) {
        val oldMap = oldRecords.associateBy { RecordKey(it.host, it.name, it.type) }
        val newMap = newRecords.associateBy { RecordKey(it.host, it.name, it.type) }

        for (key in oldMap.keys + newMap.keys) {
            val old = oldMap[key]
            val new = newMap[key]

            when {
                old == null && new != null -> createMainRecord(
                    commit, timestamp, key, new, mainTimelines
                )
                old != null && new == null -> deleteMainRecord(
                    commit, timestamp, key, mainTimelines
                )
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
        val version = RecordVersion(
            value = record.value,
            ttl = record.ttl,
            commit = commit,
            repository = "hackclub/dns",
            branch = "main",
            timestamp = timestamp
        )
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
        val newVersion = RecordVersion(
            value = record.value,
            ttl = record.ttl,
            commit = commit,
            repository = "hackclub/dns",
            branch = "main",
            timestamp = timestamp
        )
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

    fun processForkDiff(
        repository: String,
        branch: String,
        tipCommit: String,
        tipTimestamp: Instant,
        mergeBase: String,
        mergeBaseTimestamp: Instant,
        currentMainRecords: Map<RecordKey, ParsedRecord>,
        forkRecords: Map<RecordKey, ParsedRecord>,
        forkProposals: MutableMap<ForkProposalKey, ForkProposal>
    ) {
        for (key in forkRecords.keys) {
            val inMain = currentMainRecords[key]
            val inFork = forkRecords[key]!!

            val proposalKey = ForkProposalKey(key, repository, branch)

            when {
                inMain == null -> {
                    upsertForkProposal(
                        proposalKey = proposalKey,
                        mergeBase = mergeBase,
                        newState = ForkProposalState.DRAFT_ADDED,
                        baseVersion = null,
                        newVersion = inFork.toVersion(tipCommit, repository, branch, tipTimestamp),
                        eventType = ForkProposalEventType.OPENED,
                        commit = tipCommit,
                        timestamp = tipTimestamp,
                        forkProposals = forkProposals
                    )
                }

                inMain.value != inFork.value || inMain.ttl != inFork.ttl -> {
                    upsertForkProposal(
                        proposalKey = proposalKey,
                        mergeBase = mergeBase,
                        newState = ForkProposalState.DRAFT_MODIFIED,
                        baseVersion = inMain.toVersion(mergeBase, "hackclub/dns", "main", mergeBaseTimestamp),
                        newVersion = inFork.toVersion(tipCommit, repository, branch, tipTimestamp),
                        eventType = ForkProposalEventType.OPENED,
                        commit = tipCommit,
                        timestamp = tipTimestamp,
                        forkProposals = forkProposals
                    )
                }
            }
        }

        for (key in currentMainRecords.keys) {
            if (key in forkRecords) continue

            val inMain = currentMainRecords[key]!!
            val proposalKey = ForkProposalKey(key, repository, branch)

            upsertForkProposal(
                proposalKey = proposalKey,
                mergeBase = mergeBase,
                newState = ForkProposalState.DRAFT_DELETED,
                baseVersion = inMain.toVersion(mergeBase, "hackclub/dns", "main", mergeBaseTimestamp),
                newVersion = null,
                eventType = ForkProposalEventType.OPENED,
                commit = tipCommit,
                timestamp = tipTimestamp,
                forkProposals = forkProposals
            )
        }
    }

    private fun upsertForkProposal(
        proposalKey: ForkProposalKey,
        mergeBase: String,
        newState: ForkProposalState,
        baseVersion: RecordVersion?,
        newVersion: RecordVersion?,
        eventType: ForkProposalEventType,
        commit: String,
        timestamp: Instant,
        forkProposals: MutableMap<ForkProposalKey, ForkProposal>
    ) {
        val existing = forkProposals[proposalKey]

        if (existing == null) {
            forkProposals[proposalKey] = ForkProposal(
                key = proposalKey.recordKey,
                repository = proposalKey.repository,
                branch = proposalKey.branch,
                mergeBase = mergeBase,
                state = newState,
                current = newVersion,
                baseVersion = baseVersion,
                timeline = mutableListOf(
                    ForkProposalEvent(
                        type = eventType,
                        oldVersion = null,
                        newVersion = newVersion,
                        commit = commit,
                        timestamp = timestamp
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
                commit = commit,
                timestamp = timestamp
            )
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