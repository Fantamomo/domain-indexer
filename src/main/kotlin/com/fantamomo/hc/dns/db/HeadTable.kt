package com.fantamomo.hc.dns.db

import org.jetbrains.exposed.v1.core.Table

object HeadTable : Table("heads") {
    val id = reference("fork_id", ForkTable.id)
    val branch = varchar("branch", 255)
    val commit = char("commit", 40)

    override val primaryKey = PrimaryKey(id, branch)
}