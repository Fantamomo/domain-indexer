package com.fantamomo.hc.dns.task.init

import com.fantamomo.hc.dns.manager.DatabaseManager
import com.fantamomo.hc.dns.task.InitTask
import kotlin.time.measureTime

object ConnectDatabaseTask : InitTask(
    "connect-database",
    shortDescription = "Connect to the database",
    longDescription = "Attempts to establish a connection to the database"
) {
    override fun disableLogAndStateSetting() = false

    override suspend fun run() {
        logger.info("Initializing database")
        try {
            val duration = measureTime {
                DatabaseManager.connect()
            }
            logger.info("Database initialized in $duration")
        } catch (e: Exception) {
            logger.error("Failed to initialize database", e)
            markFailed()
        }
    }
}