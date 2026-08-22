package com.fantamomo.hc.dns.net

import com.fantamomo.hc.dns.data.Config
import com.fantamomo.hc.dns.data.SharedConstants
import com.fantamomo.hc.dns.manager.HostNameCache
import com.fantamomo.hc.dns.model.Hostname
import com.fantamomo.hc.dns.util.HtmlProxyRewriter
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import org.slf4j.LoggerFactory

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

private val routingLogger = LoggerFactory.getLogger("Routing")

private val hostThatRedirectToHttps: MutableSet<String> = mutableSetOf()

fun Application.configureRouting() {
    routing {
//        cacheOutput(1.minutes) {
        get("/") {
            call.respondText(
                "Hi, nice to meet you!\n" +
                        "Sadly, here is nothing to see for you.\n" +
                        "I would recommend you to look somewhere else.\n" +
                        "Bye!"
            )
//        }
        }

        route("/preview/{host}/{path...}") {
            handle {
                val hostParameter = call.pathParameters["host"]
                if (hostParameter == null) {
                    call.respondText("Missing host parameter", status = HttpStatusCode.BadRequest)
                    return@handle
                }
                val hostParts = hostParameter.split("+")
                if (hostParts.size !in 1..2) {
                    call.respondText("Invalid host parameter", status = HttpStatusCode.BadRequest)
                    return@handle
                }
                val rawHostName = hostParts.first()
                val targetAddress = hostParts.getOrNull(1)
                val hostName = try {
                    Hostname(rawHostName)
                } catch (_: Exception) {
                    call.respondText("Invalid host parameter", status = HttpStatusCode.BadRequest)
                    return@handle
                }
                val resolvedHostName = HostNameCache.find(hostName, targetAddress)
                if (resolvedHostName == null) {
                    call.respondText(
                        "Host not found" +
                                if (targetAddress != null)
                                    " or the expected target address ($targetAddress) has not been found under this host"
                                else "",
                        status = HttpStatusCode.NotFound
                    )
                    return@handle
                }

                val path = call.pathParameters.getAll("path") ?: emptyList()

                val upstreamUrl = buildUrl {
                    // just copy the complete uri from the request
                    takeFrom(call.request.uri)

                    // #### THE FOLLOWING IT NOT TRUE, ONLY KEEPT FOR REFERENCE
                    // #### then override the protocol to HTTP
                    // #### (because we cannot be sure that the upstream server uses HTTPS,
                    // #### and if he doesn't the request will fail, but if we using HTTP and
                    // #### the server supports HTTPS it automatically redirects to HTTPS)
                    // yeah normally that would be true, but because we pass redirects through to the client
                    // we would land in an infinite loop, see the comments by
                    // `hostThatRedirectToHttps.add(hostParameter)`
                    // for more information
                    protocol = if (hostParameter in hostThatRedirectToHttps) URLProtocol.HTTPS else URLProtocol.HTTP

                    // then override the path to the path from the request
                    pathSegments = path

                    // then override the host to the resolved host name
                    host = resolvedHostName
                }


                SharedConstants.sniSocketFactory.setTargetSni(
                    hosts = listOf(upstreamUrl.host),
                    target = hostName.value
                )

                val response = try {
                    SharedConstants.proxyClient.request {
                        method = call.request.httpMethod

                        url(upstreamUrl)

                        call.request.headers.forEach { name, values ->
                            val lowercase = name.lowercase()

                            if (lowercase !in headersToIgnoreBySending) {
                                if (lowercase == HttpHeaders.AcceptEncoding.lowercase()) {
                                    // we modify the Accept-Encoding header to only include gzip, deflate, and identity
                                    // because we only support these compression algorithms, and if the server returns HTML
                                    // we need to decompress the response for the HtmlRewritter

                                    headers.remove(name)

                                    val filteredValues = values.first()
                                        .split(",")
                                        .map { it.trim().lowercase() }
                                        .filter { it == "gzip" || it == "deflate" || it == "identity" }

                                    headers.append(name, filteredValues.joinToString(","))
                                } else {
                                    headers.appendAll(name, values)
                                }
                            }
                        }
                        header(HttpHeaders.Host, hostName.value)
                        header("SNI", hostName.value)

                        // stream the body of the call to the upstream host
                        // dont do it by Get or Head because those two methods dont have a body
                        if (call.request.httpMethod != HttpMethod.Get &&
                            call.request.httpMethod != HttpMethod.Head
                        ) {
                            setBody(call.receiveChannel())
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    routingLogger.error("Unable to reach upstream host ${hostName.value} via $resolvedHostName", e)
                    call.respondText(
                        "Unable to reach upstream host ${hostName.value} via $resolvedHostName",
                        status = HttpStatusCode.ServiceUnavailable
                    )
                    return@handle
                }

                val isHtml = response.contentType()?.withoutParameters() == ContentType.Text.Html

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

                                // not the best location here, but it works
                                // some servers send permanent redirects to the HTTPS version of the site if we try to connect to them via HTTP
                                // while this is good and mostly works, it does not work in this specific case
                                // it would result in an infinite loop because of the location rewrite
                                // to avoid this we need to directly request via HTTPS
                                // so we add the host to a list of hosts that redirect to HTTPS
                                // and on the next request we will directly request via HTTPS
                                if (response.status == HttpStatusCode.PermanentRedirect &&
                                    upstreamUrl.protocol == URLProtocol.HTTP &&
                                    location.protocol == URLProtocol.HTTPS
                                ) {
                                    hostThatRedirectToHttps.add(hostParameter)
                                }
                            }
                        }

                        return@forEach
                    }

                    if (lowercase in headersToIgnoreByReceiving) {
                        return@forEach
                    }

                    if (isHtml && lowercase == HttpHeaders.ContentEncoding.lowercase()) {
                        // if we are forwarding html, we don't want to forward the content-encoding header to the client
                        // because we rewrite it and for that we decompress it, but the client would still think it's compressed
                        return@forEach
                    }

                    // forward all values of a header
                    values.forEach { value ->
                        call.response.headers.append(name, value)
                    }
                }

                if (isHtml) {

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
                        contentType = response.contentType(),
                        status = response.status
                    )
                    return@handle
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

            encodedPathSegments = /*if (encodedPathSegments.isEmpty() || encodedPathSegments[0] != "") {*/
                    // fast path, just add the "preview" and host before the real path
                listOf("preview", hostParameter) + encodedPathSegments
            /*} else {
                listOf("", "preview", hostParameter) + encodedPathSegments.subList(1, encodedPathSegments.size)
            }*/
        }
    } catch (_: Exception) {
        location
    }
}