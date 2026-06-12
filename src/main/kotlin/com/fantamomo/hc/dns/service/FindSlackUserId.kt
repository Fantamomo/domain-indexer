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
        val users = usersIds.mapNotNull {
            // slack api is special, the docs says that apps should normally not request it more than once per second
            // so we are doing this to be safe
            delay(1.seconds)

            getUserProfile(it)?.let { data -> it to data }
        }.toMap()
        if (users.isEmpty()) {
            logger.warn("No slack user profile retrieved successfully for users: ${usersIds.joinToString()}")
            return null
        }
        val usersToGitLink = users.mapValues { (id, profile) ->
            try {
                val profileFields = profile["fields"] as? JsonObject
                val githubProfileField = profileFields
                    ?.get(SharedConstants.SLACK_GITHUB_PROFILE_ID) as? JsonObject
                val value = githubProfileField?.get("value") as? JsonPrimitive
                value?.contentOrNull
            } catch (e: Exception) {
                logger.error("Failed to parse slack user profile", e)
                null
            }
        }
        val normalizer = usersToGitLink.mapValues { (_, gitLink) ->
            if (gitLink == null) return@mapValues null
            try {
                val uri = URI.create(gitLink)
                if (uri.host != "github.com") return@mapValues null
                uri.path.removePrefix("/").substringBefore('/')
            } catch (e: Exception) {
                logger.error("Failed to parse github link: $gitLink", e)
                null
            }
        }

        @Suppress("UNCHECKED_CAST")
        val normalizedUsers = normalizer.filterValues { it != null } as Map<String, String>
        if (normalizedUsers.isEmpty()) {
            logger.warn("No github profile link found for slack users: ${usersIds.joinToString()}")
            return null
        }
        val githubUsernameLowercase = githubUsername.lowercase()
        if (normalizedUsers.size == 1) {
            val username = normalizedUsers.values.first()
            if (username.lowercase() != githubUsernameLowercase) {
                logger.warn("Found one slack user for requested github username $githubUsername, but with different github profile link in profile $username")
            }
        }
        val usersWithMatchingGithub = normalizedUsers.filterValues { it.lowercase() == githubUsernameLowercase }
        when (usersWithMatchingGithub.size) {
            0 -> {
                logger.warn("No slack user found, matched the github username $githubUsernameLowercase: ${normalizedUsers.keys.joinToString()}")
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

    private suspend fun requestUsers(username: String): List<String> {
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
                    //add("message")
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
            val result = body["results"]!!.jsonObject
            val users = result["users"]!!.jsonArray
            val ids = users.map { it.jsonObject["user_id"]!!.jsonPrimitive.content }
            if (ids.isEmpty()) {
                logger.warn("No slack user found for username $username")
            }
            return ids
        } catch (e: Exception) {
            logger.error("Failed to parse slack users response: $body", e)
            return emptyList()
        }
    }
}