package com.fantamomo.hc.dns.db

import org.jetbrains.exposed.v1.core.Table

object UserTable : Table("users") {
    val id = long("id")
    val username = varchar("username", 255)
    val email = varchar("email", 255).nullable()
    val type = varchar("type", 255)
    val deleted = bool("delete").default(false)

    override val primaryKey = PrimaryKey(id)
}