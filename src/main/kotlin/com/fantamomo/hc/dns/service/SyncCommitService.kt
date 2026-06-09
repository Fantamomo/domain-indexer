package com.fantamomo.hc.dns.service

import com.fantamomo.hc.dns.data.SharedValues
import com.fantamomo.hc.dns.db.CommitParentsTable
import com.fantamomo.hc.dns.db.CommitTable
import com.fantamomo.hc.dns.db.UserTable
import com.fantamomo.hc.dns.manager.DatabaseManager
import com.fantamomo.hc.dns.model.Commit
import com.fantamomo.hc.dns.model.User
import com.fantamomo.hc.dns.util.humanReadable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.eclipse.jgit.revwalk.RevCommit
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.batchUpsert
import org.jetbrains.exposed.v1.r2dbc.select
import org.slf4j.LoggerFactory
import kotlin.time.measureTime
import kotlin.time.toKotlinInstant

object SyncCommitService {
    private val logger = LoggerFactory.getLogger(SyncCommitService::class.java)

    suspend fun getAllCommitsIds(): List<String> = DatabaseManager.transaction {
        CommitTable.select(CommitTable.id)
            .map { it[CommitTable.id] }
            .toList()
    }

    suspend fun sync(): Boolean {
        logger.info("Syncing commits...")
        val commitIds = getAllCommitsIds()
        logger.info("Found ${commitIds.size} commits in db")
        val users = UserService.getAllUsers().associateBy { it.username }
        val commits = SharedValues.git.log {
            all()
        }.toList()
        logger.info("Found ${commits.size} commits in repo")
        val newCommitsRev = commits.filterNot { it.name in commitIds }
        if (newCommitsRev.isEmpty()) {
            logger.info("No new commits found, skipping sync")
            return false
        }
        logger.info("Found ${newCommitsRev.size} new commits")
        val newCommits = newCommitsRev.associateWith { it.toCommit(users) }
        try {
            val duration = measureTime {
                DatabaseManager.transaction {
                    CommitTable.batchInsert(newCommits.values) {
                        this[CommitTable.id] = it.id
                        this[CommitTable.message] = it.message
                        this[CommitTable.author] = it.author
                        this[CommitTable.commiter] = it.commiter
                        this[CommitTable.parentsCount] = it.parents.size
                        this[CommitTable.createdAt] = it.createdAt.toEpochMilliseconds()
                        this[CommitTable.commitedAt] = it.commitedAt.toEpochMilliseconds()
                    }
                }
            }
            logger.info("Inserted ${newCommits.size} commits in ${duration.humanReadable()}")
        } catch (e: Exception) {
            logger.error("Failed to insert commits", e)
            throw Exception("Failed to insert commits", e)
        }
        val parentsToInsert = newCommits.values.associateWith { it.parents }.flattenToPairs()
        logger.info("Inserting ${parentsToInsert.size} commit parents")
        try {
            val duration = measureTime {
                DatabaseManager.transaction {
                    CommitParentsTable.batchInsert(parentsToInsert, ignore = true) { pair ->
                        this[CommitParentsTable.commit] = pair.first.id
                        this[CommitParentsTable.parent] = pair.second
                    }
                }
            }
            logger.info("Inserted ${parentsToInsert.size} commit parents in ${duration.humanReadable()}")
        } catch (e: Exception) {
            logger.error("Failed to insert commit parents", e)
            throw Exception("Failed to insert commits", e)
        }

        try {
            logger.info("Updating user email values")
            val duration = measureTime {
                val old = UserService.getAllUsers().associateBy { it.id }
                val authorUpdate = newCommits.filter { it.value.author != null }
                val commiterUpdate = newCommits.filter { it.value.commiter != null }
                DatabaseManager.transaction {
                    UserTable.batchUpsert(
                        authorUpdate.entries,
                    ) { entry ->
                        val userId = entry.value.author!!
                        val oldUser = old[userId]
                        this[UserTable.id] = userId
                        this[UserTable.username] = oldUser?.username ?: entry.key.authorIdent.name
                        this[UserTable.type] = oldUser?.type!!
                        this[UserTable.email] = entry.key.authorIdent.emailAddress
                    }
                    UserTable.batchUpsert(
                        commiterUpdate.entries
                    ) { entry ->
                        val userId = entry.value.commiter!!
                        val oldUser = old[userId]
                        this[UserTable.id] = userId
                        this[UserTable.username] = oldUser?.username ?: entry.key.authorIdent.name
                        this[UserTable.type] = oldUser?.type!!
                        this[UserTable.email] = entry.key.committerIdent.emailAddress
                    }
                }
            }
            logger.info("Updated user email values in ${duration.humanReadable()}")
        } catch (e: Exception) {
            logger.error("Failed to update user email values", e)
        }
        return true
    }

    private fun <K, V> Map<K, List<V>>.flattenToPairs(): List<Pair<K, V>> {
        return flatMap { (key, values) ->
            values.map { value -> key to value }
        }
    }

    private fun RevCommit.toCommit(users: Map<String, User>): Commit {
        return Commit(
            id = this.id.name,
            message = this.fullMessage,
            author = users[this.authorIdent.name]?.id,
            commiter = users[this.committerIdent.name]?.id,
            parents = this.parents.map { it.name },
            createdAt = this.authorIdent.whenAsInstant.toKotlinInstant(),
            commitedAt = this.committerIdent.whenAsInstant.toKotlinInstant()
        )
    }
}
