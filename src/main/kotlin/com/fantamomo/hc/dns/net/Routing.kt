package com.fantamomo.hc.dns.net

import com.ucasoft.ktor.simpleCache.cacheOutput
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

fun Application.configureRouting() {
    routing {
        cacheOutput(10.seconds) {
            get("/") {
                call.respondResource("/content/index.html")
            }
        }
        cacheOutput(1.hours) {
            staticResources("/content/static", "/static") {
                enableAutoHeadResponse()
            }
        }
        route("/api/v1/") {
            apiRouting()
        }
    }
}

private fun Route.apiRouting() {

}
