package com.fantamomo.hc.dns.model

enum class SiteProblemType(
    val slackOptionName: String,
    val slackOptionId: String,
    val slackColor: String,
    val severity: SiteProblemSeverity
) {
    TOO_MANY_REDIRECTS(
        "Too many redirects",
        "too_many_redirects",
        "yellow",
        SiteProblemSeverity.MEDIUM
    ),

    DNS_UNAVAILABLE(
        "DNS unavailable",
        "dns_unavailable",
        "red",
        SiteProblemSeverity.HIGH
    ),

    DNS_RESOLUTION_FAILED(
        "DNS resolution failed",
        "dns_resolution_failed",
        "orange",
        SiteProblemSeverity.HIGH
    ),

    DNS_TIMEOUT(
        "DNS resolution timed out",
        "dns_timeout",
        "yellow",
        SiteProblemSeverity.MEDIUM
    ),

    DNS_INCONSISTENT(
        "DNS inconsistent",
        "dns_inconsistent",
        "brown",
        SiteProblemSeverity.MEDIUM
    ),

    DNS_RECORD_MALFORMED(
        "DNS record malformed",
        "dns_record_malformed",
        "red",
        SiteProblemSeverity.CRITICAL
    ),

    CONNECTION_REFUSED(
        "Connection refused",
        "connection_refused",
        "red",
        SiteProblemSeverity.HIGH
    ),

    CONNECTION_TIMEOUT(
        "Connection timed out",
        "connection_timeout",
        "orange",
        SiteProblemSeverity.HIGH
    ),

    CONNECTION_RESET(
        "Connection reset",
        "connection_reset",
        "purple",
        SiteProblemSeverity.HIGH
    ),

    CONNECTION_FAILED(
        "Connection failed",
        "connection_failed",
        "orange",
        SiteProblemSeverity.HIGH
    ),

    READ_TIMEOUT(
        "Response timed out",
        "read_timeout",
        "yellow",
        SiteProblemSeverity.MEDIUM
    ),

    WRITE_TIMEOUT(
        "Request timed out",
        "write_timeout",
        "yellow",
        SiteProblemSeverity.MEDIUM
    ),

    TLS_CERTIFICATE_EXPIRED(
        "TLS certificate expired",
        "tls_certificate_expired",
        "red",
        SiteProblemSeverity.CRITICAL
    ),

    TLS_CERTIFICATE_EXPIRING(
        "TLS certificate expiring",
        "tls_certificate_expiring",
        "blue",
        SiteProblemSeverity.INFO
    ),

    TLS_CERTIFICATE_NOT_YET_VALID(
        "TLS certificate not yet valid",
        "tls_certificate_not_yet_valid",
        "red",
        SiteProblemSeverity.HIGH
    ),

    TLS_CERTIFICATE_HOSTNAME_MISMATCH(
        "TLS certificate hostname mismatch",
        "tls_certificate_hostname_mismatch",
        "purple",
        SiteProblemSeverity.HIGH
    ),

    TLS_CERTIFICATE_UNTRUSTED(
        "TLS certificate not trusted",
        "tls_certificate_untrusted",
        "purple",
        SiteProblemSeverity.HIGH
    ),

    TLS_HANDSHAKE_FAILED(
        "TLS handshake failed",
        "tls_handshake_failed",
        "orange",
        SiteProblemSeverity.HIGH
    ),

    TLS_PROTOCOL_ERROR(
        "TLS protocol error",
        "tls_protocol_error",
        "red",
        SiteProblemSeverity.CRITICAL
    ),

    SERVICE_UNAVAILABLE(
        "Service unavailable",
        "service_unavailable",
        "red",
        SiteProblemSeverity.HIGH
    ),

    SERVICE_NOT_FOUND(
        "Service not found",
        "service_not_found",
        "orange",
        SiteProblemSeverity.HIGH
    ),

    HTTP_CLIENT_ERROR(
        "HTTP client error",
        "http_client_error",
        "yellow",
        SiteProblemSeverity.MEDIUM
    ),

    HTTP_PROTOCOL_ERROR(
        "HTTP protocol error",
        "http_protocol_error",
        "orange",
        SiteProblemSeverity.HIGH
    ),
}