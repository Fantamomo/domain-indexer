package com.fantamomo.hc.dns.net

import io.ktor.server.application.*

suspend fun Application.rootModule() {
    configureRateLimiting()
    configureHttp()
    configureSerialization()
    configureMonitoring()
    configureRouting()
}
