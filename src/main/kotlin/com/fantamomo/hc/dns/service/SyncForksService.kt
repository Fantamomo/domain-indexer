package com.fantamomo.hc.dns.service

import com.fantamomo.hc.dns.data.SharedValues
import com.fantamomo.hc.dns.db.ForkTable
import com.fantamomo.hc.dns.manager.DatabaseManager
import com.fantamomo.hc.dns.model.Fork
import com.fantamomo.hc.dns.util.RepositoriesToIgnore
import com.fantamomo.hc.dns.util.humanReadable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.eclipse.jgit.internal.storage.file.LockFile
import org.eclipse.jgit.transport.URIish
import org.jetbrains.exposed.v1.r2dbc.batchUpsert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

object SyncForksService {
    private val logger = LoggerFactory.getLogger(SyncForksService::class.java)

    private val thirtySeconds = 30.seconds

    private const val SAVE_CHUNK_SIZE = 50

    suspend fun getForksIdInDb(): List<Fork> = DatabaseManager.transaction {
        ForkTable
            .selectAll()
//            .where { ForkTable.deleted eq false }
            .map {
                Fork(
                    it[ForkTable.id],
                    it[ForkTable.userId],
                    it[ForkTable.name],
                    it[ForkTable.createdAt],
                    it[ForkTable.lastUpdatedAt],
                    it[ForkTable.pushedAt],
                    it[ForkTable.deleted]
                )
            }
            .toList()
    }

    private suspend fun saveForksToDb(forks: List<GetForksService.FetchedFork>) {
        if (forks.isEmpty()) return

        DatabaseManager.transaction {
            ForkTable.batchUpsert(forks) {
                this[ForkTable.id] = it.fork.id
                this[ForkTable.name] = it.fork.name
                this[ForkTable.userId] = it.fork.userId
                this[ForkTable.createdAt] = it.fork.createdAt
                this[ForkTable.pushedAt] = it.fork.pushedAt
                this[ForkTable.lastUpdatedAt] = it.fork.lastUpdatedAt
                this[ForkTable.deleted] = false
            }
        }
    }

    private suspend fun saveDeletedForksToDb(forks: List<Fork>) {
        if (forks.isEmpty()) return

        DatabaseManager.transaction {
            ForkTable.batchUpsert(forks) {
                this[ForkTable.id] = it.id
                this[ForkTable.name] = it.name
                this[ForkTable.userId] = it.userId
                this[ForkTable.createdAt] = it.createdAt
                this[ForkTable.pushedAt] = it.pushedAt
                this[ForkTable.lastUpdatedAt] = it.lastUpdatedAt
                this[ForkTable.deleted] = true
            }
        }
    }

    suspend fun syncForks() = coroutineScope {
        val git = SharedValues.git
        val forks = GetForksService.getCachedForks()

        if (forks.isEmpty()) {
            logger.info("No forks fetched, skipping sync")
            return@coroutineScope
        }

        val forksIds = forks.mapTo(mutableSetOf()) { it.fork.id }

        val dbForks = getForksIdInDb()
        val dbForksIds = dbForks.associateBy { it.id }
        logger.info("Found ${dbForks.size} forks in db")

        val newForks = forks.filter { it.fork.id !in dbForksIds && RepositoriesToIgnore.canIndex(it.fork.id) }

        val updatedForks = forks.filter {
            val dbFork = dbForksIds[it.fork.id]

            dbFork != null &&
                    RepositoriesToIgnore.canIndex(it.fork.id) &&
                    (
                            dbFork.pushedAt != it.fork.pushedAt ||
                                    dbFork.lastUpdatedAt != it.fork.lastUpdatedAt
                            )
        }

        if (updatedForks.isEmpty() && newForks.isEmpty()) {
            logger.info("All forks are up to date, skipping sync")
            return@coroutineScope
        }

        val fetchBecausePush = updatedForks.count { it.fork.pushedAt != dbForksIds[it.fork.id]?.pushedAt }
        val fetchBecauseUpdate = updatedForks.count { it.fork.lastUpdatedAt != dbForksIds[it.fork.id]?.lastUpdatedAt }
        val fetchBecauseBoth =
            updatedForks.count { it.fork.pushedAt != dbForksIds[it.fork.id]?.pushedAt && it.fork.lastUpdatedAt != dbForksIds[it.fork.id]?.lastUpdatedAt }
        val fetchBecauseNew = newForks.size


        logger.info("Fetching $fetchBecausePush forks because of pushedAt, $fetchBecauseUpdate because of lastUpdatedAt, $fetchBecauseBoth because of both, $fetchBecauseNew because they are new")

        val deletedForks = dbForks.filter { fork ->
            fork.id !in forksIds
        }

        logger.info("Found ${deletedForks.size} forks to mark as deleted")

        if (dbForks.isEmpty()) {
            logger.info("No forks in db, running full sync")
        } else {
            logger.info(
                "Running incremental sync of ${newForks.size + updatedForks.size} forks"
            )
        }
        if (newForks.isNotEmpty()) {
            var remoteAddedSuccessfully = 0

            logger.info("Adding ${newForks.size} remotes")

            val addDuration = measureTime {
                for (fork in newForks) {
                    try {
                        git.remoteAdd {
                            setName("fork/${fork.fork.userId}/${fork.fork.id}")
                            setUri(URIish("https://github.com/${fork.combinedName}.git"))
                        }

                        remoteAddedSuccessfully++
                    } catch (e: Exception) {
                        logger.error(
                            "Failed to add remote for fork ${fork.combinedName}",
                            e
                        )
                    }
                }
            }

            logger.info(
                "Added $remoteAddedSuccessfully/${newForks.size} remotes " +
                        "in ${addDuration.humanReadable()}"
            )
        } else {
            logger.info("No new forks found, skipping remote adding")
        }

        val forksToFetch = newForks + updatedForks
        val totalToFetch = forksToFetch.size

        var successFetch = 0
        var skippedAuth = 0
        var failedFetch = 0
        var countFetch = 0

        var totalWaitTime = Duration.ZERO

        val fetchedSuccessfully = mutableListOf<GetForksService.FetchedFork>()

        val logFile = File(git.repository.commonDirectory, "gc.log")
        val lock = LockFile(logFile)

        // we acquire the lock on the gc.log file so that the gc thinks it is already running
        // and doesn't try to run again.
        // we need to do this because after every fetch the system runs gc, that is normally not a problem,
        // but sometimes it is, so we need to prevent it from running.
        if (!lock.lock()) {
            logger.error("Failed to acquire lock on gc.log")
            throw IllegalStateException("Failed to acquire lock on gc.log")
        }

        val fetchDuration = try {
            measureTime {
                for (fork in forksToFetch) {
                    countFetch++

                    try {
                        logger.info(
                            "Fetching ${fork.combinedName} " +
                                    "($countFetch/$totalToFetch)"
                        )

                        git.fetch {
                            remote = "fork/${fork.fork.userId}/${fork.fork.id}"
                            isForceUpdate = true
                        }

                        successFetch++

                        fetchedSuccessfully += fork

                        if (fetchedSuccessfully.size >= SAVE_CHUNK_SIZE) {
                            val successfully = fetchedSuccessfully.toList()
                            launch {
                                try {
                                    logger.info("Saving fetched forks chunk to db")
                                    saveForksToDb(successfully)

                                    logger.info(
                                        "Saved ${successfully.size} fetched forks to db"
                                    )
                                } catch (e: Exception) {
                                    logger.error(
                                        "Failed to save fetched forks chunk to db",
                                        e
                                    )
                                }
                            }
                            fetchedSuccessfully.clear()
                        }
                    } catch (e: Exception) {
                        val msg = e.message ?: ""

                        if (msg.contains("no CredentialsProvider")) {
                            skippedAuth++

                            logger.warn("Auth required: ${fork.combinedName}")
                        } else {
                            failedFetch++

                            logger.error(
                                "Failed to fetch fork ${fork.combinedName}",
                                e
                            )
                        }
                    }

                    if (countFetch % 100 == 0) {
                        logger.info("Running gc and minium waiting for ${thirtySeconds.humanReadable()} to avoid rate limiting")

                        val duration = measureTime {
                            try {
                                git.gc()
                            } catch (e: Exception) {
                                logger.error("Failed to run gc", e)
                            }
                        }
                        val timeLeft = if (duration > thirtySeconds) Duration.ZERO else thirtySeconds - duration
                        if (timeLeft == Duration.ZERO) {
                            logger.info("Garbage collecting took ${duration.humanReadable()}, no need to wait")
                        } else {
                            logger.info("Garbage collected in ${duration.humanReadable()}, still waiting for ${timeLeft.humanReadable()}")
                        }

                        delay(timeLeft)

                        totalWaitTime += thirtySeconds
                    } else if (countFetch % 25 == 0) {
                        logger.info("Delaying for 10 seconds to avoid rate limiting")

                        delay(10.seconds)

                        totalWaitTime += 10.seconds
                    }
                }

                if (fetchedSuccessfully.isNotEmpty()) {
                    try {
                        saveForksToDb(fetchedSuccessfully)

                        logger.info(
                            "Saved remaining ${fetchedSuccessfully.size} fetched forks to db"
                        )

                        fetchedSuccessfully.clear()
                    } catch (e: Exception) {
                        logger.error(
                            "Failed to save remaining fetched forks to db",
                            e
                        )
                    }
                }

                if (deletedForks.isNotEmpty()) {
                    try {
                        saveDeletedForksToDb(deletedForks)

                        logger.info(
                            "Marked ${deletedForks.size} forks as deleted"
                        )
                    } catch (e: Exception) {
                        logger.error(
                            "Failed to mark deleted forks in db",
                            e
                        )
                    }
                }
            }
        } finally {
            // we release the lock on the gc.log file so that the gc can run again.
            lock.unlock()
        }

        logger.info(
            "Fetched $successFetch/$totalToFetch successfully " +
                    "(skipped $skippedAuth due to auth, failed $failedFetch) " +
                    "in ${fetchDuration.humanReadable()} " +
                    "(waited ${totalWaitTime.humanReadable()} for rate limiting)"
        )

        logger.info("Running garbage collection")
        try {
            // because we disabled the gc for all the fetched above, we need to run it manually.
            val duration = measureTime {
                git.gc()
            }
            logger.info("Garbage collected in ${duration.humanReadable()}")
        } catch (e: Exception) {
            logger.error("Failed to run garbage collection", e)
        }
    }
}