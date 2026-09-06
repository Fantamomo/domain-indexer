package com.fantamomo.hc.dns.model.dns

enum class RecordType(val slackOptionName: String, val slackOptionId: String, val slackOptionColor: String, ) {
    A("A", "A", "green"),
    AAAA("AAAA", "AAAA", "green"),
    CNAME("CNAME", "CNAME", "blue"),
    ALIAS("ALIAS", "ALIAS", "blue"),
    TXT("", "", "");

    fun isNamedRecordALink() = this == CNAME || this == ALIAS || this == A || this == AAAA

    companion object {
        val checkable = listOf(A, AAAA, CNAME, ALIAS)
    }
}
