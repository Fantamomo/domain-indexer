package com.fantamomo.hc.dns.task.init

import com.fantamomo.hc.dns.data.SharedValues
import com.fantamomo.hc.dns.service.SyncForksService
import com.fantamomo.hc.dns.task.InitTask
import com.fantamomo.hc.dns.util.KGitCommitGraphAnalyzer

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
            val commitsBeforeFetch = SharedValues.commitGraphAnalyzer.hashes().size
            try {
                SyncForksService.syncForks()
            } catch (e: Exception) {
                logger.error("Unexpected exception while syncing forks", e)
                markFailed()
                return
            }
            logger.info("Initializing ${forks.size} forks")
            val commitsAfterFetch = SharedValues.git
                .log { all() }
                .toList()
            if (commitsAfterFetch.size != commitsBeforeFetch) {
                logger.info("Need to recalculate commit graph analyzer because the number of commits changed from $commitsBeforeFetch to ${commitsAfterFetch.size}")
                SharedValues.commitGraphAnalyzer = KGitCommitGraphAnalyzer(commitsAfterFetch) // optimisation
            }
        } catch (e: Exception) {
            logger.error("Unexpected exception while initializing forks", e)
            markFailed()
        }
    }
}