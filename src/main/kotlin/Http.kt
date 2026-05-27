package com.fantamomo.hc

import io.ktor.server.application.*
import com.ucasoft.ktor.simpleCache.SimpleCache
import com.ucasoft.ktor.simpleMemoryCache.*
import kotlin.time.Duration.Companion.seconds

fun Application.configureHttp() {
    install(SimpleCache) {
        memoryCache {
            invalidateAt = 10.seconds
        }
    }
}
