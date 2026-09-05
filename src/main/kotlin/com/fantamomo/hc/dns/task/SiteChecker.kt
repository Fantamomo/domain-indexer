package com.fantamomo.hc.dns.task

import com.fantamomo.hc.dns.App
import com.fantamomo.hc.dns.data.Config
import com.fantamomo.hc.dns.data.SharedConstants
import com.fantamomo.hc.dns.db.RecordTable
import com.fantamomo.hc.dns.db.SiteCheckerIgnoreListTable
import com.fantamomo.hc.dns.manager.DatabaseManager
import com.fantamomo.hc.dns.model.SiteCheckResult
import com.fantamomo.hc.dns.model.SiteProblemType
import com.fantamomo.hc.dns.model.dns.RecordState
import com.fantamomo.hc.dns.model.dns.RecordType
import com.fantamomo.hc.dns.util.DISABLE_GITHUB_HELPER_PLUGIN
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.r2dbc.select
import org.slf4j.LoggerFactory
import java.util.concurrent.Semaphore
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
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
    )

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
        if (!running.compareAndSet(false, true)) {
            throw IllegalStateException("Site checker is already running")
        }

        logger.info("Site checker started")

        delay(TIME_BETWEEN_RUNS)

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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("An exception occurred while checking sites", e)
            }

            delay(TIME_BETWEEN_RUNS)
        }
    }

    private suspend fun checkSites() = coroutineScope {
        val sites = DatabaseManager.transaction {
            RecordTable
                .select(
                    RecordTable.host,
                    RecordTable.name,
                    RecordTable.currentValue,
                )
                .where {
                    RecordTable.type inList RecordType.RESOLVABLE and
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
                    )
                }
                .toList()
        }

        if (sites.isEmpty()) {
            return@coroutineScope
        }

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
            async(Dispatchers.IO) {
                limiter.awaitRateLimit()

                limiter.semaphore.acquire()
                try {
                    while (!globalSemaphore.tryAcquire()) {
                        currentCoroutineContext().ensureActive()
                        delay(GLOBAL_RETRY_DELAY)
                    }

                    try {
                        @Suppress("HttpUrlsUsage")
                        checkSite(
                            Url("http://${site.host}/")
                        )
                    } finally {
                        globalSemaphore.release()
                    }
                } finally {
                    limiter.semaphore.release()
                }
            }
        }.awaitAll()
    }

    private suspend fun checkSite(url: Url): SiteCheckResult {
        try {
            val response = SharedConstants.client.get(url) {
                attributes.put(DISABLE_GITHUB_HELPER_PLUGIN, true)

                header(
                    HttpHeaders.UserAgent,
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
                )
                header(
                    HttpHeaders.Accept,
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
                )
                header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.5")
                header(HttpHeaders.AcceptEncoding, "gzip, deflate")
            }
            return checkResponse(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val result = checkException(url, e)
            if (result != null) {
                return result
            }

            logger.error("An exception occurred while checking site $url", e)
            // well, it is a failure, but an unexpected one, so we just log it and pretend like nothing happened
            return SiteCheckResult.Success
        }
    }

    private fun checkException(url: Url, e: Exception): SiteCheckResult.Failure? {

        return null
    }

    @Suppress("UastIncorrectHttpHeaderInspection")
    private suspend fun checkResponse(response: HttpResponse): SiteCheckResult {
        val server = response.headers[HttpHeaders.Server]?.lowercase()

        val text = response.bodyAsText()

        if (server == "vercel" &&
            response.headers["x-vercel-error"] == "DEPLOYMENT_NOT_FOUND" &&
            response.status.value == 404 &&
            text.contains("https://vercel.com/docs/errors/DEPLOYMENT_NOT_FOUND")
        ) {
            // the response looks like a vercel deployment not found page (e.g., https://not-existing-site.vercel-dns.com)
            return SiteCheckResult.Failure(
                type = SiteProblemType.SERVICE_NOT_FOUND,
                details = "The record redirects to a Vercel site, but Vercel could not find the deployment"
            )
        }

        return SiteCheckResult.Success
    }
}