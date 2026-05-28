package com.fantamomo.hc.dns.service

import com.fantamomo.hc.dns.data.SharedConstants
import com.fantamomo.hc.dns.model.Fork
import com.fantamomo.hc.dns.model.User
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

object GetForksService {

    private val logger = LoggerFactory.getLogger(GetForksService::class.java)

    private val delayBetweenRequests = 500.milliseconds

    private lateinit var forks: List<FetchedFork>

    private data class CacheEntry(
        val etag: String? = null,
        val lastModified: String? = null
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    suspend fun getForks(): ForkRespond {

        val fetchedForks = mutableListOf<FetchedFork>()

        var totalRequests = 0
        var totalWaitTime = Duration.ZERO

        var fetchedPages = 0
        var totalPages: Int? = null

        logger.trace("Fetching forks: https://github.com/hackclub/dns")

        val firstUrl = "https://api.github.com/repos/hackclub/dns/forks?per_page=100"

        val firstResponse = request(firstUrl)
        totalRequests++

        totalPages = firstResponse.lastPageNumber()

        when (val result = handleResponse(firstResponse, firstUrl)) {

            is ResponseResult.Success -> {
                parseForks(result.body, fetchedForks)
                fetchedPages = 1
            }

            is ResponseResult.NotModified -> {
                return ForkRespond.notModified(totalRequests)
            }

            is ResponseResult.Failed -> {
                return ForkRespond.failed(totalRequests)
            }
        }

        var nextUrl = firstResponse.nextLink()

        while (nextUrl != null) {

            delay(delayBetweenRequests)
            totalWaitTime += delayBetweenRequests

            val response = request(nextUrl)
            totalRequests++

            when (val result = handleResponse(response, nextUrl)) {

                is ResponseResult.Success -> {
                    parseForks(result.body, fetchedForks)
                    fetchedPages++
                }

                is ResponseResult.NotModified -> {
                    logger.trace("Page not modified: $nextUrl")
                }

                is ResponseResult.Failed -> {
                    logger.warn("Failed page request: $nextUrl")
                }
            }

            nextUrl = response.nextLink()
        }

        forks = fetchedForks

        return ForkRespond.success(
            forks = fetchedForks,
            totalRequests = totalRequests,
            totalWaitTime = totalWaitTime,
            totalForks = fetchedForks.size,
            fetchedPages = fetchedPages,
            totalPages = totalPages
        )
    }

    fun getCachedForks(): List<FetchedFork> {
        if (!::forks.isInitialized) {
            throw IllegalStateException("Forks have not been fetched yet.")
        }

        return forks
    }

    private suspend fun request(url: String): HttpResponse {

        val cacheEntry = cache[url]

        return SharedConstants.client.get(url) {

            cacheEntry?.etag?.let {
                header(HttpHeaders.IfNoneMatch, it)
            }

            cacheEntry?.lastModified?.let {
                header(HttpHeaders.IfModifiedSince, it)
            }
        }
    }

    private suspend fun handleResponse(
        response: HttpResponse,
        url: String
    ): ResponseResult {

        return when (response.status.value) {

            200 -> {
                updateCache(url, response)
                ResponseResult.Success(response.body())
            }

            304 -> {
                ResponseResult.NotModified
            }

            else -> {
                logger.error("HTTP error ${response.status.value} for $url")
                logger.error(response.bodyAsText())
                ResponseResult.Failed
            }
        }
    }

    private fun updateCache(
        url: String,
        response: HttpResponse
    ) {

        cache[url] = CacheEntry(
            etag = response.headers[HttpHeaders.ETag],
            lastModified = response.headers[HttpHeaders.LastModified]
        )
    }

    private fun HttpResponse.lastPageNumber(): Int? {

        return headers[HttpHeaders.Link]
            ?.split(",")
            ?.map { it.trim() }
            ?.firstOrNull { it.contains("rel=\"last\"") }
            ?.substringAfter("<")
            ?.substringBefore(">")
            ?.replace("per_page=", "")
            ?.substringAfter("page=")
            ?.takeWhile { it.isDigit() }
            ?.toIntOrNull()
    }

    private fun HttpResponse.nextLink(): String? {

        val link = headers[HttpHeaders.Link] ?: return null

        return link
            .split(",")
            .map { it.trim() }
            .firstOrNull { it.contains("rel=\"next\"") }
            ?.substringAfter("<")
            ?.substringBefore(">")
    }

    private fun parseForks(
        body: JsonElement,
        forks: MutableList<FetchedFork>
    ) {

        val array = body.jsonArray

        for (element in array) {

            try {

                val obj = element.jsonObject
                val owner = obj["owner"]!!.jsonObject

                val userId = owner["id"]!!.jsonPrimitive.long
                val userName = owner["login"]!!.jsonPrimitive.content
                val userType = owner["type"]!!.jsonPrimitive.content

                forks.add(
                    FetchedFork(Fork(
                        id = obj["id"]!!.jsonPrimitive.long,
                        userId = userId,
                        name = obj["name"]!!.jsonPrimitive.content,
                        createdAt = Instant.parse(
                            obj["created_at"]!!.jsonPrimitive.content
                        ),
                        lastUpdatedAt = Instant.parse(
                            obj["updated_at"]!!.jsonPrimitive.content
                        ),
                        pushedAt = Instant.parse(
                            obj["pushed_at"]!!.jsonPrimitive.content
                        )
                    ),
                        User(
                            id = userId,
                            username = userName,
                            email = null,
                            type = userType
                        )
                    )
                )

            } catch (e: Exception) {
                logger.error("Failed to parse fork: $element", e)
            }
        }
    }

    class FetchedFork(
        val fork: Fork,
        val user: User
    ) {
        val combinedName: String
            get() = "${user.username}/${fork.name}"
    }

    sealed class ResponseResult {

        data class Success(
            val body: JsonElement
        ) : ResponseResult()

        data object NotModified : ResponseResult()

        data object Failed : ResponseResult()
    }

    enum class Status {
        SUCCESS,
        NOT_MODIFIED,
        FAILED
    }

    data class ForkRespond(
        val forks: List<FetchedFork>,

        val totalRequests: Int,
        val totalWaitTime: Duration,

        val totalForks: Int,
        val fetchedPages: Int,
        val totalPages: Int?,

        val status: Status
    ) {

        val isEmpty: Boolean
            get() = forks.isEmpty()

        val isPartial: Boolean
            get() = totalPages != null && fetchedPages < totalPages

        companion object {

            fun success(
                forks: List<FetchedFork>,
                totalRequests: Int,
                totalWaitTime: Duration,
                totalForks: Int,
                fetchedPages: Int,
                totalPages: Int?
            ) = ForkRespond(
                forks = forks,
                totalRequests = totalRequests,
                totalWaitTime = totalWaitTime,
                totalForks = totalForks,
                fetchedPages = fetchedPages,
                totalPages = totalPages,
                status = Status.SUCCESS
            )

            fun notModified(
                totalRequests: Int
            ) = ForkRespond(
                forks = emptyList(),
                totalRequests = totalRequests,
                totalWaitTime = Duration.ZERO,
                totalForks = 0,
                fetchedPages = 0,
                totalPages = null,
                status = Status.NOT_MODIFIED
            )

            fun failed(
                totalRequests: Int
            ) = ForkRespond(
                forks = emptyList(),
                totalRequests = totalRequests,
                totalWaitTime = Duration.ZERO,
                totalForks = 0,
                fetchedPages = 0,
                totalPages = null,
                status = Status.FAILED
            )
        }
    }
}