package com.fantamomo.hc.dns.task

import com.fantamomo.hc.dns.App
import com.fantamomo.hc.dns.data.Config
import com.fantamomo.hc.dns.data.SharedConstants
import com.fantamomo.hc.dns.db.RecordTable
import com.fantamomo.hc.dns.db.SiteCheckerIgnoreListTable
import com.fantamomo.hc.dns.manager.DatabaseManager
import com.fantamomo.hc.dns.model.SiteCheckResult
import com.fantamomo.hc.dns.model.SiteProblem
import com.fantamomo.hc.dns.model.SiteProblemType
import com.fantamomo.hc.dns.model.dns.RecordState
import com.fantamomo.hc.dns.model.dns.RecordType
import com.fantamomo.hc.dns.task.sc.SlackSiteCheckerConnector
import io.ktor.http.*
import io.ktor.network.sockets.SocketTimeoutException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Semaphore
import kotlinx.io.IOException
import okhttp3.*
import okhttp3.internal.platform.Platform
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.r2dbc.select
import org.slf4j.LoggerFactory
import java.net.*
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.net.ssl.*
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

object SiteChecker {
    private val TIME_BETWEEN_RUNS by lazy { Config.SITE_CHECK_INTERVAL }

    private const val MAX_GLOBAL_CONCURRENCY = 128
    private const val MAX_TARGET_CONCURRENCY = 4
    private const val MAX_REQUESTS_PER_MINUTE = 30

    private val TARGET_REQUEST_INTERVAL =
        60.seconds / MAX_REQUESTS_PER_MINUTE

    private val GLOBAL_RETRY_DELAY = 100.milliseconds

    private val logger = LoggerFactory.getLogger(SiteChecker::class.java)

    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        logger.error("A task in the scheduler encountered an exception", exception)
    }

    private val job = SupervisorJob(App.scope.coroutineContext.job)
    private val running = AtomicBoolean(false)

    private val scope = CoroutineScope(
        App.scope.coroutineContext + job + exceptionHandler
    )

    private data class Site(
        val host: String,
        val target: String,
        val type: RecordType
    )

    private class CheckContext(
        val url: String,
    ) {
        val dnsAddresses = mutableListOf<InetAddress>()

        var connectedAddress: InetSocketAddress? = null
        var proxy: Proxy? = null

        var protocol: Protocol? = null
        var handshake: Handshake? = null

        var certificateChain: List<X509Certificate> = emptyList()

        var dnsStartedAt: Long? = null
        var dnsFinishedAt: Long? = null

        var connectStartedAt: Long? = null
        var connectFinishedAt: Long? = null

        var tlsStartedAt: Long? = null
        var tlsFinishedAt: Long? = null

        var requestStartedAt: Long? = null
        var responseHeadersAt: Long? = null

        var failure: Throwable? = null
    }

    private class RecordingTrustManager(
        private val delegate: X509TrustManager,
        private val context: CheckContext,
    ) : X509TrustManager {

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            delegate.acceptedIssuers

        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) {
            delegate.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) {
            context.certificateChain = chain.toList()

            delegate.checkServerTrusted(chain, authType)
        }
    }

    private class TargetLimiter {
        val semaphore = Semaphore(MAX_TARGET_CONCURRENCY)

        @Volatile
        var nextRequestAt = System.nanoTime()

        val lock = Any()

        suspend fun awaitRateLimit() {
            val delayNanos = synchronized(lock) {
                val now = System.nanoTime()
                val waitNanos = nextRequestAt - now

                nextRequestAt = maxOf(
                    nextRequestAt + TARGET_REQUEST_INTERVAL.inWholeNanoseconds,
                    now
                )

                waitNanos
            }

            if (delayNanos > 0) {
                delay(delayNanos.nanoseconds)
            }
        }
    }

    suspend fun start() {
        if (Config.SLACK_BOT_TOKEN.isBlank()) {
            throw IllegalStateException("SLACK_BOT_TOKEN is not set")
        }

        if (!running.compareAndSet(expectedValue = false, newValue = true)) {
            throw IllegalStateException("Site checker is already running")
        }

        SlackSiteCheckerConnector.start()

        logger.info("Site checker started")

//        delay(TIME_BETWEEN_RUNS)

        try {
            val job = scope.launch {
                run()
            }
            job.join()
        } catch (e: Throwable) {
            logger.error(
                "The site checker has stopped unexpectedly. " +
                        "That should never happen, check logs for more details and then restart the application"
            )
            throw e
        }
    }

    private suspend fun run() {
        while (currentCoroutineContext().isActive) {
            try {
                checkSites()
                logger.info("Site checker run completed")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("An exception occurred while checking sites", e)
            }

            delay(TIME_BETWEEN_RUNS)
        }
    }

    private suspend fun checkSites() = coroutineScope {

        SharedConstants.workSynchronizer.waitUntilUnlocked()

        val sites = DatabaseManager.transaction {
            RecordTable
                .select(
                    RecordTable.host,
                    RecordTable.name,
                    RecordTable.type,
                    RecordTable.currentValue,
                )
                .where {
                    RecordTable.type inList RecordType.checkable and
                            (RecordTable.state eq RecordState.ACTIVE) and
                            notExists(
                                SiteCheckerIgnoreListTable
                                    .select(
                                        SiteCheckerIgnoreListTable.host,
                                        SiteCheckerIgnoreListTable.name
                                    )
                                    .where {
                                        SiteCheckerIgnoreListTable.host eq RecordTable.host and
                                                (SiteCheckerIgnoreListTable.name eq RecordTable.name)
                                    }
                            )
                }
                .mapNotNull {
                    val host = it[RecordTable.host]
                    val name = it[RecordTable.name]

                    if (name.startsWith("*")) {
                        // we are not able to really check wildcard records, so we just skip them
                        return@mapNotNull null
                    }
                    val currentValue = it[RecordTable.currentValue]

                    val target = currentValue?.value
                        ?.trim()
                        ?.takeIf { value -> value.isNotEmpty() }
                        ?: return@mapNotNull null

                    val fqdn = if (name.isEmpty() || name == "@") {
                        host
                    } else {
                        "$name.$host"
                    }

                    Site(
                        host = fqdn,
                        target = target,
                        type = it[RecordTable.type]
                    )
                }
                .toList()
        }

        if (sites.isEmpty()) {
            return@coroutineScope
        }

        logger.info("Found ${sites.size} sites to check")

        val sitesByTarget = sites.groupBy { it.target }
        val globalSemaphore = Semaphore(MAX_GLOBAL_CONCURRENCY)

        sitesByTarget
            .map { (target, targetSites) ->
                async(Dispatchers.IO) {
                    checkTarget(
                        target = target,
                        sites = targetSites,
                        globalSemaphore = globalSemaphore,
                    )
                }
            }
            .awaitAll()
    }

    private suspend fun checkTarget(
        target: String,
        sites: List<Site>,
        globalSemaphore: Semaphore,
    ) = coroutineScope {
        val limiter = TargetLimiter()

        sites.map { site ->
            SharedConstants.workSynchronizer.waitUntilUnlocked()
            async(Dispatchers.IO) {
                limiter.awaitRateLimit()

                limiter.semaphore.acquire()
                try {
                    globalSemaphore.acquire()

                    try {
                        SharedConstants.workSynchronizer.waitUntilUnlocked()
                        logger.info("Checking site: ${site.host}") // todo: remove this line

                        @Suppress("HttpUrlsUsage")
                        val result = checkSite("http://${site.host}/")

                        when (result) {
                            is SiteCheckResult.Failure -> {
                                var skip = false
                                if (site.type == RecordType.CNAME && result.type == SiteProblemType.DNS_UNAVAILABLE) {
                                    skip = true
                                    // the following skip records are mostly CNAME verification records which will never resolve to an IP address
                                    // so we just ignore them
                                    val t = site.target
                                    if ("._domainkey." in site.host) {
                                        logger.info("Skipping alert for site ${site.host} because it is a DKIM record")
                                    } else if (t.endsWith(".acme.tier2.infra.hackclub.dev.")) {
                                        logger.info("Skipping alert for site ${site.host} because it is an Vercel (hackclub.dev) ACME validation record")
                                    } else if (t.endsWith(".dcv.cloudflare.com.")) {
                                        logger.info("Skipping alert for site ${site.host} because it is an DCV-Cloudflare validation record")
                                    } else if (t.endsWith("._acme.deno.net.")) {
                                        logger.info("Skipping alert for site ${site.host} because it is an ACME validation record")
                                    } else if (t.endsWith(".sectigo.com.") && t.matches(Regex("[A-Fa-f0-9]{32}\\.[A-Fa-f0-9]{32}(?:\\.[A-Za-z0-9_-]+)?\\.sectigo\\.com\\."))) {
                                        logger.info("Skipping alert for site ${site.host} because it is an Sectigo validation record")
                                    } else if (t.endsWith(".sendgrid.net.") && t.matches(Regex("u\\d+\\.wl\\d+\\.sendgrid\\.net\\."))) {
                                        logger.info("Skipping alert for site ${site.host} because it is a SendGrid validation record")
                                    } else if (t.endsWith(".acm-validations.aws.")) {
                                        logger.info("Skipping alert for site ${site.host} because it is an ACM validation record")
                                    } else {
                                        skip = false
                                    }
                                }
                                if (skip) {
                                    scope.launch {
                                        SlackSiteCheckerConnector.success(site.host)
                                    }
                                    return@async
                                }
                                val exceptionStr = when {
                                    result.exceptionType != null && result.exceptionMessage != null -> "${result.exceptionType}: ${result.exceptionMessage}"
                                    result.exceptionType != null -> result.exceptionType
                                    result.exceptionMessage != null -> result.exceptionMessage
                                    else -> null
                                }
                                val problem = SiteProblem(
                                    site = site.host,
                                    problem = result.type,
                                    recordType = site.type,
                                    recordTarget = site.target,
                                    url = Url("http://${site.host}/"),
                                    details = result.details,
                                    remoteAddress = result.remoteAddress,
                                    exception = exceptionStr,
                                    techFacts = result.techFacts,
                                )
                                scope.launch {
                                    SlackSiteCheckerConnector.problem(problem)
                                }
                            }
                            SiteCheckResult.Success -> {
                                scope.launch {
                                    SlackSiteCheckerConnector.success(site.host)
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error("Unexpected error checking site ${site.host}", e)
                    } finally {
                        globalSemaphore.release()
                    }
                } finally {
                    limiter.semaphore.release()
                }
            }
        }.awaitAll()
    }

    private fun checkSite(url: String): SiteCheckResult {

        val context = CheckContext(url)

        val client = createClient(context)

        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/139.0.0.0 Safari/537.36"
            )
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9," +
                        "image/avif,image/webp,image/apng,*/*;q=0.8"
            )
            .header("Accept-Language", "en-US,en;q=0.5")
            .header("Accept-Encoding", "gzip, deflate")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                checkResponse(response, context)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            checkException(
                url = url,
                exception = e,
                context = context,
            )
        }
    }

    private fun checkResponse(
        response: Response,
        context: CheckContext,
    ): SiteCheckResult {

        val redirects = redirectChain(response)

        if (redirects.size > 20) {
            return failure(
                SiteProblemType.TOO_MANY_REDIRECTS,
                buildRedirectDetails(redirects),
                context = context,
            )
        }

        val body = runCatching {
            response.body.string()
        }.getOrDefault("")

        val server = response.header("Server")
            ?.lowercase()

        if (
            server == "vercel" &&
            response.header("x-vercel-error") == "DEPLOYMENT_NOT_FOUND" &&
            response.code == 404 &&
            body.contains(
                "https://vercel.com/docs/errors/DEPLOYMENT_NOT_FOUND"
            )
        ) {
            return failure(
                SiteProblemType.SERVICE_NOT_FOUND,
                buildHttpDetails(
                    response,
                    "The server reported that the requested deployment could not be found.",
                ),
                context = context,
            )
        }

//        return when {
//            response.code in 500..599 -> {
//                failure(
//                    SiteProblemType.SERVICE_UNAVAILABLE,
//                    buildHttpDetails(
//                        response,
//                        "The server returned an HTTP server error.",
//                    )
//                )
//            }
//
//            response.code in 400..499 -> {
//                failure(
//                    if (response.code == 404) {
//                        SiteProblemType.SERVICE_NOT_FOUND
//                    } else {
//                        SiteProblemType.HTTP_CLIENT_ERROR
//                    },
//                    buildHttpDetails(
//                        response,
//                        "The server returned an HTTP client error.",
//                    )
//                )
//            }
//
//            else -> {
        return checkCertificateExpiry(
            response,
            context,
        )
//            }
//        }
    }

    private fun List<Throwable>.has(type: Class<*>): Boolean =
        any { type.isInstance(it) }

    private inline fun <reified T : Throwable> List<Throwable>.has() = has(T::class.java)

    private fun checkException(
        url: String,
        exception: Exception,
        context: CheckContext,
    ): SiteCheckResult.Failure {

        val c = generateSequence<Throwable>(exception) { it.cause }.toList()


        val certificate = context.certificateChain.firstOrNull()

        if (c.has<CertificateExpiredException>()) {
            return failure(
                SiteProblemType.TLS_CERTIFICATE_EXPIRED,
                buildTlsFailureDetails(
                    url,
                    context,
                    exception,
                    "The server presented a TLS certificate that has expired.",
                ),
                exception,
                context,
            )
        }

        if (c.has<CertificateNotYetValidException>()) {
            return failure(
                SiteProblemType.TLS_CERTIFICATE_NOT_YET_VALID,
                buildTlsFailureDetails(
                    url,
                    context,
                    exception,
                    "The server presented a TLS certificate that is not yet valid.",
                ),
                exception,
                context,
            )
        }

        if (
            c.has<SSLPeerUnverifiedException>() &&
            isHostnameMismatch(exception)
        ) {
            return failure(
                SiteProblemType.TLS_CERTIFICATE_HOSTNAME_MISMATCH,
                buildTlsFailureDetails(
                    url,
                    context,
                    exception,
                    "The TLS certificate presented by the server does not match the requested hostname.",
                ),
                exception,
                context,
            )
        }

        if (c.has<SSLPeerUnverifiedException>()) {
            return failure(
                SiteProblemType.TLS_CERTIFICATE_UNTRUSTED,
                buildTlsFailureDetails(
                    url,
                    context,
                    exception,
                    "The TLS certificate presented by the server could not be verified.",
                ),
                exception,
                context,
            )
        }

        if (
            c.has<SSLHandshakeException>() ||
            c.has<SSLException>()
        ) {
            return failure(
                SiteProblemType.TLS_HANDSHAKE_FAILED,
                buildTlsFailureDetails(
                    url,
                    context,
                    exception,
                    "The TLS handshake could not be completed.",
                ),
                exception,
                context,
            )
        }

        if (c.has<UnknownHostException>()) {
            return failure(
                SiteProblemType.DNS_UNAVAILABLE,
                buildDnsFailureDetails(
                    url,
                    context,
                    exception,
                ),
                exception,
                context,
            )
        }

        if (c.has<SocketTimeoutException>()) {
            val type = when {
                context.tlsStartedAt != null &&
                        context.tlsFinishedAt == null ->
                    SiteProblemType.CONNECTION_TIMEOUT

                context.responseHeadersAt == null &&
                        context.connectFinishedAt != null ->
                    SiteProblemType.READ_TIMEOUT

                else ->
                    SiteProblemType.CONNECTION_TIMEOUT
            }

            return failure(
                type,
                buildConnectionFailureDetails(
                    url,
                    context,
                    exception,
                ),
                exception,
                context,
            )
        }

        if (c.has<ConnectException>()) {
            return failure(
                SiteProblemType.CONNECTION_REFUSED,
                buildConnectionFailureDetails(
                    url,
                    context,
                    exception,
                ),
                exception,
                context,
            )
        }

        if (c.has<NoRouteToHostException>()) {
            return failure(
                SiteProblemType.CONNECTION_FAILED,
                buildConnectionFailureDetails(
                    url,
                    context,
                    exception,
                ),
                exception,
                context,
            )
        }

        if (c.has<SocketException>()) {
            if (
                c.any {
                    it.message
                        ?.contains("Connection reset", ignoreCase = true) == true
                }
            ) {
                return failure(
                    SiteProblemType.CONNECTION_RESET,
                    buildConnectionFailureDetails(
                        url,
                        context,
                        exception,
                    ),
                    exception,
                    context,
                )
            }

            return failure(
                SiteProblemType.CONNECTION_FAILED,
                buildConnectionFailureDetails(
                    url,
                    context,
                    exception,
                ),
                exception,
                context,
            )
        }

        return failure(
            SiteProblemType.CONNECTION_FAILED,
            buildConnectionFailureDetails(
                url,
                context,
                exception,
            ),
            exception,
            context,
        )
    }

    private fun createClient(context: CheckContext): OkHttpClient {
        val baseTrustManager = createTrustManager()

        val recordingTrustManager = RecordingTrustManager(
            delegate = baseTrustManager,
            context = context,
        )

        val sslContext = SSLContext.getInstance("TLS")

        sslContext.init(
            null,
            arrayOf(recordingTrustManager),
            null,
        )

        return OkHttpClient.Builder()
            .sslSocketFactory(
                sslContext.socketFactory,
                recordingTrustManager,
            )
//            .hostnameVerifier { hostname, session ->
//                val verifier = HttpsURLConnection
//                    .getDefaultHostnameVerifier()
//
//                verifier.verify(hostname, session)
//            }
            .followRedirects(true)
            .followSslRedirects(true)
            .eventListener(createEventListener(context))
            .connectTimeout(1.minutes)
            .readTimeout(1.minutes)
            .writeTimeout(1.minutes)
//            .callTimeout(120, TimeUnit.SECONDS) // call means the complete call from DNS resolution, connecting to writing and reading
            .build()
    }

    private fun createTrustManager(): X509TrustManager {
        return Platform.get().platformTrustManager()
    }

    private fun createEventListener(
        context: CheckContext,
    ): EventListener {

        return object : EventListener() {

            override fun dnsStart(
                call: Call,
                domainName: String,
            ) {
                context.dnsStartedAt = System.nanoTime()
            }

            override fun dnsEnd(
                call: Call,
                domainName: String,
                inetAddressList: List<InetAddress>,
            ) {
                context.dnsFinishedAt = System.nanoTime()

                context.dnsAddresses.clear()
                context.dnsAddresses += inetAddressList
            }

            override fun connectStart(
                call: Call,
                inetSocketAddress: InetSocketAddress,
                proxy: Proxy
            ) {
                context.connectStartedAt = System.nanoTime()
                context.connectedAddress = inetSocketAddress
                context.proxy = proxy
            }

            override fun connectEnd(
                call: Call,
                inetSocketAddress: InetSocketAddress,
                proxy: Proxy,
                protocol: Protocol?,
            ) {
                context.connectFinishedAt = System.nanoTime()
                context.protocol = protocol
            }

            override fun secureConnectStart(call: Call) {
                context.tlsStartedAt = System.nanoTime()
            }

            override fun secureConnectEnd(
                call: Call,
                handshake: Handshake?,
            ) {
                context.tlsFinishedAt = System.nanoTime()
                context.handshake = handshake

                if (handshake != null) {
                    context.certificateChain =
                        handshake.peerCertificates
                            .filterIsInstance<X509Certificate>()
                }
            }

            override fun responseHeadersStart(call: Call) {
                context.responseHeadersAt = System.nanoTime()
            }

            override fun callFailed(
                call: Call,
                ioe: IOException,
            ) {
                context.failure = ioe
            }
        }
    }

    private fun failure(
        type: SiteProblemType,
        details: String,
        exception: Throwable? = null,
        context: CheckContext? = null,
    ) = SiteCheckResult.Failure(
        type = type,
        details = details,
        exceptionType = exception?.let { it::class.java.name },
        exceptionMessage = exception?.message,
        remoteAddress = context?.connectedAddress?.let { "${it.address.hostAddress}:${it.port}" },
        techFacts = buildTechFacts(context),
    )

    private fun buildTechFacts(context: CheckContext?): String? {
        if (context == null) return null
        val facts = mutableListOf<String>()
        if (context.dnsAddresses.isNotEmpty()) {
            facts += "DNS: " + context.dnsAddresses.joinToString(", ") { it.hostAddress }
        }
        context.connectedAddress?.let {
            facts += "Remote: ${it.address.hostAddress}:${it.port}"
        }
        context.protocol?.let {
            facts += "Protocol: $it"
        }
        context.handshake?.let {
            facts += "TLS: ${it.tlsVersion}, Cipher: ${it.cipherSuite}"
        }
        if (facts.isEmpty()) return null
        return facts.joinToString("; ")
    }

    private fun isHostnameMismatch(
        exception: Throwable,
    ): Boolean {

        return generateSequence(exception) { it.cause }
            .any { throwable ->
                val message = throwable.message
                    ?.lowercase()
                    ?: return@any false

                message.contains("hostname") &&
                        (
                                message.contains("does not match") ||
                                        message.contains("not verified") ||
                                        message.contains("no name matching") ||
                                        message.contains("doesn't match")
                                )
            }
    }

    private fun buildTlsFailureDetails(
        url: String,
        context: CheckContext,
        exception: Throwable,
        description: String,
    ): String {

        return buildString {

            appendLine(description)
            appendLine()

            appendLine("URL: $url")

            appendNetworkInformation(context)

            context.handshake?.let {
                appendLine("TLS version: ${it.tlsVersion}")
                appendLine("Cipher suite: ${it.cipherSuite}")
            }

            if (context.certificateChain.isNotEmpty()) {
                appendLine()
                appendLine("Certificate chain:")

                context.certificateChain.forEachIndexed { index, certificate ->
                    appendCertificate(
                        index,
                        certificate,
                    )
                }
            }

            appendLine()
            appendException(exception)
            appendTiming(context)
        }.trim()
    }

    private fun StringBuilder.appendCertificate(
        index: Int,
        certificate: X509Certificate,
    ) {
        appendLine("Certificate #$index")
        appendLine("  Subject: ${certificate.subjectX500Principal.name}")
        appendLine("  Issuer: ${certificate.issuerX500Principal.name}")
        appendLine("  Serial number: ${certificate.serialNumber.toString(16)}")
        appendLine("  Valid from: ${certificate.notBefore.toInstant()}")
        appendLine("  Valid until: ${certificate.notAfter.toInstant()}")

        appendLine(
            "  SHA-256: ${certificateSha256(certificate)}"
        )

        val san = certificate.subjectAlternativeNames
            ?.mapNotNull { it.getOrNull(1)?.toString() }
            .orEmpty()

        if (san.isNotEmpty()) {
            appendLine("  Subject alternative names:")

            san.forEach {
                appendLine("    $it")
            }
        }

        appendLine()
    }

    private fun certificateSha256(
        certificate: X509Certificate,
    ): String {

        return MessageDigest
            .getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString(":") {
                "%02X".format(it)
            }
    }

    private fun StringBuilder.appendNetworkInformation(
        context: CheckContext,
    ) {

        if (context.dnsAddresses.isNotEmpty()) {
            appendLine("DNS addresses:")

            context.dnsAddresses.forEach {
                appendLine("  ${it.hostAddress}")
            }
        }

        context.connectedAddress?.let {
            appendLine(
                "Remote address: ${it.address.hostAddress}:${it.port}"
            )
        }

        context.proxy?.let {
            appendLine("Proxy: $it")
        }

        context.protocol?.let {
            appendLine("HTTP protocol: $it")
        }
    }

    private fun StringBuilder.appendException(
        exception: Throwable,
    ) {
        appendLine("Exception:")
        appendLine("  ${exception::class.java.name}")

        exception.message?.let {
            appendLine("  Message: $it")
        }

        val causes = generateSequence(exception) { it.cause }
            .drop(1)
            .toList()

        if (causes.isNotEmpty()) {
            appendLine("Cause chain:")

            causes.forEachIndexed { index, cause ->
                appendLine(
                    "  ${index + 1}. ${cause::class.java.name}"
                )

                cause.message?.let {
                    appendLine("     Message: $it")
                }
            }
        }
    }

    private fun StringBuilder.appendTiming(
        context: CheckContext,
    ) {

        fun elapsed(
            start: Long?,
            end: Long?,
        ): String? {
            if (start == null || end == null) {
                return null
            }

            return "%.2f ms".format(
                (end - start) / 1_000_000.0
            )
        }

        val dns = elapsed(
            context.dnsStartedAt,
            context.dnsFinishedAt,
        )

        val connect = elapsed(
            context.connectStartedAt,
            context.connectFinishedAt,
        )

        val tls = elapsed(
            context.tlsStartedAt,
            context.tlsFinishedAt,
        )

        if (dns != null || connect != null || tls != null) {
            appendLine()
            appendLine("Timing:")

            dns?.let {
                appendLine("  DNS: $it")
            }

            connect?.let {
                appendLine("  Connection: $it")
            }

            tls?.let {
                appendLine("  TLS: $it")
            }
        }
    }

    private fun buildDnsFailureDetails(
        url: String,
        context: CheckContext,
        exception: Throwable,
    ): String {

        return buildString {
            appendLine(
                "The hostname could not be resolved to an IP address."
            )
            appendLine()

            appendLine("URL: $url")

            if (context.dnsAddresses.isNotEmpty()) {
                appendLine("Resolved addresses:")

                context.dnsAddresses.forEach {
                    appendLine("  ${it.hostAddress}")
                }
            } else {
                appendLine("Resolved addresses: none")
            }

            appendLine()
            appendException(exception)
            appendTiming(context)
        }.trim()
    }

    private fun buildConnectionFailureDetails(
        url: String,
        context: CheckContext,
        exception: Throwable,
    ): String {

        return buildString {

            appendLine(
                "The connection to the server could not be established."
            )
            appendLine()

            appendLine("URL: $url")

            appendNetworkInformation(context)

            appendLine()
            appendException(exception)

            appendTiming(context)
        }.trim()
    }

    private fun checkCertificateExpiry(
        response: Response,
        context: CheckContext,
    ): SiteCheckResult {

        val certificate = response.handshake
            ?.peerCertificates
            ?.filterIsInstance<X509Certificate>()
            ?.firstOrNull()
            ?: return SiteCheckResult.Success

        val now = Instant.now()
        val expiry = certificate.notAfter.toInstant()

        if (expiry.isBefore(now)) {
            return failure(
                SiteProblemType.TLS_CERTIFICATE_EXPIRED,
                buildCertificateExpiryDetails(
                    response,
                    certificate,
                ),
                context = context,
            )
        }

        if (
            expiry.isBefore(
                now.plus(30, ChronoUnit.DAYS)
            )
        ) {
            return failure(
                SiteProblemType.TLS_CERTIFICATE_EXPIRING,
                buildCertificateExpiryDetails(
                    response,
                    certificate,
                ),
                context = context,
            )
        }

        return SiteCheckResult.Success
    }

    private fun buildCertificateExpiryDetails(
        response: Response,
        certificate: X509Certificate,
    ): String {

        val expiry = certificate.notAfter.toInstant()
        val remaining = ChronoUnit.DAYS.between(
            Instant.now(),
            expiry,
        )

        return buildString {
            appendLine(
                if (expiry.isBefore(Instant.now())) {
                    "The server certificate has expired."
                } else {
                    "The server certificate is approaching its expiration date."
                }
            )

            appendLine()

            appendLine("URL: ${response.request.url}")
            appendLine("TLS version: ${response.handshake?.tlsVersion}")
            appendLine("Cipher suite: ${response.handshake?.cipherSuite}")

            appendLine()
            appendLine("Certificate:")
            appendLine("  Subject: ${certificate.subjectX500Principal.name}")
            appendLine("  Issuer: ${certificate.issuerX500Principal.name}")
            appendLine("  Valid from: ${certificate.notBefore.toInstant()}")
            appendLine("  Valid until: $expiry")
            appendLine("  Remaining validity: $remaining days")
            appendLine("  SHA-256: ${certificateSha256(certificate)}")

            val san = certificate.subjectAlternativeNames
                ?.mapNotNull { it.getOrNull(1)?.toString() }
                .orEmpty()

            if (san.isNotEmpty()) {
                appendLine("  Subject alternative names:")

                san.forEach {
                    appendLine("    $it")
                }
            }
        }.trim()
    }

    private fun buildHttpDetails(
        response: Response,
        description: String,
    ): String {

        return buildString {

            appendLine(description)
            appendLine()

            appendLine("URL: ${response.request.url}")
            appendLine("HTTP status: ${response.code}")
            appendLine("HTTP protocol: ${response.protocol}")

            response.handshake?.let {
                appendLine("TLS version: ${it.tlsVersion}")
                appendLine("Cipher suite: ${it.cipherSuite}")
            }

            response.header("Server")?.let {
                appendLine("Server: $it")
            }

            response.header("Content-Type")?.let {
                appendLine("Content-Type: $it")
            }

            response.header("Location")?.let {
                appendLine("Location: $it")
            }

            appendLine()
            appendLine("Response headers:")

            response.headers.forEach { header ->
                appendLine("  ${header.first}: ${header.second}")
            }
        }.trim()
    }

    private fun redirectChain(
        response: Response,
    ): List<Response> {

        val result = mutableListOf<Response>()

        var current: Response? = response

        while (current != null) {
            result += current
            current = current.priorResponse
        }

        return result.asReversed()
    }

    private fun buildRedirectDetails(
        responses: List<Response>,
    ): String {

        return buildString {

            appendLine(
                "The request exceeded the maximum allowed number of redirects."
            )
            appendLine()

            appendLine("Redirect chain:")

            responses.forEachIndexed { index, response ->

                appendLine(
                    "  ${index + 1}. " +
                            "${response.code} " +
                            "${response.request.url}"
                )

                response.header("Location")?.let {
                    appendLine("     Location: $it")
                }
            }
        }.trim()
    }
}