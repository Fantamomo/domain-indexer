package com.fantamomo.hc.dns.task.init

import com.fantamomo.hc.dns.App
import com.fantamomo.hc.dns.data.Config
import com.fantamomo.hc.dns.db.UserTable
import com.fantamomo.hc.dns.manager.DatabaseManager
import com.fantamomo.hc.dns.model.SlackUserIdFoundState
import com.fantamomo.hc.dns.service.FindSlackUserId
import com.fantamomo.hc.dns.task.InitTask
import com.fantamomo.hc.dns.util.error.RateLimitedException
import com.fantamomo.hc.dns.util.humanReadable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

object FindMissingSlackUsersInitTask : InitTask(
    "find-missing-slack-users",
    UpdateUsersTask,
    shortDescription = "Find missing slack users",
    longDescription = "Finds users in the database that are not in slack and updates their slack id"
) {
    private const val OVERRIDDEN_GITHUB_ID_TO_SLACK_ID_FILE_PATH = "data/overriden_github_id_to_slack_id.json"

    val overriddenGitHubIdToSlackId: Map<Long, String?> = mutableMapOf()

    private var loaded = false

    override suspend fun run() {
        loadOverriddenGithubIdToSlackIdFile()

        // we are running this task in parallel, because it will take a while to complete
        // and we dont want to block the scheduler from starting
        App.scope.launch {
            findMissingSlackIds()
        }
    }

    private suspend fun findMissingSlackIds() {
        val overriddenIds = overriddenGitHubIdToSlackId.keys

        if (overriddenIds.isNotEmpty()) {
            try {
                val unoverriddenSlackIds = DatabaseManager.transaction {
                    UserTable.select(UserTable.id, UserTable.slackIdState)
                        .where {
                            // we are only overriding users that are explicit UNKNOWN or NOT_FOUND
                            // if overridden users have a custom id set, we don't want to override it
                            // NOT_FOUND is only possible if we add users to the overridden GitHub id to slack id file
                            ((UserTable.slackIdState eq SlackUserIdFoundState.NOT_FOUND) or
                                    (UserTable.slackIdState eq SlackUserIdFoundState.UNKNOWN)) and
                                    (UserTable.id inList overriddenIds)
                        }
                        .map { it[UserTable.id] }
                        .toList()
                }
                if (unoverriddenSlackIds.isNotEmpty()) {
                    logger.info("Found ${unoverriddenSlackIds.size} users with missing slack ids, but they are overridden in the overridden github id to slack id file")
                    for (id in unoverriddenSlackIds) {
                        val slackId = overriddenGitHubIdToSlackId[id]
                        setSlackId(id, slackId, SlackUserIdFoundState.OVERRIDDEN)
                        logger.info("Resolved slack id for user $id from overridden github id to slack id file")
                    }
                    logger.info("Resolved ${unoverriddenSlackIds.size} users with missing slack ids, but they are overridden in the overridden github id to slack id file")
                }
            } catch (e: Exception) {
                logger.error("Failed to resolve slack ids for users with overridden github ids", e)
            }
        }

        val missingUsers = DatabaseManager.transaction {
            UserTable.select(UserTable.id, UserTable.username, UserTable.slackIdState)
                .where { UserTable.slackIdState eq SlackUserIdFoundState.UNKNOWN }
                .map { Pair(it[UserTable.id], it[UserTable.username]) }
                .toList()
        }

        if (missingUsers.isEmpty()) return

        // if we are missing slack ids but no bot and oauth token is configured, we cannot resolve them
        // so we just log a warning and return
        if (Config.SLACK_USER_OAUTH_TOKEN.isBlank() || Config.SLACK_BOT_TOKEN.isBlank()) {
            logger.warn("Found ${missingUsers.size} users with missing slack ids,")
            logger.warn("but slack user oauth token and/or bot token is not configured, user lookup is not possible")
            logger.warn("Please set slack.user.oauth.token and slack.bot.token in the config.properties file")
            return
        }

        logger.info("Found ${missingUsers.size} users with missing slack ids, trying to resolve them")
        logger.info("Will take approximately ${(missingUsers.size / 10.0).minutes.humanReadable()}")

        var founded = 0
        var notFound = 0
        var err = 0
        var overridden = 0
        var total = 0

        var lastRequestTime = Instant.DISTANT_PAST

        suspend fun findSlackIdWithRateLimit(username: String): String? {
            val now = Clock.System.now()
            val nextAllowedRequestTime = lastRequestTime + 6.seconds


            if (now < nextAllowedRequestTime) {
                delay(nextAllowedRequestTime - now)
            }
            lastRequestTime = Clock.System.now()
            return FindSlackUserId.find(username)
        }

        for ((id, username) in missingUsers) {
            total++
            try {
                if (id in overriddenGitHubIdToSlackId) {
                    logger.info("Resolved slack id for user $id($username) from overridden github id to slack id file")
                    setSlackId(id, overriddenGitHubIdToSlackId[id], SlackUserIdFoundState.OVERRIDDEN)
                    overridden++
                    continue
                }
                var slackId: String?

                var tries = 0
                var failed = false
                while (true) {
                    tries++
                    try {
                        slackId = findSlackIdWithRateLimit(username)
                        break
                    } catch (e: RateLimitedException) {
                        val delayDuration = e.retryAfter.seconds
                        logger.warn("Hit the API rate limit, waiting ${delayDuration.humanReadable()} seconds")
                        delay(delayDuration)
                    } catch (e: Exception) {
                        logger.error("Failed to resolve slack id for user $id($username)", e)
                        delay(5.seconds)
                    }
                    if (tries > 5) {
                        logger.warn("Failed to resolve slack id for user $id($username) after $tries tries")
                        failed = true
                        slackId = null
                        break
                    }
                }
                if (failed) continue

                if (slackId == null) {
                    // no need to log this, because FindSlackUserId.find already logged it
                    notFound++

                    setSlackId(id, null, SlackUserIdFoundState.NOT_FOUND)

                    continue
                }

                logger.info("Resolved slack id for user $id($username): $slackId")

                setSlackId(id, slackId, SlackUserIdFoundState.FOUND)
                founded++
            } catch (e: Exception) {
                logger.error("Failed to resolve slack id for user $id($username)", e)
                err++
                if (err % 10 == 0) {
                    logger.error("Due to $err errors, delaying for 30 seconds")
                    delay(30.seconds)
                }
            }
        }

        logger.info("Resolved ${founded + overridden} ($founded via api) users with slack ids, couldn't resolve slack ids of $notFound users, $err errors occurred, $overridden users from overridden github id to slack id file")
    }

    private suspend fun setSlackId(id: Long, slackId: String?, state: SlackUserIdFoundState) = try {
        DatabaseManager.transaction {
            UserTable.update({ UserTable.id eq id }) {
                it[UserTable.slackId] = slackId
                it[UserTable.slackIdState] = state
            }
        }
    } catch (e: Exception) {
        logger.error("Failed to set slack id for user $id", e)
    }

    private fun loadOverriddenGithubIdToSlackIdFile() {
        if (loaded) return
        loaded = true

        val resourceAsStream = javaClass.classLoader.getResourceAsStream(OVERRIDDEN_GITHUB_ID_TO_SLACK_ID_FILE_PATH)
        if (resourceAsStream == null) {
            logger.error("Failed to load overridden github id to slack id file")
            return
        }

        val json = resourceAsStream.bufferedReader().use { it.readText() }
        val jsonMap = try {
            Json.decodeFromString<JsonObject>(json)
        } catch (e: Exception) {
            logger.error("Failed to parse overridden github id to slack id file", e)
            return
        }

        overriddenGitHubIdToSlackId as MutableMap<Long, String?>

        for ((key, value) in jsonMap) {
            try {
                val content = value.jsonPrimitive.contentOrNull
                if (content == null) {
                    overriddenGitHubIdToSlackId[key.toLong()] = null
                    continue
                }
                if (content.isEmpty()) throw IllegalStateException("Empty content")
                if (!content.startsWith("U")) throw IllegalStateException("Invalid content: $content")
                overriddenGitHubIdToSlackId[key.toLong()] = content
            } catch (e: Exception) {
                logger.error("Failed to parse overridden github id to slack id file", e)
            }
        }
    }
}