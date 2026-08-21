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
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlin.time.Duration.Companion.minutes

private val hopByHopHeaders = setOf(
    HttpHeaders.Connection,
//    HttpHeaders.KeepAlive,
    HttpHeaders.ProxyAuthenticate,
    HttpHeaders.ProxyAuthorization,
    HttpHeaders.TE,
    HttpHeaders.Trailer,
    HttpHeaders.TransferEncoding,
    HttpHeaders.Upgrade,
).mapTo(hashSetOf()) { it.lowercase() }

private val headersToIgnoreBySending = hopByHopHeaders + setOf(
    HttpHeaders.Host.lowercase(),
    HttpHeaders.ContentLength.lowercase(),
)

private val headersToIgnoreByReceiving = hopByHopHeaders + setOf(
    HttpHeaders.ContentLength.lowercase(),
)

fun Application.configureRouting() {
    routing {
        cacheOutput(1.minutes) {
            get("/") {
                call.respondText(
                    "Hi, nice to meet you!\n" +
                            "Sadly, here is nothing to see for you.\n" +
                            "I would recommend you to look somewhere else.\n" +
                            "Bye!"
                )
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

            val response = try {
                SharedConstants.proxyClient.get {
                    url {
                        // just copy the complete uri from the request
                        takeFrom(call.request.uri)

                        // then override the protocol to HTTP
                        // (because we cannot be sure that the upstream server uses HTTPS,
                        // and if he doesn't the request will fail, but if we using HTTP and
                        // the server supports HTTPS it automatically redirects to HTTPS)
                        protocol = URLProtocol.HTTP

                        // then override the path to the path from the request
                        encodedPath = path

                        // then override the host to the resolved host name
                        host = resolvedHostName
                    }
                    call.request.headers.forEach { name, value ->
                        if (name.lowercase() !in headersToIgnoreBySending) {
                            header(name, value.first())
                        }
                    }
                    header(HttpHeaders.Host, hostName.value)
                    header("SNI", hostName.value)

                    header(HttpHeaders.AcceptEncoding, "identity")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                call.respondText("Unable to reach upstream host $hostName via $resolvedHostName")
                return@get
            }

            response.headers.forEach { name, values ->
                val lowercase = name.lowercase()

                if (lowercase == HttpHeaders.Location.lowercase()) {
                    for (value in values) {
                        val location = parseUrl(value)
                        if (location == null) {
                            // if it is not a valid url, we are just passing it through
                            call.response.headers.append(name, value)
                            continue
                        }

                        if (location.host != hostName.value && location.host != hostParameter && location.host != hostName.fqdn) {
                            // seams like the server is redirecting to a complete different site, so we are just passing it through
                            call.response.headers.append(name, value)
                        } else {
                            // valid url and it points to the same server, so we just rewrite it to this proxy
                            val rewrittenLocation = rewriteLocation(
                                location,
                                Config.MESSAGE_HOST,
                                Config.SECURE_MESSAGE_HOST,
                                hostParameter
                            )
                            call.response.headers.append(name, rewrittenLocation.toString())
                        }
                    }

                    return@forEach
                }

                if (lowercase in headersToIgnoreByReceiving) {
                    return@forEach
                }

                // forward all values of a header
                values.forEach { value ->
                    call.response.headers.append(name, value)
                }
            }
            if (response.contentType()?.withoutParameters() == ContentType.Text.Html) {
                val html = response.bodyAsText()
                val url = buildUrl {
                    takeFrom(response.request.url)
                    host = hostParameter
                }

                val proxyBaseUrl =
                    "http${if (Config.SECURE_MESSAGE_HOST) "s" else ""}://${Config.MESSAGE_HOST}/preview/$hostParameter"

                val transformedHtml = HtmlProxyRewriter.rewrite(
                    html,
                    url.toString(),
                    proxyBaseUrl
                )
                call.respondText(
                    transformedHtml,
                    contentType = ContentType.Text.Html,
                    status = response.status
                )
                return@get
            }

            // for efficiency and resource usage, we don't load the entire response into memory
            // instead we are streaming the response from the upstream server to the client
            call.respondBytesWriter(
                contentType = response.contentType(),
                status = response.status
            ) {
                response.bodyAsChannel().copyTo(this)
            }
        }
    }
}

private fun rewriteLocation(
    location: Url, // url from the Location header
    publicHost: String, // the host of this server
    publicHostSecure: Boolean, // whether this server is using HTTPS
    hostParameter: String // the host parameter from the request which is the server the proxy connects to
): Url {
    return try {
        if (location.host.isBlank()) {
            return location
        }

        buildUrl {
            takeFrom(location)

            if (location.protocol == URLProtocol.HTTP || location.protocol == URLProtocol.HTTPS) {
                protocol = if (publicHostSecure) URLProtocol.HTTPS else URLProtocol.HTTP
            }
            // we ignore any other protocol because well if don't support them


            // sets the host to this public host, so the request will go through this server
            host = publicHost
            // fast path, just add the "preview" and host before the real path
            encodedPathSegments = listOf("preview", hostParameter) + encodedPathSegments
        }
    } catch (_: Exception) {
        location
    }
}