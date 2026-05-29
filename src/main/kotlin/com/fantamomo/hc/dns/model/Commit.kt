package com.fantamomo.hc.dns.model

import kotlin.time.Instant

data class Commit(
    val id: String,
    val message: String,
    val author: Long?,
    val commiter: Long?,
    val parents: List<String>,
    val createdAt: Instant,
    val commitedAt: Instant
)