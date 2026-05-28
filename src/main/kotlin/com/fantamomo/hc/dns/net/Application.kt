package com.fantamomo.hc.dns.net

import io.ktor.server.application.*

suspend fun Application.rootModule() {
    configureExposed()
    configureRateLimiting()
    configureHttp()
    configureMonitoring()
    configureSerialization()
    configureRouting()
}
