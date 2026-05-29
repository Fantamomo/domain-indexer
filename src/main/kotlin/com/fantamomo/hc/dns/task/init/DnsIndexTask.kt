package com.fantamomo.hc.dns.task.init

import com.fantamomo.hc.dns.db.RecordTable
import com.fantamomo.hc.dns.db.RecordTimelineTable
import com.fantamomo.hc.dns.manager.DatabaseManager
import com.fantamomo.hc.dns.manager.DnsManager
import com.fantamomo.hc.dns.model.dns.RecordKey
import com.fantamomo.hc.dns.model.dns.RecordTimeline
import com.fantamomo.hc.dns.model.dns.TimelineEvent
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
        val (index, duration) = measureTimedValue {
            DnsManager.index()
        }
        logger.info("DNS index with ${index.size} entries built in ${duration.humanReadable()}")
        if (index.isEmpty()) return
        val values = index.values
        try {
            val duration = measureTime {
                DatabaseManager.transaction {
                    RecordTable.batchUpsert(values) { record ->
                        this[RecordTable.host] = record.key.host
                        this[RecordTable.name] = record.key.name
                        this[RecordTable.type] = record.key.type
                        this[RecordTable.state] = record.state
                        this[RecordTable.currentValue] = record.current
                    }
                }
            }
            logger.info("Inserted ${values.size} records in ${duration.humanReadable()}")
        } catch (e: Exception) {
            logger.error("Failed to insert records", e)
            return
        }
        try {
            val timelineEvents = values.flattenTimelineEvents()
            val duration = measureTime {
                DatabaseManager.transaction {
                    RecordTimelineTable.batchUpsert(timelineEvents) { (key, record) ->
                        this[RecordTimelineTable.host] = key.host
                        this[RecordTimelineTable.name] = key.name
                        this[RecordTimelineTable.type] = key.type
                        this[RecordTimelineTable.timelineType] = record.type
                        this[RecordTimelineTable.oldValue] = record.oldVersion
                        this[RecordTimelineTable.newValue] = record.newVersion
                        this[RecordTimelineTable.commit] = record.commit
                        this[RecordTimelineTable.timestamp] = record.timestamp
                    }
                }
            }
            logger.info("Inserted ${timelineEvents.size} record timelines in ${duration.humanReadable()}")
        } catch (e: Exception) {
            logger.error("Failed to insert record timelines", e)
        }
    }

    private fun Collection<RecordTimeline>.flattenTimelineEvents(): List<Pair<RecordKey, TimelineEvent>> =
        flatMap { recordTimeline ->
            recordTimeline.timeline.map { event ->
                recordTimeline.key to event
            }
        }
}