package com.fantamomo.hc.dns.service

import com.fantamomo.hc.dns.data.SharedConstants
import com.fantamomo.hc.dns.data.SharedValues
import com.fantamomo.hc.dns.db.CommitParentsTable
import com.fantamomo.hc.dns.db.CommitTable
import com.fantamomo.hc.dns.db.UserTable
import com.fantamomo.hc.dns.manager.DatabaseManager
import com.fantamomo.hc.dns.model.Commit
import com.fantamomo.hc.dns.util.humanReadable
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.associate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import org.eclipse.jgit.revwalk.RevCommit
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlin.time.toKotlinInstant

object SyncCommitService {
    private val logger = LoggerFactory.getLogger(SyncCommitService::class.java)

    private const val LIST_COMMITS_BASE_URL = "https://api.github.com/repos/hackclub/dns/commits"

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
            val count = DatabaseManager.transaction {
                CommitTable.select(CommitTable.requestedFromGitHub)
                    .where { CommitTable.requestedFromGitHub eq false }
                    .count()
            }
            if (count > 0) {
                logger.info("Connecting commits with author/commiter")
                try {
                    val duration = measureTime {
                        connectWithAuthorCommiter()
                    }
                    logger.info("Connected commits with author/commiter in ${duration.humanReadable()}")
                } catch (e: Exception) {
                    logger.error("Failed to connect commits with author/commiter", e)
                }
                logger.info("Syncing commits author/commiter completed")
                return false
            }
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
                        this[CommitTable.requestedFromGitHub] = false
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

        logger.info("Connecting commits with author/commiter")
        try {
            val duration = measureTime {
                connectWithAuthorCommiter()
            }
            logger.info("Connected commits with author/commiter in ${duration.humanReadable()}")
        } catch (e: Exception) {
            logger.error("Failed to connect commits with author/commiter", e)
        }

        logger.info("Syncing commits completed")
        return true
    }

    // we are requesting all commits that we know from github so that we can access the real author/commiter,
    // then we connect those infos in our commit table
    private suspend fun connectWithAuthorCommiter() {

        val pending = DatabaseManager.transaction {
            CommitTable
                .select(CommitTable.id)
                .where { CommitTable.requestedFromGitHub eq false }
                .map { it[CommitTable.id] }
                .toList()
                .toMutableSet()
        }

        if (pending.isEmpty()) return

        logger.info("Found ${pending.size} commits to connect with author/commiter")

        val parentMap = DatabaseManager.transaction {
            CommitParentsTable
                .select(
                    CommitParentsTable.commit,
                    CommitParentsTable.parent
                )
                .where {
                    CommitParentsTable.commit inList pending
                }
                .associate {
                    it[CommitParentsTable.commit] to
                            it[CommitParentsTable.parent]
                }
        }

        val parents = parentMap.values.toSet()

        val leaves = pending.filter {
            it !in parents
        }

        logger.info("Found ${leaves.size} points to start requesting commit lists")

        val buffer = mutableListOf<RespondCommit>()

        var totalSavedCommits = 0
        var requestCount = 0
        var lastRequestCountOnSave = 0

        var waitingTime = Duration.ZERO
        var databaseTime = Duration.ZERO

        val duration = measureTime {
            for (leaf in leaves) {

                try {
                    var page = 1

                    while (true) {

                        requestCount++
                        val commits = requestCommits(
                            start = leaf,
                            perPage = 100,
                            page = page
                        )

                        if (commits.isEmpty())
                            break

                        var stop = false

                        for (c in commits) {

                            if (!pending.remove(c.sha)) {
                                stop = true
                                break
                            }

                            buffer += c
                        }

                        if (stop) break

                        page++
                    }
                } catch (e: Exception) {
                    logger.error("Failed to request commits for sha $leaf", e)
                }

                if (buffer.size > 500 || requestCount - lastRequestCountOnSave > 50) {
                    logger.info("Saving buffer of size ${buffer.size} (already $totalSavedCommits saved) to db after ${requestCount - lastRequestCountOnSave} new requests (total: $requestCount requests)")
                    databaseTime += measureTime {
                        insertUserAndCommits(buffer)
                    }
                    totalSavedCommits += buffer.size
                    buffer.clear()
                    lastRequestCountOnSave = requestCount
                }

                if (requestCount % 100 == 0) {
                    logger.info("Delaying for 10 seconds to avoid rate limiting")
                    delay(10.seconds)
                    waitingTime += 10.seconds
                } else if (requestCount % 25 == 0) {
                    logger.info("Delaying for 1 second to avoid rate limiting")
                    delay(1.seconds)
                    waitingTime += 1.seconds
                }
            }
            logger.info("Saving remaining buffer of size ${buffer.size} to db")
            insertUserAndCommits(buffer)
        }
        logger.info("Connected commits with author/commiter in ${duration.humanReadable()} (database operations took ${databaseTime.humanReadable()}, and waited ${waitingTime.humanReadable()} to avoid rate limits)")
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun insertUserAndCommits(commits: List<RespondCommit>) {
        if (commits.isEmpty()) return

        val users = (commits.mapNotNull { it.author } + commits.mapNotNull { it.commiter }).distinctBy { it.id }
        val userIds = users.mapTo(mutableSetOf()) { it.id }
        val existingUsers = try {
            DatabaseManager.transaction {
                UserTable.select(UserTable.id)
                    .map { it[UserTable.id] }
                    .toList()
            }
        } catch (e: Exception) {
            logger.error("Failed to load existing users", e)
            return
        }
        val usersToInsert = users.filter { it.id !in existingUsers }
        val usersToUpdate = users.filter { it.id in existingUsers }

        try {
            DatabaseManager.transaction {
                UserTable.batchInsert(usersToInsert) {
                    this[UserTable.id] = it.id
                    this[UserTable.username] = it.username
                    this[UserTable.email] = it.email
                    this[UserTable.type] = it.type
                    this[UserTable.slackId] = null
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to insert users", e)
        }
        try {
            DatabaseManager.transaction {
                usersToUpdate.forEach { user ->
                    UserTable.update({ UserTable.id eq user.id }) {
                        it[UserTable.username] = user.username
                        it[UserTable.email] = user.email
                        it[UserTable.type] = user.type
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to update users", e)
        }

        try {
            DatabaseManager.transaction {
                commits.forEach { commit ->
                    CommitTable.update({ CommitTable.id eq commit.sha }) {
                        commit.author?.let { it1 -> it[CommitTable.author] = it1.id }
                        commit.commiter?.let { it1 -> it[CommitTable.commiter] = it1.id }
                        it[CommitTable.requestedFromGitHub] = true
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to update commits", e)
        }
    }

    private suspend fun requestCommits(start: String, perPage: Int, page: Int? = null): List<RespondCommit> {
        require(perPage in 1..100) { "perPage must be between 1 and 100" }
        require(start.length == 40) { "start must be a valid git sha" }
        val url = "$LIST_COMMITS_BASE_URL?sha=$start&per_page=$perPage" + (page?.let { "&page=$it" } ?: "")
        val response = SharedConstants.client.get(url)
        if (response.status.value !in 200..299) {
            when (response.status.value) {
                404 -> logger.warn("Commit sha $start not found, does not exist in the repo, are you sure it's a valid sha?")
                400 -> logger.warn("Invalid request parameters for sha $start")
                409 -> logger.warn("Conflict while requesting commits for sha $start")
                500 -> logger.warn("Internal server error while requesting commits for sha $start")
                else -> logger.error("Failed to request commits for sha $start: ${response.status.value}")
            }
            return emptyList()
        }
        val body = try {
            response.body<JsonArray>()
        } catch (e: Exception) {
            logger.error("Failed to parse response body for sha $start", e)
            return emptyList()
        }
        if (body.isEmpty()) {
            logger.warn("Server returned an empty response for sha $start")
            return emptyList()
        }
        val result = body.mapNotNull { element ->
            try {
                val obj = element.jsonObject
                val commitObj = obj["commit"]!!.jsonObject
                val sha = obj["sha"]!!.jsonPrimitive.content
                val author = obj["author"]!! as? JsonObject
                val authorEmail = commitObj["author"]!!.jsonObject["email"]!!.jsonPrimitive.content
                val commiter = obj["committer"] as? JsonObject
                val commiterEmail = commitObj["committer"]!!.jsonObject["email"]!!.jsonPrimitive.content
                RespondCommit(
                    sha = sha,
                    author = author?.let {
                        User(
                            id = it["id"]!!.jsonPrimitive.long,
                            username = it["login"]!!.jsonPrimitive.content,
                            email = authorEmail,
                            type = it["type"]!!.jsonPrimitive.content,
                        )
                    },
                    commiter = commiter?.let {
                        User(
                            id = it["id"]!!.jsonPrimitive.long,
                            username = it["login"]!!.jsonPrimitive.content,
                            email = commiterEmail,
                            type = it["type"]!!.jsonPrimitive.content,
                        )
                    }
                )
            } catch (e: Exception) {
                logger.error("Failed to parse '$element', skipping it", e)
                null
            }
        }
        return result
    }

    private fun <K, V> Map<K, List<V>>.flattenToPairs(): List<Pair<K, V>> {
        return flatMap { (key, values) ->
            values.map { value -> key to value }
        }
    }

    private fun RevCommit.toCommit(users: Map<String, com.fantamomo.hc.dns.model.User>): Commit {
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

    private class User(
        val id: Long,
        val username: String,
        val email: String,
        val type: String,
    )

    private class RespondCommit(
        val sha: String,
        val author: User?,
        val commiter: User?
    )
}
