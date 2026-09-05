package com.fantamomo.hc.dns.model

import com.fantamomo.hc.dns.model.dns.RecordType
import io.ktor.http.*

class SiteProblem(
    val site: String,
    val problem: SiteProblemType,
    val recordType: RecordType, // only A, AAAA or CNAME
    val recordTarget: String,
    val url: Url,
    val details: String
) {
    init {
        require(recordType in setOf(RecordType.A, RecordType.AAAA, RecordType.CNAME)) { "Record type must be A, AAAA or CNAME" }

    }
}