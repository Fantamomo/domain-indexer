package com.fantamomo.hc.dns.model.dns

data class ForkProposalKey(
    val recordKey: RecordKey,
    val repository: String,
    val branch: String
)