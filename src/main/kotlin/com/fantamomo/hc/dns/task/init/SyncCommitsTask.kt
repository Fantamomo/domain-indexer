package com.fantamomo.hc.dns.task.init

import com.fantamomo.hc.dns.service.SyncCommitService
import com.fantamomo.hc.dns.task.InitTask

object SyncCommitsTask : InitTask(
    "sync-commits",
    InitForksTask,
    shortDescription = "Sync commits",
    longDescription = "Syncs the commits in the git repository with the database"
) {
    override suspend fun run() {
        try {
            SyncCommitService.sync()
        } catch (e: Exception) {
            markFailed()
            if (e::class != Exception::class) {
                logger.error("Unexpected exception while syncing commits", e)
            }
        }
    }
}