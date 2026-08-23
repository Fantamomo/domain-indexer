package com.fantamomo.hc.dns.net

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText(
                "Hi, nice to meet you!\n" +
                        "Sadly, here is nothing to see for you.\n" +
                        "I would recommend you to look somewhere else.\n" +
                        "Bye!"
            )
        }

        get("robots.txt") {
            call.respondText(
                "User-agent: *\n" +
                        "Disallow: /"
            )
        }

        route("/preview/{host}/{path...}") {
            handle(RoutingContext::proxyHandle)
        }
    }
}
