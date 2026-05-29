package com.fantamomo.hc.dns.db

import org.jetbrains.exposed.v1.core.Table

object CommitTable : Table("commits") {
    val id = char("commit", 40)
    val message = text("message")
    val author = reference("author", UserTable.id).nullable()
    val commiter = reference("commiter", UserTable.id).nullable()
    val parentsCount = integer("parents_count")
    val createdAt = long("created_at")
    val commitedAt = long("commited_at")

    override val primaryKey = PrimaryKey(id)
}