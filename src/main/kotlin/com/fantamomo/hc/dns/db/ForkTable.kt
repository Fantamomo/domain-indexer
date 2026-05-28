package com.fantamomo.hc.dns.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

object ForkTable : Table("forks") {
    val id = long("id")
    val userId = reference("user_id", UserTable.id)
    val name = varchar("name", 255)
    val createdAt = timestamp("created_at")
    val lastUpdatedAt = timestamp("last_updated_at")
    val pushedAt = timestamp("pushed_at")
    val deleted = bool("delete").default(false)

    override val primaryKey = PrimaryKey(id)
}