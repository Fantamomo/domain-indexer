package com.fantamomo.hc.dns.util

import com.fantamomo.hc.dns.data.Config
import com.fantamomo.hc.dns.data.SharedConstants
import com.fantamomo.hc.dns.model.dns.ForkProposal
import com.fantamomo.hc.dns.model.dns.RecordKey
import com.fantamomo.hc.dns.model.dns.RecordTimeline
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

object SlackNotificationService {

    private val logger = LoggerFactory.getLogger(SlackNotificationService::class.java)

    private const val BLOCK_LIMIT = 50
    private const val CHUNK_SIZE = 10

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
            logger.warn("Slack webhook URL is not configured, skipping notification")
            return
        }

        val hasRecordChanges = newRecords.isNotEmpty() || changedRecords.isNotEmpty() || removedRecords.isNotEmpty()
        val hasForkChanges =
            newForkProposals.isNotEmpty() || changedForkProposals.isNotEmpty() || closedForkProposals.isNotEmpty()
        if (!hasRecordChanges && !hasForkChanges) return

        val totalChanges = newRecords.size + changedRecords.size + removedRecords.size +
                newForkProposals.size + changedForkProposals.size + closedForkProposals.size

        val blocks = buildBlocks {
            header("DNS Changes - $totalChanges update${if (totalChanges != 1) "s" else ""}")

            section {
                fields {
                    if (newRecords.isNotEmpty()) field("*Added*\n${newRecords.size} record${newRecords.size.plural}")
                    if (changedRecords.isNotEmpty()) field("*Changed*\n${changedRecords.size} record${changedRecords.size.plural}")
                    if (removedRecords.isNotEmpty()) field("*Removed*\n${removedRecords.size} record${removedRecords.size.plural}")
                    if (newForkProposals.isNotEmpty()) field("*New proposals*\n${newForkProposals.size}")
                    if (changedForkProposals.isNotEmpty()) field("*Updated proposals*\n${changedForkProposals.size}")
                    if (closedForkProposals.isNotEmpty()) field("*Closed proposals*\n${closedForkProposals.size}")
                }
            }

            divider()

            if (hasRecordChanges) {
                if (newRecords.isNotEmpty()) {
                    richRecordSection("🟢  New Records", newRecords) { rt ->
                        RichEntry(
                            fqdn = rt.key.fqdn,
                            type = rt.key.type.name,
                            newValue = rt.current?.value,
                            meta = rt.current?.ttl?.let { "TTL: $it" }
                        )
                    }
                }

                if (changedRecords.isNotEmpty()) {
                    richRecordSection("🟡  Changed Records", changedRecords) { rt ->
                        val last = rt.timeline.lastOrNull()
                        RichEntry(
                            fqdn = rt.key.fqdn,
                            type = rt.key.type.name,
                            oldValue = last?.oldVersion?.value,
                            newValue = (last?.newVersion ?: rt.current)?.value,
                            meta = rt.current?.ttl?.let { "TTL: $it" }
                        )
                    }
                }

                if (removedRecords.isNotEmpty()) {
                    richRecordSection("🔴  Removed Records", removedRecords) { rt ->
                        val old = rt.timeline.lastOrNull()?.oldVersion ?: rt.current
                        RichEntry(
                            fqdn = rt.key.fqdn,
                            type = rt.key.type.name,
                            oldValue = old?.value
                        )
                    }
                }
            }

            if (hasForkChanges) {
                divider()

                if (newForkProposals.isNotEmpty()) {
                    richRecordSection("🔀  New Proposals", newForkProposals) { p ->
                        RichEntry(
                            fqdn = p.key.fqdn,
                            type = p.key.type.name,
                            repo = "${p.repository}:${p.branch}",
                            meta = p.current?.ttl?.let { "TTL: $it" },
                            newValue = p.current?.value
                        )
                    }
                }

                if (changedForkProposals.isNotEmpty()) {
                    richRecordSection("🟡  Updated Proposals", changedForkProposals) { p ->
                        val last = p.timeline.lastOrNull()
                        RichEntry(
                            fqdn = p.key.fqdn,
                            type = p.key.type.name,
                            repo = "${p.repository}:${p.branch}",
                            oldValue = last?.oldVersion?.value,
                            newValue = (last?.newVersion ?: p.current)?.value,
                            meta = p.current?.ttl?.let { "TTL: $it" }
                        )
                    }
                }

                if (closedForkProposals.isNotEmpty()) {
                    richRecordSection("🔴  Closed Proposals", closedForkProposals) { p ->
                        RichEntry(
                            fqdn = p.key.fqdn,
                            type = p.key.type.name,
                            repo = "${p.repository}:${p.branch}",
                            oldValue = p.current?.value
                        )
                    }
                }
            }

            context(":star: star the <https://github.com/fantamomo/domain-indexer|repo>")
        }

        post(webhookUrl, blocks)
    }

    suspend fun sendNewRecordsNotification(newRecords: List<RecordTimeline>) =
        sendDnsChangeNotification(newRecords = newRecords)

    private suspend fun post(webhookUrl: String, blocks: List<JsonObject>) {
        runCatching {
            val payload = buildJsonObject {
                put("text", "DNS Change Notification")
                put("blocks", JsonArray(blocks.take(BLOCK_LIMIT)))
            }
            val response = SharedConstants.client.post(webhookUrl) {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            if (response.status.isSuccess()) {
                logger.info("Slack notification sent successfully")
            } else {
                logger.error("Slack notification failed — status: ${response.status}, body: ${response.bodyAsText()}")
            }
        }.onFailure {
            logger.error("Slack notification error", it)
        }
    }

    private data class RichEntry(
        val fqdn: String,
        val type: String,
        val repo: String? = null,
        val oldValue: String? = null,
        val newValue: String? = null,
        val meta: String? = null,
    )

    private fun buildBlocks(init: BlockBuilder.() -> Unit): List<JsonObject> =
        BlockBuilder().apply(init).build()

    private class BlockBuilder {
        private val blocks = mutableListOf<JsonObject>()

        fun header(text: String) {
            if (overflow()) return
            blocks += jsonObjectOf(
                "type" to "header",
                "text" to jsonObjectOf("type" to "plain_text", "text" to text.cap(150), "emoji" to true)
            )
        }

        fun divider() {
            if (overflow()) return
            if (blocks.lastOrNull()?.isDivider == true) return
            blocks += jsonObjectOf("type" to "divider")
        }

        fun section(init: SectionBuilder.() -> Unit) {
            if (overflow()) return
            SectionBuilder().apply(init).build()?.let { blocks += it }
        }

        fun context(text: String) {
            if (overflow()) return
            blocks += jsonObjectOf(
                "type" to "context",
                "elements" to JsonArray(
                    listOf(
                        jsonObjectOf("type" to "mrkdwn", "text" to text.cap(300))
                    )
                )
            )
        }

        fun <T> richRecordSection(title: String, items: List<T>, toEntry: (T) -> RichEntry) {
            if (overflow()) return

            blocks += jsonObjectOf(
                "type" to "rich_text",
                "elements" to JsonArray(
                    listOf(
                        jsonObjectOf(
                            "type" to "rich_text_section",
                            "elements" to JsonArray(
                                listOf(
                                    jsonObjectOf(
                                        "type" to "text",
                                        "text" to "$title (${items.size})",
                                        "style" to jsonObjectOf("bold" to true)
                                    )
                                )
                            )
                        )
                    )
                )
            )

            items.chunked(CHUNK_SIZE).forEachIndexed { chunkIndex, chunk ->
                if (overflow()) return

                val richElements = mutableListOf<JsonObject>()

                if (chunkIndex > 0) {
                    richElements += jsonObjectOf(
                        "type" to "text",
                        "text" to "Part ${chunkIndex + 1}\n",
                        "style" to jsonObjectOf("italic" to true)
                    )
                }

                chunk.forEachIndexed { index, item ->
                    val e = toEntry(item)

                    richElements += jsonObjectOf("type" to "text", "text" to (if (index > 0) "\n\n" else "\n"))

                    richElements += jsonObjectOf(
                        "type" to "text",
                        "text" to "Name: ",
                        "style" to jsonObjectOf("bold" to true)
                    )
                    richElements += jsonObjectOf(
                        "type" to "text",
                        "text" to e.fqdn.sanitize(),
                        "style" to jsonObjectOf("code" to true)
                    )
                    richElements += jsonObjectOf("type" to "text", "text" to "\n")
                    richElements += jsonObjectOf(
                        "type" to "text",
                        "text" to "Type: ",
                        "style" to jsonObjectOf("bold" to true)
                    )
                    richElements += jsonObjectOf(
                        "type" to "text",
                        "text" to e.type,
                        "style" to jsonObjectOf("code" to true)
                    )

                    if (e.repo != null) {
                        richElements += jsonObjectOf("type" to "text", "text" to "\n")
                        richElements += jsonObjectOf(
                            "type" to "text",
                            "text" to "Repo: ",
                            "style" to jsonObjectOf("bold" to true)
                        )
                        richElements += jsonObjectOf(
                            "type" to "text",
                            "text" to e.repo.sanitize(),
                            "style" to jsonObjectOf("code" to true)
                        )
                    }


                    richElements += jsonObjectOf("type" to "text", "text" to "\n")
                    richElements += jsonObjectOf(
                        "type" to "text",
                        "text" to "Value: ",
                        "style" to jsonObjectOf("bold" to true)
                    )
                    when {
                        e.oldValue != null && e.newValue != null -> {
                            richElements += jsonObjectOf(
                                "type" to "text",
                                "text" to e.oldValue.sanitize().cap(80),
                                "style" to jsonObjectOf("code" to true, "strike" to true)
                            )
                            richElements += jsonObjectOf("type" to "text", "text" to "  ->  ")
                            richElements += jsonObjectOf(
                                "type" to "text",
                                "text" to e.newValue.sanitize().cap(80),
                                "style" to jsonObjectOf("code" to true)
                            )
                        }

                        e.oldValue != null -> {
                            richElements += jsonObjectOf(
                                "type" to "text",
                                "text" to e.oldValue.sanitize().cap(100),
                                "style" to jsonObjectOf("code" to true, "strike" to true)
                            )
                        }

                        e.newValue != null -> {
                            richElements += jsonObjectOf(
                                "type" to "text",
                                "text" to e.newValue.sanitize().cap(120),
                                "style" to jsonObjectOf("code" to true)
                            )
                        }
                    }

                    if (e.meta != null) {
                        richElements += jsonObjectOf("type" to "text", "text" to "\n")
                        richElements += jsonObjectOf(
                            "type" to "text",
                            "text" to e.meta.sanitize(),
                            "style" to jsonObjectOf("italic" to true)
                        )
                    }
                }

                richElements += jsonObjectOf("type" to "text", "text" to "\n")

                blocks += jsonObjectOf(
                    "type" to "rich_text",
                    "elements" to JsonArray(
                        listOf(
                            jsonObjectOf(
                                "type" to "rich_text_section",
                                "elements" to JsonArray(richElements)
                            )
                        )
                    )
                )
            }

            if (items.size > CHUNK_SIZE * ((blocks.size / CHUNK_SIZE).coerceAtLeast(1))) {
                blocks += jsonObjectOf(
                    "type" to "context",
                    "elements" to JsonArray(
                        listOf(
                            jsonObjectOf("type" to "mrkdwn", "text" to "_Some entries omitted due to block limit_")
                        )
                    )
                )
            }

            divider()
        }

        private fun overflow() = blocks.size >= BLOCK_LIMIT - 2

        fun build(): List<JsonObject> = blocks
    }

    private class SectionBuilder {
        private var text: String? = null
        private val fieldList = mutableListOf<JsonPrimitive>()

        fun text(value: String) {
            text = value
        }

        fun fields(init: FieldsBuilder.() -> Unit) {
            fieldList += FieldsBuilder().apply(init).build()
        }

        fun build(): JsonObject? {
            if (text == null && fieldList.isEmpty()) return null
            return buildJsonObject {
                put("type", "section")
                text?.let { put("text", jsonObjectOf("type" to "mrkdwn", "text" to it.cap(3000))) }
                if (fieldList.isNotEmpty()) {
                    put("fields", JsonArray(fieldList.map { jsonObjectOf("type" to "mrkdwn", "text" to it) }))
                }
            }
        }
    }

    private class FieldsBuilder {
        private val fields = mutableListOf<JsonPrimitive>()
        fun field(text: String) {
            fields += JsonPrimitive(text.cap(2000))
        }

        fun build() = fields
    }

    private val JsonObject.isDivider get() = get("type")?.jsonPrimitive?.content == "divider"

    private val RecordKey.fqdn
        get() = if (name == "" || name == "@") host else "$name.$host"

    private val Int.plural get() = if (this != 1) "s" else ""

    private fun String.cap(max: Int): String =
        if (length <= max) this else take(max - 1) + "…"

    private fun String.sanitize(): String =
        replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]"), " ")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()

    private fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JsonObject =
        JsonObject(pairs.associate { (k, v) ->
            k to when (v) {
                is JsonElement -> v
                is String -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                null -> JsonNull
                else -> JsonPrimitive(v.toString())
            }
        })
}