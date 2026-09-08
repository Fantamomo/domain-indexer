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
    // key is used in the update logic to determine if a message should be sent
    // it is an identifier for details and techFacts, because those two fields are ignored in the logic
    // it should be a combination of important infos from details and techFacts
    val key: String
) {
    val severity: SiteProblemSeverity get() = problem.severity

    init {
        require(recordType in RecordType.checkable) { "Record type must be in ${RecordType.checkable}" }
        require(key.length < 256) { "Key must be less than 256 characters" }
    }

    fun isDifferentFrom(other: SiteProblem): Boolean {
        return site != other.site ||
                problem != other.problem ||
                recordType != other.recordType ||
                recordTarget != other.recordTarget ||
                url != other.url ||

                // in favor of key
//                details != other.details ||

                // we don't use remoteAddress for this determination,
                // because many Nameservers return more than one IP address or switch between them (e.g., Cloudflare, Vercel)
                // so this would be unreliable and we would spam the channel with update messages
//                remoteAddress != other.remoteAddress ||
                exception != other.exception ||

                // in favor of key
//                techFacts != other.techFacts ||
                key != other.key
    }
}