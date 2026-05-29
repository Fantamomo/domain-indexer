package com.fantamomo.hc.dns.model.dns

data class RecordTimeline(
    val key: RecordKey,
    var current: RecordVersion?,
    var state: RecordState,
    val timeline: MutableList<TimelineEvent>
)