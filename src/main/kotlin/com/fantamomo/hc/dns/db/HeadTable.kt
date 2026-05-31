package com.fantamomo.hc.dns.db

import org.jetbrains.exposed.v1.core.Table

object HeadTable : Table("heads") {
    val repoId = long("repo_id") // i dont know why but .nullable() does not work, in the db it is not nullable
    val branch = varchar("branch", 255)
    val commit = reference("commit", CommitTable.id)

    override val primaryKey = PrimaryKey(repoId, branch)
}