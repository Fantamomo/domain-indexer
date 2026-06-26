package com.fantamomo.hc.dns.net

import com.fantamomo.hc.dns.data.Config
import com.fantamomo.hc.dns.data.SharedConstants
import com.fantamomo.hc.dns.manager.HostNameCache
import com.fantamomo.hc.dns.model.Hostname
import com.fantamomo.hc.dns.util.HtmlProxyRewriter
import com.ucasoft.ktor.simpleCache.cacheOutput
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.time.Duration.Companion.minutes

private val headersToIgnoreBySending = listOf(
    HttpHeaders.Host,
    HttpHeaders.TransferEncoding,
).map { it.lowercase() }

private val headersToIgnoreByReceiving = listOf(
    HttpHeaders.ContentLength,
    HttpHeaders.TransferEncoding,
).map { it.lowercase() }

fun Application.configureRouting() {
    routing {
        cacheOutput(1.minutes) {
            get("/") {
                call.respondText("Hi, nice to meet you!\nSadly, here is nothing to see for you.\nI would recommend you to look somewhere else.\nBye!")
            }
        }

        get("/preview/{host}/{path...}") {
            val hostParameter = call.pathParameters["host"]
            if (hostParameter == null) {
                call.respondText("Missing host parameter", status = HttpStatusCode.BadRequest)
                return@get
            }
            val hostName = try {
                Hostname(hostParameter)
            } catch (_: Exception) {
                call.respondText("Invalid host parameter", status = HttpStatusCode.BadRequest)
                return@get
            }
            val resolvedHostName = HostNameCache.find(hostName)
            if (resolvedHostName == null) {
                call.respondText("Host not found", status = HttpStatusCode.NotFound)
                return@get
            }

            val path = call.pathParameters["path"] ?: ""

            val response = SharedConstants.proxyClient.get {
                url {
                    protocol = URLProtocol.HTTP
                    host = resolvedHostName
                    encodedPath = path
                }
                call.request.headers.forEach { name, value ->
                    if (name.lowercase() !in headersToIgnoreBySending) {
                        header(name, value.first())
                    }
                }
                header(HttpHeaders.Host, hostName.value)
                header("SNI", hostName.value)
            }
            response.headers.forEach { name, value ->
                if (name.lowercase() !in headersToIgnoreByReceiving) {
                    call.response.header(name, value.first())
                }
            }
            if (response.contentType()?.withoutParameters() == ContentType.Text.Html) {
                val html = response.bodyAsText()
                val url = buildUrl {
                    takeFrom(response.request.url)
                    host = hostParameter
                }
                @Suppress("HttpUrlsUsage")
                val transformedHtml = HtmlProxyRewriter.rewrite(html, url.toString(), "http://${Config.MESSAGE_HOST}/preview/$hostParameter")
                call.respondText(
                    transformedHtml,
                    contentType = ContentType.Text.Html,
                    status = response.status
                )
                return@get
            }
            val bytes = response.bodyAsBytes()
            call.respondBytes(
                contentType = response.contentType(),
                status = response.status,
                bytes = bytes
            )
        }
    }
}