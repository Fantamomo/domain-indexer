package com.fantamomo.hc.dns.model

import com.fantamomo.hc.dns.model.dns.RecordType
import io.ktor.http.*

class SiteProblem(
    val site: String,
    val problem: SiteProblemType,
    val recordType: RecordType, // only A, AAAA, CNAME or ALIAS
    val recordTarget: String,
    val url: Url,
    val details: String,
    val remoteAddress: String? = null,
    val exception: String? = null,
    val techFacts: String? = null
) {
    val severity: SiteProblemSeverity get() = problem.severity

    init {
        require(recordType in RecordType.checkable) { "Record type must be in ${RecordType.checkable}" }
    }
}