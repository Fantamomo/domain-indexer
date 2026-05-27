package com.fantamomo.hc

import io.ktor.server.application.Application

fun Application.rootModule() {
    configureExposed()
    configurePostgres()
    configureRateLimiting()
    configureHttp()
    configureMonitoring()
    configureSerialization()
    configureRouting()
}
