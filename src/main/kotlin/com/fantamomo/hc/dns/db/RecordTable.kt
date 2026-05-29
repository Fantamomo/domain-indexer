package com.fantamomo.hc.dns.db

import com.fantamomo.hc.dns.data.SharedConstants
import com.fantamomo.hc.dns.model.dns.RecordState
import com.fantamomo.hc.dns.model.dns.RecordType
import com.fantamomo.hc.dns.model.dns.RecordVersion
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.json.json

object RecordTable : Table("records") {
    val host = varchar("host", 24)
    val name = varchar("name", 255)
    val type = enumerationByName<RecordType>("type", 10)
    val state = enumerationByName<RecordState>("state", 10)
    val currentValue = json<RecordVersion>("current_value", SharedConstants.jsonSQL).nullable()

    override val primaryKey = PrimaryKey(host, name, type)
}