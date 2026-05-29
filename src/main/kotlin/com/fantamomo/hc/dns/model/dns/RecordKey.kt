package com.fantamomo.hc.dns.model.dns

data class RecordKey(
    val host: String,
    val name: String,
    val type: RecordType
)