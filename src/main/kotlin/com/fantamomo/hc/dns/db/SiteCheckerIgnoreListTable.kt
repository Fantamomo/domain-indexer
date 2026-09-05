package com.fantamomo.hc.dns.db

import org.jetbrains.exposed.v1.core.Table

object SiteCheckerIgnoreListTable : Table("site_checker_ignore_list") {
    val host = varchar("host", 24)
    val name = varchar("name", 255)
    // just for saving the reason, does not effect the software in any way (actually, it is never referenced)
    val reason = varchar("reason", 255)

    override val primaryKey = PrimaryKey(host, name)
}