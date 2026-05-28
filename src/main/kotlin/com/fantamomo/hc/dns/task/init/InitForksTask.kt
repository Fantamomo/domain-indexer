package com.fantamomo.hc.dns.task.init

import com.fantamomo.hc.dns.service.SyncForksService
import com.fantamomo.hc.dns.task.InitTask

object InitForksTask : InitTask(
    "init-forks",
    InitRepoTask, GetForksInitTask, UpdateUsersTask,
    shortDescription = "Initialize forks",
    longDescription = "Add all forks as remotes to the git repository and fetch them"
) {

    override fun disableLogAndStateSetting() = false

    override suspend fun run() {
        try {
            val forks = GetForksInitTask.forks
            if (forks.isEmpty()) {
                logger.info("No forks fetched, skipping init")
                return
            }
            try {
                SyncForksService.syncForks()
            } catch (e: Exception) {
                logger.error("Unexpected exception while syncing forks", e)
                markFailed()
                return
            }
            logger.info("Initializing ${forks.size} forks")
        } catch (e: Exception) {
            logger.error("Unexpected exception while initializing forks", e)
            markFailed()
        }
    }
}