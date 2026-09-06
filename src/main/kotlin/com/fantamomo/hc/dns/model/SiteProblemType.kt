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
        "gray",
        SiteProblemSeverity.HIGH
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
        "red",
        SiteProblemSeverity.HIGH
    ),

    DNS_TIMEOUT(
        "DNS resolution timed out",
        "dns_timeout",
        "red",
        SiteProblemSeverity.HIGH
    ),

    DNS_INCONSISTENT(
        "DNS inconsistent",
        "dns_inconsistent",
        "red",
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
        "red",
        SiteProblemSeverity.HIGH
    ),

    CONNECTION_RESET(
        "Connection reset",
        "connection_reset",
        "red",
        SiteProblemSeverity.HIGH
    ),

    CONNECTION_FAILED(
        "Connection failed",
        "connection_failed",
        "red",
        SiteProblemSeverity.HIGH
    ),

    READ_TIMEOUT(
        "Response timed out",
        "read_timeout",
        "red",
        SiteProblemSeverity.HIGH
    ),

    WRITE_TIMEOUT(
        "Request timed out",
        "write_timeout",
        "red",
        SiteProblemSeverity.HIGH
    ),

    TLS_CERTIFICATE_EXPIRED(
        "TLS certificate expired",
        "tls_certificate_expired",
        "red",
        SiteProblemSeverity.HIGH
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
        "red",
        SiteProblemSeverity.HIGH
    ),

    TLS_CERTIFICATE_UNTRUSTED(
        "TLS certificate not trusted",
        "tls_certificate_untrusted",
        "red",
        SiteProblemSeverity.HIGH
    ),

    TLS_HANDSHAKE_FAILED(
        "TLS handshake failed",
        "tls_handshake_failed",
        "red",
        SiteProblemSeverity.HIGH
    ),

    TLS_PROTOCOL_ERROR(
        "TLS protocol error",
        "tls_protocol_error",
        "red",
        SiteProblemSeverity.HIGH
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
        "red",
        SiteProblemSeverity.HIGH
    ),

    HTTP_CLIENT_ERROR(
        "HTTP client error",
        "http_client_error",
        "red",
        SiteProblemSeverity.HIGH
    ),

    HTTP_PROTOCOL_ERROR(
        "HTTP protocol error",
        "http_protocol_error",
        "red",
        SiteProblemSeverity.HIGH
    ),
}