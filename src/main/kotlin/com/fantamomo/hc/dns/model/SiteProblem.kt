package com.fantamomo.hc.dns.model

import com.fantamomo.hc.dns.model.dns.RecordType
import io.ktor.http.*

data class SiteProblem(
    val site: String,
    val problem: SiteProblemType,
    val recordType: RecordType, // only A, AAAA, CNAME or ALIAS
    val recordTarget: String,
    val url: Url,
    val details: String,
    val remoteAddress: String? = null,
    val exception: String? = null,
    val techFacts: String? = null,
    val key: String
) {
    val severity: SiteProblemSeverity get() = problem.severity

    init {
        require(recordType in RecordType.checkable) { "Record type must be in ${RecordType.checkable}" }
    }

    fun isDifferentFrom(other: SiteProblem): Boolean {
        return site != other.site ||
                problem != other.problem ||
                recordType != other.recordType ||
                recordTarget != other.recordTarget ||
                url != other.url ||
//                details != other.details || // in favor of key
                remoteAddress != other.remoteAddress ||
                exception != other.exception ||
//                techFacts != other.techFacts || // in favor of key
                key != other.key
    }
}