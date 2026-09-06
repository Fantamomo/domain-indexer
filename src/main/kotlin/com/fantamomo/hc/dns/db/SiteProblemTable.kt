package com.fantamomo.hc.dns.db

import com.fantamomo.hc.dns.model.SiteProblemSeverity
import com.fantamomo.hc.dns.model.SiteProblemType
import com.fantamomo.hc.dns.model.dns.RecordType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

object SiteProblemTable : Table("site_problems") {
    val site = varchar("site", 255)
    val itemId = varchar("item_id", 64)
    val problem = enumerationByName<SiteProblemType>("problem", 64)
    val severity = enumerationByName<SiteProblemSeverity>("severity", 32)
    val recordType = enumerationByName<RecordType>("record_type", 16)
    val recordTarget = varchar("record_target", 255)
    val endpoint = varchar("endpoint", 512)
    val details = text("details")
    val remoteAddress = varchar("remote_address", 64).nullable()
    val exception = text("exception").nullable()
    val techFacts = text("tech_facts").nullable()
    val firstOccurred = timestamp("first_occurred")
    val lastOccurred = timestamp("last_occurred")

    init {
        index(true, itemId)
    }

    override val primaryKey = PrimaryKey(site)
}
