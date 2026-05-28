package com.fantamomo.hc.dns.task.init

import com.fantamomo.hc.dns.service.UserService
import com.fantamomo.hc.dns.task.InitTask

object UpdateUsersTask : InitTask(
    "update-users",
    GetForksInitTask, InitDatabaseTablesTask,
    shortDescription = "Update users",
    longDescription = "Updates the user information in the database based on the users associated with the fetched forks"
) {
    override suspend fun run() {
        try {
            UserService.updateUsers(GetForksInitTask.forks.map { it.user })
        } catch (e: Exception) {
            logger.error("Failed to update users while fetching forks", e)
            markFailed()
        }
    }
}