package com.fantamomo.hc.dns.model

import kotlin.time.Instant

class Fork(
    val id: Long,
    val userId: Long,
    val name: String,
    val createdAt: Instant,
    val lastUpdatedAt: Instant,
    val pushedAt: Instant,
    val deleted: Boolean = false
)