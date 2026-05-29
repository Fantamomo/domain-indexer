package com.fantamomo.hc.dns.util

import com.fantamomo.hc.dns.data.Config
import com.fantamomo.hc.dns.data.SharedConstants
import com.fantamomo.hc.dns.model.dns.ForkProposal
import com.fantamomo.hc.dns.model.dns.RecordTimeline
import com.fantamomo.hc.dns.model.dns.TimelineEventType
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Instant

object SlackNotificationService {

    private val logger = LoggerFactory.getLogger(SlackNotificationService::class.java)

    private const val MAX_TOTAL_BLOCKS = 50
    private const val MAX_SECTION_TEXT_LENGTH = 2800
    private const val MAX_CHUNK_ITEMS = 12

    @Serializable
    private data class SlackPayload(
        val text: String,
        val blocks: List<JsonObject>
    )

    suspend fun sendDnsChangeNotification(
        newRecords: List<RecordTimeline> = emptyList(),
        changedRecords: List<RecordTimeline> = emptyList(),
        removedRecords: List<RecordTimeline> = emptyList(),
        newForkProposals: List<ForkProposal> = emptyList(),
        changedForkProposals: List<ForkProposal> = emptyList(),
        closedForkProposals: List<ForkProposal> = emptyList(),
    ) {
        val webhookUrl = Config.SLACK_WEB_HOOK_URL
        if (webhookUrl.isBlank()) {
            logger.warn("Slack webhook URL is not configured. Skipping notification.")
            return
        }

        val hasMainChanges = newRecords.isNotEmpty() || changedRecords.isNotEmpty() || removedRecords.isNotEmpty()
        val hasForkChanges = newForkProposals.isNotEmpty() || changedForkProposals.isNotEmpty() || closedForkProposals.isNotEmpty()

        if (!hasMainChanges && !hasForkChanges) return

        val blocks = mutableListOf<JsonObject>()

        blocks += headerBlock("DNS Record Changes Detected")
        blocks += dividerBlock()

        if (hasMainChanges) {
            blocks += sectionBlock("*📄 Main Branch Records*")

            appendCategory(
                blocks = blocks,
                title = "🟢 *New Records* (${newRecords.size})",
                emptyLabel = "_No new records_",
                entries = newRecords.map { recordEntry(it, RecordChangeType.CREATED) }
            )

            appendCategory(
                blocks = blocks,
                title = "🟡 *Changed Records* (${changedRecords.size})",
                emptyLabel = "_No changed records_",
                entries = changedRecords.map { recordEntry(it, RecordChangeType.CHANGED) }
            )

            appendCategory(
                blocks = blocks,
                title = "🔴 *Removed Records* (${removedRecords.size})",
                emptyLabel = "_No removed records_",
                entries = removedRecords.map { recordEntry(it, RecordChangeType.REMOVED) }
            )
        }

        if (hasForkChanges) {
            if (blocks.isNotEmpty() && blocks.lastOrNull()?.isDivider() != true) {
                blocks += dividerBlock()
            }

            blocks += sectionBlock("*🔀 Fork Proposals*")

            appendCategory(
                blocks = blocks,
                title = "🟢 *New Proposals* (${newForkProposals.size})",
                emptyLabel = "_No new proposals_",
                entries = newForkProposals.map { forkProposalEntry(it, RecordChangeType.CREATED) }
            )

            appendCategory(
                blocks = blocks,
                title = "🟡 *Updated Proposals* (${changedForkProposals.size})",
                emptyLabel = "_No updated proposals_",
                entries = changedForkProposals.map { forkProposalEntry(it, RecordChangeType.CHANGED) }
            )

            appendCategory(
                blocks = blocks,
                title = "🔴 *Closed / Removed Proposals* (${closedForkProposals.size})",
                emptyLabel = "_No closed proposals_",
                entries = closedForkProposals.map { forkProposalEntry(it, RecordChangeType.REMOVED) }
            )
        }

        blocks += contextBlock("_Fantamomo DNS · ${Instant.now()}_")

        send(webhookUrl, blocks)
    }

    suspend fun sendNewRecordsNotification(newRecords: List<RecordTimeline>) {
        sendDnsChangeNotification(newRecords = newRecords)
    }

    private fun appendCategory(
        blocks: MutableList<JsonObject>,
        title: String,
        emptyLabel: String,
        entries: List<String>
    ) {
        if (blocks.size >= MAX_TOTAL_BLOCKS) return

        blocks += sectionBlock(title)

        if (entries.isEmpty()) {
            if (blocks.size < MAX_TOTAL_BLOCKS) {
                blocks += sectionBlock(emptyLabel)
            }
            if (blocks.size < MAX_TOTAL_BLOCKS) {
                blocks += dividerBlock()
            }
            return
        }

        val normalizedEntries = entries.map { sanitizeSlackText(it) }
        var remaining = normalizedEntries
        var chunkIndex = 0

        while (remaining.isNotEmpty() && blocks.size < MAX_TOTAL_BLOCKS - 2) {
            val (chunk, rest) = takeChunk(remaining)
            remaining = rest
            chunkIndex++

            val chunkTitle = if (chunkIndex == 1) "" else "_Part $chunkIndex"
            val chunkText = buildString {
                if (chunkTitle.isNotBlank()) {
                    append(chunkTitle).append("\n")
                }
                chunk.forEachIndexed { index, entry ->
                    append("• ").append(entry)
                    if (index != chunk.lastIndex) append("\n")
                }
            }

            blocks += sectionBlock(chunkText)

            if (remaining.isNotEmpty() && blocks.size < MAX_TOTAL_BLOCKS - 2) {
                blocks += dividerBlock()
            }
        }

        if (remaining.isNotEmpty() && blocks.size < MAX_TOTAL_BLOCKS - 1) {
            blocks += sectionBlock("_… and ${remaining.size} more entries were truncated due to space limitations._")
        }

        if (blocks.size < MAX_TOTAL_BLOCKS) {
            blocks += dividerBlock()
        }
    }

    private fun takeChunk(items: List<String>): Pair<List<String>, List<String>> {
        if (items.isEmpty()) return emptyList<String>() to emptyList()

        val chunk = mutableListOf<String>()
        var currentLength = 0
        var count = 0

        for (item in items) {
            if (count >= MAX_CHUNK_ITEMS) break

            val normalized = truncateToLength(item, 900)
            val candidateLength = currentLength + normalized.length + 3

            if (candidateLength > MAX_SECTION_TEXT_LENGTH && chunk.isNotEmpty()) break

            chunk += normalized
            currentLength += normalized.length + 3
            count++
        }

        val rest = items.drop(chunk.size)
        return chunk to rest
    }

    private fun recordEntry(rt: RecordTimeline, changeType: RecordChangeType): String {
        val key = rt.key
        val fqdn = "${key.name}.${key.host}"
        val current = rt.current
        val lastEvent = rt.timeline.lastOrNull { it.type == changeType.eventType }

        val header = "*$fqdn* `${sanitizeSlackText(key.type.name)}`"

        val details = when (changeType) {
            RecordChangeType.CREATED -> {
                if (current != null) {
                    buildString {
                        append("Value: `${sanitizeSlackText(current.value)}`\n")
                        append("TTL: ${current.ttl ?: "default"}\n")
                        append("Source: `${sanitizeSlackText(current.repository)}:${sanitizeSlackText(current.branch)}`")
                    }
                } else {
                    "_no value_"
                }
            }

            RecordChangeType.CHANGED -> {
                val old = lastEvent?.oldVersion
                val new = lastEvent?.newVersion ?: current

                buildString {
                    if (old != null) append("~~`${sanitizeSlackText(old.value)}`~~ → ")
                    if (new != null) {
                        append("`${sanitizeSlackText(new.value)}`\n")
                        append("TTL: ${new.ttl ?: "default"}\n")
                        append("Source: `${sanitizeSlackText(new.repository)}:${sanitizeSlackText(new.branch)}`")
                    } else {
                        append("_unknown_")
                    }
                }
            }

            RecordChangeType.REMOVED -> {
                val old = lastEvent?.oldVersion ?: current
                buildString {
                    if (old != null) append("~~`${sanitizeSlackText(old.value)}`~~\n")
                    append("_Record has been removed_")
                }
            }
        }

        return truncateToLength("$header\n$details", MAX_SECTION_TEXT_LENGTH)
    }

    private fun forkProposalEntry(proposal: ForkProposal, changeType: RecordChangeType): String {
        val key = proposal.key
        val fqdn = "${key.name}.${key.host}"

        val header = "*$fqdn* `${sanitizeSlackText(key.type.name)}`"
        val branchLine = "Branch: `${sanitizeSlackText(proposal.repository)}:${sanitizeSlackText(proposal.branch)}`"

        val details = when (changeType) {
            RecordChangeType.CREATED -> {
                buildString {
                    proposal.baseVersion?.let { append("Base: `${sanitizeSlackText(it.value)}`\n") }
                    proposal.current?.let { append("Proposed: `${sanitizeSlackText(it.value)}`\n") }
                    append("Merge-base: `${sanitizeSlackText(proposal.mergeBase)}`")
                }
            }

            RecordChangeType.CHANGED -> {
                val lastEvent = proposal.timeline.lastOrNull()
                val old = lastEvent?.oldVersion
                val new = lastEvent?.newVersion ?: proposal.current

                buildString {
                    if (old != null) append("~~`${sanitizeSlackText(old.value)}`~~ → ")
                    if (new != null) append("`${sanitizeSlackText(new.value)}`\n")
                    append(branchLine)
                }
            }

            RecordChangeType.REMOVED -> {
                buildString {
                    proposal.current?.let { append("~~`${sanitizeSlackText(it.value)}`~~\n") }
                    append("_Proposal closed / removed_")
                }
            }
        }

        return truncateToLength("$header\n$branchLine\n$details", MAX_SECTION_TEXT_LENGTH)
    }

    private fun headerBlock(text: String): JsonObject =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("header"),
                "text" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("plain_text"),
                        "text" to JsonPrimitive(truncateToLength(sanitizeSlackText(text), 150)),
                        "emoji" to JsonPrimitive(true)
                    )
                )
            )
        )

    private fun dividerBlock(): JsonObject =
        JsonObject(mapOf("type" to JsonPrimitive("divider")))

    private fun sectionBlock(text: String): JsonObject =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("section"),
                "text" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("mrkdwn"),
                        "text" to JsonPrimitive(truncateToLength(sanitizeSlackText(text), MAX_SECTION_TEXT_LENGTH))
                    )
                )
            )
        )

    private fun contextBlock(text: String): JsonObject =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("context"),
                "elements" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("mrkdwn"),
                                "text" to JsonPrimitive(truncateToLength(sanitizeSlackText(text), 300))
                            )
                        )
                    )
                )
            )
        )

    private suspend fun send(webhookUrl: String, blocks: List<JsonObject>) {
        try {
            val payload = SlackPayload(
                text = "DNS Change Notification",
                blocks = blocks.take(MAX_TOTAL_BLOCKS)
            )

            val response = SharedConstants.client.post(webhookUrl) {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            if (response.status.isSuccess()) {
                logger.info("Slack notification sent successfully.")
            } else {
                logger.error("Failed to send Slack notification. Status: ${response.status}")
                logger.error("Response body: ${response.bodyAsText()}")
            }
        } catch (e: Exception) {
            logger.error("Error while sending Slack notification", e)
        }
    }

    private fun sanitizeSlackText(value: String): String {
        return value
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]"), " ")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()
    }

    private fun truncateToLength(value: String, maxLength: Int): String {
        if (value.length <= maxLength) return value
        if (maxLength <= 1) return value.take(maxLength)
        return value.take(maxLength - 1) + "…"
    }

    private fun JsonObject.isDivider(): Boolean {
        return this["type"]?.jsonPrimitive?.content == "divider"
    }

    private enum class RecordChangeType(val eventType: TimelineEventType) {
        CREATED(TimelineEventType.CREATED),
        CHANGED(TimelineEventType.UPDATED),
        REMOVED(TimelineEventType.DELETED),
    }
}