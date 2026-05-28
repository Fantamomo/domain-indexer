package com.fantamomo.hc.dns.net

import io.github.flaxoos.ktor.server.plugins.ratelimiter.RateLimiting
import io.github.flaxoos.ktor.server.plugins.ratelimiter.implementations.TokenBucket
import io.ktor.server.application.*
import io.ktor.server.routing.*
import kotlin.time.Duration.Companion.minutes

fun Application.configureRateLimiting() {
    routing {
        route("/") {
            install(RateLimiting) {
                rateLimiter {
                    type = TokenBucket::class
                    capacity = 60
                    rate = 1.minutes
                }
            }
        }
    }
}
