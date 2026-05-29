package com.fantamomo.hc.dns.model.dns

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class RecordVersion(
    val value: String,
    val ttl: Int?,
    val commit: String,
    val repository: String,
    val branch: String,
    val timestamp: Instant
)