package com.fantamomo.hc.dns.model

enum class SiteProblemType(
    val slackOptionName: String,
    val slackOptionId: String,
    val slackColor: String,
    val severity: SiteProblemSeverity
) {
    // trying to reach the site, failed because of too many redirects (more than 20)
    TOO_MANY_REDIRECTS("Too many redirects", "too_many_redirects", "gray", SiteProblemSeverity.HIGH),

    // although the DNS should exist, it doesn't
    DNS_UNAVAILABLE("DNS unavailable", "dns_unavailable", "red", SiteProblemSeverity.HIGH),

    // although the DNS should resolve to the expected IP, it doesn't
    DNS_RESOLUTION_FAILED("DNS resolution failed", "dns_resolution_failed", "red", SiteProblemSeverity.HIGH),

    // the DNS does not resolve the same around the world
    DNS_INCONSISTENT("DNS inconsistent", "dns_inconsistent", "red", SiteProblemSeverity.MEDIUM),

    // the DNS record is malformed (e.g. CNAME target misses a "." at the end)
    DNS_RECORD_MALFORMED("DNS record malformed", "dns_record_mailedformed", "red", SiteProblemSeverity.CRITICAL),

    // the TLS certificate expired
    TLS_CERTIFICATE_EXPIRED("TLS certificate expired", "tls_certificate_expired", "red", SiteProblemSeverity.HIGH),

    // the TLS certificate is expiring soon (less than 30 days)
    TLS_CERTIFICATE_EXPIRING(
        "TLS certificate expiring",
        "tls_certificate_expiring",
        "orange",
        SiteProblemSeverity.INFO
    ),

    // the connection to the site failed
    CONNECTION_FAILED("Connection failed", "connection_failed", "red", SiteProblemSeverity.HIGH),

    // service unavailable (e.g. the server is behind a proxy and the proxy tells us that the service is unavailable)
    SERVICE_UNAVAILABLE("Service unavailable", "service_unavailable", "red", SiteProblemSeverity.HIGH),

    // service not found (e.g. the server is behind a proxy and the proxy tells us that the service is not found)
    SERVICE_NOT_FOUND("Service not found", "service_not_found", "red", SiteProblemSeverity.HIGH),
}
