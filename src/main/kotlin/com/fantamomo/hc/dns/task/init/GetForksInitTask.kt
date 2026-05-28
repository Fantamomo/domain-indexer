package com.fantamomo.hc.dns.task.init

import com.fantamomo.hc.dns.service.GetForksService
import com.fantamomo.hc.dns.task.InitTask
import com.fantamomo.hc.dns.util.humanReadable
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
        val (respond, duration) = measureTimedValue {
            try {
                GetForksService.getForks()
            } catch (e: Exception) {
                logger.error("Unexpected exception while fetching forks", e)
                markFailed()
                return
            }
        }
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