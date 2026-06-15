package com.fantamomo.hc.dns.model.dns

enum class RecordType {
    A,
    AAAA,
    CNAME,
    ALIAS,
    TXT;

    fun isNamedRecordALink() = this == CNAME || this == ALIAS || this == A || this == AAAA
}
