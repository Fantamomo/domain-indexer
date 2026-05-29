package com.fantamomo.hc.dns.db

import com.fantamomo.hc.dns.data.SharedConstants
import com.fantamomo.hc.dns.model.dns.ForkProposalEventType
import com.fantamomo.hc.dns.model.dns.RecordType
import com.fantamomo.hc.dns.model.dns.RecordVersion
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.json.json

object ForkProposalTimelineTable : Table("fork_proposal_timelines") {
    val host = varchar("host", 24)
    val name = varchar("name", 255)
    val type = enumerationByName<RecordType>("type", 10)
    val repository = varchar("repository", 255)
    val branch = varchar("branch", 255)
    val eventType = enumerationByName<ForkProposalEventType>("event_type", 10)
    val oldValue = json<RecordVersion>("old_value", SharedConstants.jsonSQL).nullable()
    val newValue = json<RecordVersion>("new_value", SharedConstants.jsonSQL).nullable()
    val commit = reference("commit", CommitTable.id)
    val timestamp = timestamp("timestamp")

    override val primaryKey = PrimaryKey(host, name, type, repository, branch, commit)
}