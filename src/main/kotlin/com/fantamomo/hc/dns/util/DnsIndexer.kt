package com.fantamomo.hc.dns.util

import com.fantamomo.hc.dns.model.dns.*
import kotlin.time.Instant

object DnsIndexer {

    fun processCommit(
        repository: String,
        branch: String,
        commit: String,
        timestamp: Instant,

        oldRecords: List<ParsedRecord>,
        newRecords: List<ParsedRecord>,

        timelines: MutableMap<RecordKey, RecordTimeline>
    ) {

        val oldMap = oldRecords.associateBy {
            RecordKey(it.host, it.name, it.type)
        }

        val newMap = newRecords.associateBy {
            RecordKey(it.host, it.name, it.type)
        }

        val allKeys = mutableSetOf<RecordKey>()

        allKeys += oldMap.keys
        allKeys += newMap.keys

        for (key in allKeys) {

            val old = oldMap[key]
            val new = newMap[key]

            when {

                old == null && new != null -> {
                    createRecord(
                        repository,
                        branch,
                        commit,
                        timestamp,
                        key,
                        new,
                        timelines
                    )
                }

                old != null && new == null -> {
                    deleteRecord(
                        commit,
                        timestamp,
                        key,
                        timelines
                    )
                }

                old != null && new != null -> {

                    if (
                        old.value != new.value ||
                        old.ttl != new.ttl
                    ) {
                        updateRecord(
                            repository,
                            branch,
                            commit,
                            timestamp,
                            key,
                            old,
                            new,
                            timelines
                        )
                    }
                }
            }
        }
    }

    private fun createRecord(
        repository: String,
        branch: String,
        commit: String,
        timestamp: Instant,
        key: RecordKey,
        record: ParsedRecord,
        timelines: MutableMap<RecordKey, RecordTimeline>
    ) {

        val state =
            if (repository == "hackclub/dns" && branch == "main") {
                RecordState.ACTIVE
            } else {
                RecordState.DRAFT
            }

        val version = RecordVersion(
            value = record.value,
            ttl = record.ttl,
            commit = commit,
            repository = repository,
            branch = branch,
            timestamp = timestamp
        )

        val timeline = RecordTimeline(
            key = key,
            current = version,
            state = state,
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

        timelines[key] = timeline
    }

    private fun updateRecord(
        repository: String,
        branch: String,
        commit: String,
        timestamp: Instant,
        key: RecordKey,
        old: ParsedRecord,
        new: ParsedRecord,
        timelines: MutableMap<RecordKey, RecordTimeline>
    ) {

        val timeline = timelines[key] ?: return

        val oldVersion = timeline.current

        val newVersion = RecordVersion(
            value = new.value,
            ttl = new.ttl,
            commit = commit,
            repository = repository,
            branch = branch,
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

    private fun deleteRecord(
        commit: String,
        timestamp: Instant,
        key: RecordKey,
        timelines: MutableMap<RecordKey, RecordTimeline>
    ) {

        val timeline = timelines[key] ?: return

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
}