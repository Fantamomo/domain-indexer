package com.fantamomo.hc.dns.util

import com.fantamomo.hc.dns.data.Config
import com.fantamomo.hc.dns.data.SharedConstants
import com.fantamomo.hc.dns.model.dns.ForkProposal
import com.fantamomo.hc.dns.model.dns.RecordTimeline
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object SlackNotificationService {

    private val logger = LoggerFactory.getLogger(SlackNotificationService::class.java)

    private const val MAX_TOTAL_BLOCKS = 50
    private const val MAX_SECTION_TEXT_LENGTH = 2800
    private const val MAX_ITEMS_PER_CHUNK = 10

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

        val totalChanges = newRecords.size + changedRecords.size + removedRecords.size +
                newForkProposals.size + changedForkProposals.size + closedForkProposals.size
        val summaryParts = buildList {
            if (newRecords.isNotEmpty()) add("${newRecords.size} added")
            if (changedRecords.isNotEmpty()) add("${changedRecords.size} changed")
            if (removedRecords.isNotEmpty()) add("${removedRecords.size} removed")
            if (newForkProposals.isNotEmpty()) add("${newForkProposals.size} new proposals")
            if (changedForkProposals.isNotEmpty()) add("${changedForkProposals.size} updated proposals")
            if (closedForkProposals.isNotEmpty()) add("${closedForkProposals.size} closed proposals")
        }.joinToString(" · ")

        blocks += headerBlock("DNS Changes — $totalChanges update${if (totalChanges != 1) "s" else ""}")
        blocks += sectionBlock(summaryParts)
        blocks += dividerBlock()

        if (hasMainChanges) {
            if (newRecords.isNotEmpty()) {
                appendRecordSection(blocks, "🟢  New records", newRecords) { rt ->
                    val fqdn = formatFqdn(rt.key.name, rt.key.host)
                    val v = rt.current
                    if (v != null)
                        "*$fqdn* `${rt.key.type}` → `${truncate(v.value, 120)}` _(ttl: ${v.ttl ?: "default"})_"
                    else
                        "*$fqdn* `${rt.key.type}`"
                }
            }

            if (changedRecords.isNotEmpty()) {
                appendRecordSection(blocks, "🟡  Changed records", changedRecords) { rt ->
                    val fqdn = formatFqdn(rt.key.name, rt.key.host)
                    val lastUpdate = rt.timeline.lastOrNull()
                    val old = lastUpdate?.oldVersion
                    val new = lastUpdate?.newVersion ?: rt.current
                    buildString {
                        append("*$fqdn* `${rt.key.type}`")
                        if (old != null && new != null) {
                            append("\n~~`${truncate(old.value, 80)}`~~ → `${truncate(new.value, 80)}`")
                        } else if (new != null) {
                            append("\n`${truncate(new.value, 100)}`")
                        }
                    }
                }
            }

            if (removedRecords.isNotEmpty()) {
                appendRecordSection(blocks, "🔴  Removed records", removedRecords) { rt ->
                    val fqdn = formatFqdn(rt.key.name, rt.key.host)
                    val old = rt.timeline.lastOrNull()?.oldVersion ?: rt.current
                    buildString {
                        append("*$fqdn* `${rt.key.type}`")
                        if (old != null) append("\n~~`${truncate(old.value, 100)}`~~")
                    }
                }
            }
        }

        if (hasForkChanges) {
            if (hasMainChanges && !blocks.lastOrNull().isDivider()) blocks += dividerBlock()

            if (newForkProposals.isNotEmpty()) {
                appendRecordSection(blocks, "🔀  New proposals", newForkProposals) { p ->
                    val fqdn = formatFqdn(p.key.name, p.key.host)
                    buildString {
                        append("*$fqdn* `${p.key.type}` — `${sanitize(p.repository)}:${sanitize(p.branch)}`")
                        p.baseVersion?.let { append("\nbase: `${truncate(it.value, 80)}`") }
                        p.current?.let { append("\nproposed: `${truncate(it.value, 80)}`") }
                    }
                }
            }

            if (changedForkProposals.isNotEmpty()) {
                appendRecordSection(blocks, "🟡  Updated proposals", changedForkProposals) { p ->
                    val fqdn = formatFqdn(p.key.name, p.key.host)
                    val last = p.timeline.lastOrNull()
                    buildString {
                        append("*$fqdn* `${p.key.type}` — `${sanitize(p.repository)}:${sanitize(p.branch)}`")
                        if (last?.oldVersion != null && last.newVersion != null) {
                            append("\n~~`${truncate(last.oldVersion.value, 80)}`~~ → `${truncate(last.newVersion.value, 80)}`")
                        } else {
                            p.current?.let { append("\n`${truncate(it.value, 100)}`") }
                        }
                    }
                }
            }

            if (closedForkProposals.isNotEmpty()) {
                appendRecordSection(blocks, "🔴  Closed proposals", closedForkProposals) { p ->
                    val fqdn = formatFqdn(p.key.name, p.key.host)
                    buildString {
                        append("*$fqdn* `${p.key.type}` — `${sanitize(p.repository)}:${sanitize(p.branch)}`")
                        p.current?.let { append("\n~~`${truncate(it.value, 100)}`~~") }
                    }
                }
            }
        }

        val timestamp = ZonedDateTime.now(ZoneId.of("UTC"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'"))
        blocks += contextBlock("Fantamomo DNS  ·  $timestamp")

        send(webhookUrl, blocks)
    }

    suspend fun sendNewRecordsNotification(newRecords: List<RecordTimeline>) {
        sendDnsChangeNotification(newRecords = newRecords)
    }

    private fun <T> appendRecordSection(
        blocks: MutableList<JsonObject>,
        title: String,
        items: List<T>,
        formatter: (T) -> String
    ) {
        if (blocks.size >= MAX_TOTAL_BLOCKS - 2) return

        blocks += sectionBlock("*$title* (${items.size})")

        val entries = items.map { sanitize(formatter(it)) }
        var remaining = entries
        var chunkIndex = 0

        while (remaining.isNotEmpty() && blocks.size < MAX_TOTAL_BLOCKS - 2) {
            val (chunk, rest) = takeChunk(remaining)
            remaining = rest
            chunkIndex++

            val text = buildString {
                if (chunkIndex > 1) append("_Part ${chunkIndex}_\n")
                chunk.forEachIndexed { i, entry ->
                    append("• ").append(entry)
                    if (i != chunk.lastIndex) append("\n")
                }
            }

            blocks += sectionBlock(text)
        }

        if (remaining.isNotEmpty() && blocks.size < MAX_TOTAL_BLOCKS - 1) {
            blocks += sectionBlock("_… and ${remaining.size} more not shown_")
        }

        if (blocks.size < MAX_TOTAL_BLOCKS) blocks += dividerBlock()
    }

    private fun takeChunk(items: List<String>): Pair<List<String>, List<String>> {
        val chunk = mutableListOf<String>()
        var length = 0

        for (item in items) {
            if (chunk.size >= MAX_ITEMS_PER_CHUNK) break
            val line = truncate(item, 600)
            val candidate = length + line.length + 3
            if (candidate > MAX_SECTION_TEXT_LENGTH && chunk.isNotEmpty()) break
            chunk += line
            length += line.length + 3
        }

        return chunk to items.drop(chunk.size)
    }

    private fun formatFqdn(name: String, host: String): String {
        return if (name == "@") host else "$name.$host"
    }

    private fun headerBlock(text: String): JsonObject =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("header"),
                "text" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("plain_text"),
                        "text" to JsonPrimitive(truncate(sanitize(text), 150)),
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
                        "text" to JsonPrimitive(truncate(sanitize(text), MAX_SECTION_TEXT_LENGTH))
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
                                "text" to JsonPrimitive(truncate(sanitize(text), 300))
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

    private fun sanitize(value: String): String =
        value
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]"), " ")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()

    private fun truncate(value: String, maxLength: Int): String {
        if (value.length <= maxLength) return value
        if (maxLength <= 1) return value.take(maxLength)
        return value.take(maxLength - 1) + "…"
    }

    private fun JsonObject?.isDivider(): Boolean =
        this?.get("type")?.jsonPrimitive?.content == "divider"
}