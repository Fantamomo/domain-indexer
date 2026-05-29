package com.fantamomo.hc.dns.db

import org.jetbrains.exposed.v1.core.Table

object CommitParentsTable : Table("commit_parents") {
    val commit = reference("commit", CommitTable.id)
    val parent = reference("parent", CommitTable.id)

    override val primaryKey = PrimaryKey(commit, parent)
}