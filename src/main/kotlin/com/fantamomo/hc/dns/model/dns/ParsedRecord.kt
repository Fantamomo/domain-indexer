package com.fantamomo.hc.dns.model.dns

data class ParsedRecord(
    val host: String,
    val name: String,
    val type: RecordType,
    val value: String,
    val ttl: Int?
)