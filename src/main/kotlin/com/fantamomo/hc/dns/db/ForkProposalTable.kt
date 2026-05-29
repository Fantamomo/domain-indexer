package com.fantamomo.hc.dns.db

import com.fantamomo.hc.dns.data.SharedConstants
import com.fantamomo.hc.dns.model.dns.ForkProposalState
import com.fantamomo.hc.dns.model.dns.RecordType
import com.fantamomo.hc.dns.model.dns.RecordVersion
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.json.json

object ForkProposalTable : Table("fork_proposals") {
    val host = varchar("host", 24)
    val name = varchar("name", 255)
    val type = enumerationByName<RecordType>("type", 10)
    val repository = varchar("repository", 255)
    val branch = varchar("branch", 255)
    val mergeBase = varchar("merge_base", 64)
    val state = enumerationByName<ForkProposalState>("state", 20)

    val currentValue = json<RecordVersion>("current_value", SharedConstants.jsonSQL).nullable()

    val baseValue = json<RecordVersion>("base_value", SharedConstants.jsonSQL).nullable()

    override val primaryKey = PrimaryKey(host, name, type, repository, branch)
}