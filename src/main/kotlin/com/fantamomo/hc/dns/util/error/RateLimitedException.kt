package com.fantamomo.hc.dns.util.error

class RateLimitedException(
    val retryAfter: Long
) : RuntimeException("Rate limited. Retry after $retryAfter seconds")