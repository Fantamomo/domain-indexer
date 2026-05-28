package com.fantamomo.hc.dns.net

import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondResource("/content/index.html") {

            }
        }
        staticResources("/content/static", "/static") {
            enableAutoHeadResponse()
        }
    }
}