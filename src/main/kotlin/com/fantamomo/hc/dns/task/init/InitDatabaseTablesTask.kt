package com.fantamomo.hc.dns.task.init

import com.fantamomo.hc.dns.db.*
import com.fantamomo.hc.dns.manager.DatabaseManager
import com.fantamomo.hc.dns.task.InitTask
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils

object InitDatabaseTablesTask : InitTask(
    "init-database-tables",
    ConnectDatabaseTask,
    shortDescription = "Initialize database tables",
    longDescription = "Creates the necessary database tables if they do not already exist"
) {
    override fun disableLogAndStateSetting() = false

    override suspend fun run() {
        logger.info("Initializing database tables")
        try {
            DatabaseManager.transaction {
                SchemaUtils.create(
                    UserTable,
                    ForkTable,
                    CommitTable,
                    CommitParentsTable,
                    RecordTable,
                    RecordTimelineTable,
                    ForkProposalTable,
                    ForkProposalTimelineTable
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to initialize database tables", e)
            markFailed()
        }
    }
}