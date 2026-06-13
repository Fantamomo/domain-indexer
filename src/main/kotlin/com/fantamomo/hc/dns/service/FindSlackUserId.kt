package com.fantamomo.hc.dns.service

import com.fantamomo.hc.dns.data.Config
import com.fantamomo.hc.dns.data.SharedConstants
import com.fantamomo.hc.dns.util.error.RateLimitedException
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.net.URI
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

object FindSlackUserId {
    private const val SLACK_ASSISTANT_URL = "https://slack.com/api/assistant.search.context"
    private const val SLACK_USER_PROFILE_URL = "https://slack.com/api/users.profile.get"

    private val logger = LoggerFactory.getLogger(FindSlackUserId::class.java)

    // slack user id regex for slack mentions like <@U0905G0BRU5|Fantamomo>
    private val SLACK_ID_REGEX = Regex("""<@([UW][A-Z0-9]+)(?:\|[^>]+)?>""")

    private val userToGithubUserCache = mutableMapOf<String, String?>()

    // we are trying to find the slack user id for a given github username
    // which is easy to say, but hard to do
    suspend fun find(githubUsername: String): String? {
        if (Config.SLACK_USER_OAUTH_TOKEN.isBlank() || Config.SLACK_BOT_TOKEN.isBlank()) {
            logger.warn("Slack user oauth token or bot token is not configured, user lookup is not possible, skipping")
            return null
        }

        val usersIds = requestUsers(githubUsername)
        if (usersIds.isEmpty()) {
            //logger.warn("No slack user found for github username $githubUsername")
            return null
        }
        val users = usersIds.mapNotNull { id ->
            // only delay if we are not already in the cache
            if (id !in userToGithubUserCache) {
                // slack api is special, the docs says that apps should normally not request it more than once per second
                // so we are doing this to be safe
                delay(1.seconds)
            }

            getGitHubUsername(id)?.let { id to it }
        }.toMap()

        if (users.isEmpty()) {
            logger.warn("No github profile link found for slack users: ${usersIds.joinToString()}")
            return null
        }
        val githubUsernameLowercase = githubUsername.lowercase()
        if (users.size == 1) {
            val username = users.values.first()
            if (username.lowercase() != githubUsernameLowercase) {
                logger.warn("Found one slack user for requested github username $githubUsername, but with different github profile link in profile $username")
            } else {
                return users.keys.first()
            }
        }
        val usersWithMatchingGithub = users.filterValues { it.lowercase() == githubUsernameLowercase }
        when (usersWithMatchingGithub.size) {
            0 -> {
                logger.warn("No slack user found, matched the github username $githubUsernameLowercase: ${users.keys.joinToString()}")
                return null
            }

            1 -> {
                val user = usersWithMatchingGithub.keys.first()
//                logger.info("Found one slack user for requested github username $githubUsername: $user")
                return user
            }

            else -> {
                logger.warn("Found multiple slack users for requested github username $githubUsername: ${usersWithMatchingGithub.keys.joinToString()}")
                logger.warn("Please specify the slack user id manually")
                return null
            }
        }
    }

    private suspend fun getGitHubUsername(slackId: String): String? {
        if (slackId !in userToGithubUserCache) {
            val profile = getUserProfile(slackId)
            if (profile == null) {
                logger.warn("Could not retrieve slack user profile for slack user $slackId")
                // we are not caching this, because maybe we are rate limited or ony other error and we want to retry later
                return null
            }
            val profileFields = profile["fields"] as? JsonObject
            val githubProfileField = profileFields
                ?.get(SharedConstants.SLACK_GITHUB_PROFILE_ID) as? JsonObject
            val value = githubProfileField?.get("value") as? JsonPrimitive
            val link = value?.contentOrNull

            if (link == null) {
                logger.warn("No github profile link found for slack user $slackId")
                userToGithubUserCache[slackId] = null
                return null
            }

            val uri = URI.create(link)
            if (uri.host != "github.com") {
                // maybe the user uses a other service than github
                logger.info("Found a profile link for slack user $slackId, but it is not a github profile link: $link")
                userToGithubUserCache[slackId] = null
                return null
            }
            val githubUser = uri.path.removePrefix("/").substringBefore('/')
            if (githubUser.isBlank()) {
                logger.warn("Invalid github profile link: $link")
                userToGithubUserCache[slackId] = null
                return null
            }
            userToGithubUserCache[slackId] = githubUser
            return githubUser
        } else {
            return userToGithubUserCache[slackId]
        }
    }

    private suspend fun getUserProfile(userId: String): JsonObject? {
        val response = try {
            SharedConstants.client.get("$SLACK_USER_PROFILE_URL?user=$userId") {
                header(HttpHeaders.Authorization, "Bearer ${Config.SLACK_BOT_TOKEN}")
            }
        } catch (e: Exception) {
            logger.error("Failed to get slack user profile", e)
            return null
        }
        if (response.status.value !in 200..299) {
            logger.error("Failed to get slack user profile: ${response.status.value}")
            return null
        }
        try {
            return response.body<JsonObject>()["profile"]!!.jsonObject
        } catch (e: Exception) {
            logger.error("Failed to parse slack user profile response", e)
            return null
        }
    }

    private suspend fun requestUsers(username: String): Collection<String> {
        val response = SharedConstants.client.post(SLACK_ASSISTANT_URL) {
            header(HttpHeaders.Authorization, "Bearer ${Config.SLACK_USER_OAUTH_TOKEN}")
            contentType(ContentType.Application.Json)
            setBody(SharedConstants.json.encodeToString(buildJsonObject {
                put("query", username)
                putJsonArray("keywords_clauses") {
                    add(username)
                }
                putJsonArray("channel_types") {
                    add("public_channel")
                }
                putJsonArray("content_types") {
                    add("messages")
                    add("users")
                }
                put("include_deleted_users", true)
                put("disable_semantic_search", true)
            }))
        }
        if (response.status.value !in 200..299) {
            if (response.status.value == 429) {
                // we got rate limited, we are throwing a special exception to tell the caller to retry later
                val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
                    ?: 5.minutes.inWholeSeconds // slack should always return a retry-after header, but just in case
                throw RateLimitedException(retryAfter)
            }
            logger.error("Failed to request slack users: ${response.status.value}")
            return emptyList()
        }
        var body: JsonObject? = null
        try {
            body = response.body<JsonObject>()
            val ids = extractUserIds(body)
            if (ids.isEmpty()) {
                logger.warn("No slack user found for username $username")
            }
            return ids
        } catch (e: Exception) {
            logger.error("Failed to parse slack users response: $body", e)
            return emptyList()
        }
    }

    private fun extractUserIds(response: JsonObject): Set<String> {
        val result = response["results"]!!.jsonObject

        val ids = mutableSetOf<String>()

        // we are extracting the users element from the results object,
        // which contains all users that match the requested username
        val users = result["users"] as? JsonArray
        if (users != null) {
            for (element in users) {
                if (element is JsonObject) {
                    val idElement = element["user_id"] as? JsonPrimitive
                    if (idElement != null) {
                        val id = idElement.contentOrNull
                        if (id != null) {
                            ids += id
                        } else {
                            logger.warn("Invalid user_id: $idElement")
                        }
                    } else {
                        logger.warn("Missing user_id: $element")
                    }
                } else {
                    logger.warn("Invalid user element: $element")
                }
            }
        } else {
            logger.warn("No results.users found in slack response or it was not an array}")
        }

        // we are extracting all the users,
        // but not all users have the same slack username as the github username
        // so we are also searching for messages that contains the github username,
        // then we are extracting from the messages every slack id that we find
        val messages = result["messages"] as? JsonArray
        if (messages != null) {
            for (element in messages) {
                if (element is JsonObject) {
                    extractUserIdsFromMessage(element, ids)
                } else {
                    logger.warn("Invalid message element: $element")
                }
            }
        } else {
            logger.warn("No results.messages found in slack response or it was not an array}")
        }

        return ids
    }

    private fun extractUserIdsFromMessage(
        message: JsonObject,
        ids: MutableSet<String>
    ) {
        // first the author id
        val authorIdElement = message["author_user_id"] as? JsonPrimitive
        val authorId = authorIdElement?.contentOrNull
        if (authorId != null) {
            ids.add(authorId)
        } else {
            logger.warn("Missing or invalid author_user_id: $message")
        }
        val contentElement = message["content"] as? JsonPrimitive
        val content = contentElement?.contentOrNull
        if (content != null) {
            // we are searching for user mentions in the message content
            // like <@U0905G0BRU5|Fantamomo>
            val matches = SLACK_ID_REGEX.findAll(content)
            for (match in matches) {
                val slackId = match.groupValues[1]
                ids.add(slackId)
            }
        }
    }
}