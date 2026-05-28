package com.fantamomo.hc.dns.net

import com.fantamomo.hc.dns.App
import com.fantamomo.hc.dns.task.InitTask
import dev.hayden.KHealth
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureMonitoring() {
    install(CallId) {
        header(HttpHeaders.XRequestId)
        verify { callId: String ->
            callId.isNotEmpty()
        }
    }
    configureKHealth()
}

private fun Application.configureKHealth() {
    install(KHealth) {
        readyCheckPath = "/ready"
        readyChecks {
            for (initTask in App.initTasks) {
                check(initTask.name) {
                    initTask.state == InitTask.State.COMPLETED
                }
            }
        }
    }
    routing {
        route("/ready") {
            get("/{check}") {
                val check = call.parameters["check"]
                if (check.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }
                val initTask = App.initTasks.find { it.name == check }
                if (initTask == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "long_description" to initTask.longDescription,
                        "short_description" to initTask.shortDescription,
                        "id" to initTask.name,
                        "state" to initTask.state.name,
                        "completed" to (initTask.state == InitTask.State.COMPLETED).toString(),
                        "running" to (initTask.state == InitTask.State.RUNNING).toString(),
                    )
                )
            }
        }
    }
}