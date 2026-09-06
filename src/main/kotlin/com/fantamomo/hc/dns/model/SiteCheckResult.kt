package com.fantamomo.hc.dns.model

sealed interface SiteCheckResult {
    data object Success : SiteCheckResult

    data class Failure(
        val type: SiteProblemType,
        val details: String,
        val exceptionType: String? = null,
        val exceptionMessage: String? = null,
        val remoteAddress: String? = null,
        val techFacts: String? = null,
    ) : SiteCheckResult
}