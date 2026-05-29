package com.fantamomo.hc.dns.model.dns

import kotlin.time.Instant

data class ForkProposal(
    val key: RecordKey,
    val repository: String,
    val branch: String,
    val mergeBase: String,
    var state: ForkProposalState,
    var current: RecordVersion?,
    val baseVersion: RecordVersion?,

    val timeline: MutableList<ForkProposalEvent> = mutableListOf()
)

data class ForkProposalEvent(
    val type: ForkProposalEventType,
    val oldVersion: RecordVersion?,
    val newVersion: RecordVersion?,
    val commit: String,
    val timestamp: Instant
)