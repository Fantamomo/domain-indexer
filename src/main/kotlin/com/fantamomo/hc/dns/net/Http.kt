package com.fantamomo.hc.dns.net

import com.ucasoft.ktor.simpleCache.SimpleCache
import com.ucasoft.ktor.simpleMemoryCache.memoryCache
import io.ktor.server.application.*
import kotlin.time.Duration.Companion.minutes

fun Application.configureHttp() {
    install(SimpleCache) {
        memoryCache {
            invalidateAt = 1.minutes
        }
    }
}
