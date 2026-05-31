package com.fantamomo.hc.dns.db

import org.jetbrains.exposed.v1.core.Table

object HeadTable : Table("heads") {
    val repoId = long("repo_id").nullable()
    val branch = varchar("branch", 255)
    val commit = reference("commit", CommitTable.id)

    override val primaryKey = PrimaryKey(repoId, branch)
}