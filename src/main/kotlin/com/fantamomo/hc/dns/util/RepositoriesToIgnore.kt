package com.fantamomo.hc.dns.util

import com.fantamomo.hc.dns.data.Config

object RepositoriesToIgnore {

    private val ignoredIds: Set<Long> = Config.IDS_TO_IGNORE
        .split(",")
        .mapNotNull { it.trim().toLongOrNull() }
        .toSet()

    fun isIgnored(id: Long): Boolean = ignoredIds.contains(id)

    fun canIndex(id: Long): Boolean = !isIgnored(id)
}