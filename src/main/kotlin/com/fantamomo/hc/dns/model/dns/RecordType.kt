package com.fantamomo.hc.dns.model.dns

enum class RecordType(val slackOptionName: String, val slackOptionId: String, val slackOptionColor: String, ) {
    A("A", "A", "lagoon"),
    AAAA("AAAA", "AAAA", "lagoon"),
    CNAME("CNAME", "CNAME", "aubergine"),
    ALIAS("ALIAS", "ALIAS", "aubergine"),
    TXT("", "", "");

    fun isNamedRecordALink() = this == CNAME || this == ALIAS || this == A || this == AAAA

    companion object {
        val CHECKABLE = listOf(A, AAAA, CNAME, ALIAS)
    }
}
