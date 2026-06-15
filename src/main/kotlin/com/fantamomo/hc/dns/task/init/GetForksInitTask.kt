package com.fantamomo.hc.dns.task.init

import com.fantamomo.hc.dns.service.GetForksService
import com.fantamomo.hc.dns.task.InitTask
import com.fantamomo.hc.dns.util.humanReadable
import io.ktor.client.plugins.*
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

object GetForksInitTask : InitTask(
    "fetch-forks",
    shortDescription = "Fetches the list of all forks from GitHub",
    longDescription = "Retrieves the list of all forks from the hackclub/dns repository on GitHub"
) {
    lateinit var forks: List<GetForksService.FetchedFork>
        private set

    override fun disableLogAndStateSetting() = false

    override suspend fun run() {
        logger.info("Fetching forks")
        var timeoutErrors = 0
        var retried = false
        val (respond, duration) = measureTimedValue {
            while (true) {
                try {
                    // we need to ignore the cache here because it could happen that one of the requests fails
                    // with an HttpRequestTimeoutException, if we try again with the cache enabled,
                    // we would make a second request, in this case the service would return that the cache is not modified
                    // but due the failed request in the first place no cache is available, so we would end up with a failed task without any retries
                    return@measureTimedValue GetForksService.getForks(ignoreCache = true)
                } catch (e: HttpRequestTimeoutException) {
                    if (timeoutErrors > 5) {
                        logger.error("Failed to fetch forks after 5 retries", e)
                        markFailed()
                        return
                    }
                    logger.warn("Request timed out, retrying in 10 seconds")
                    timeoutErrors++
                    delay(10.seconds)
                    retried = true
                    logger.info("Retrying...")
                } catch (e: Exception) {
                    logger.error("Unexpected exception while fetching forks", e)
                    markFailed()
                    return
                }
            }
            // yeah it is unreachable, but the compiler thinks the type of respond is Any if we don't put this here
            @Suppress("KotlinUnreachableCode")
            throw IllegalStateException("Unreachable")
        }
        if (retried) logger.info("Successfully retried fetching forks")
        when (respond.status) {
            GetForksService.Status.NOT_MODIFIED -> {
                logger.warn("Forks not modified since last fetch. That shouldn't be a fetch before this call. Maybe an scheduled has been miss configured?")
                forks = GetForksService.getCachedForks()
                return
            }

            GetForksService.Status.FAILED -> {
                logger.error("Failed to fetch forks")
                markFailed()
            }

            GetForksService.Status.SUCCESS -> {
                logger.info("Fetched ${respond.forks.size} forks in ${duration.humanReadable()}")
                forks = respond.forks
            }
        }
    }
}