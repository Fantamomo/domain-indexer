package com.fantamomo.hc.dns.model.dns

import kotlin.time.Instant

data class TimelineEvent(
    val type: TimelineEventType,
    val oldVersion: RecordVersion?,
    val newVersion: RecordVersion?,
    val commit: String,
    val timestamp: Instant
)