package com.fantamomo.hc.dns.util

import com.fantamomo.hc.dns.data.Config
import com.fantamomo.hc.dns.data.SharedConstants
import com.fantamomo.hc.dns.db.CommitTable
import com.fantamomo.hc.dns.db.UserTable
import com.fantamomo.hc.dns.manager.DatabaseManager
import com.fantamomo.hc.dns.model.RepoWithBranch
import com.fantamomo.hc.dns.model.dns.ForkProposal
import com.fantamomo.hc.dns.model.dns.RecordKey
import com.fantamomo.hc.dns.model.dns.RecordTimeline
import com.fantamomo.hc.dns.util.slack.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.flow.associate
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select
import org.slf4j.LoggerFactory

object SlackNotificationService {

    private val logger = LoggerFactory.getLogger(SlackNotificationService::class.java)

    private val defaultExtraFields = buildJsonObject {
        put("unfurl_links", false)
        put("unfurl_media", false)
    }

    suspend fun sendDnsChangeNotification(
        newRecords: List<RecordTimeline> = emptyList(),
        changedRecords: List<RecordTimeline> = emptyList(),
        removedRecords: List<RecordTimeline> = emptyList(),
        newForkProposals: List<ForkProposal> = emptyList(),
        changedForkProposals: List<ForkProposal> = emptyList(),
        closedForkProposals: List<ForkProposal> = emptyList(),
    ) {
        val targetChannel = Config.SLACK_CHANNEL_FOR_UPDATES
        if (targetChannel.isBlank()) {
            logger.warn("Slack channel for updates is not configured, skipping notification")
            return
        }

        val hasRecordChanges = newRecords.isNotEmpty() || changedRecords.isNotEmpty() || removedRecords.isNotEmpty()
        val hasForkChanges =
            newForkProposals.isNotEmpty() || changedForkProposals.isNotEmpty() || closedForkProposals.isNotEmpty()
        if (!hasRecordChanges && !hasForkChanges) return

        val authorUserAlias = UserTable.alias("author_user")
        val commiterUserAlias = UserTable.alias("commiter_user")

        val commitsToSlackId = try {
            DatabaseManager.transaction {
                CommitTable
                    .join(
                        authorUserAlias,
                        JoinType.LEFT,
                        CommitTable.author,
                        authorUserAlias[UserTable.id]
                    )
                    .join(
                        commiterUserAlias,
                        JoinType.LEFT,
                        CommitTable.commiter,
                        commiterUserAlias[UserTable.id]
                    )
                    .select(
                        CommitTable.id,
                        authorUserAlias[UserTable.slackId],
                        commiterUserAlias[UserTable.slackId],
                    )
                    .where {
                        (CommitTable.author neq null) or
                                (CommitTable.commiter neq null)
                    }
                    .associate {
                        it[CommitTable.id] to Pair(
                            it[authorUserAlias[UserTable.slackId]],
                            it[commiterUserAlias[UserTable.slackId]]
                        )
                    }
            }
        } catch (e: Exception) {
            logger.error("Failed to load commits to slack ids", e)
            emptyMap()
        }

        val totalChanges = newRecords.size + changedRecords.size + removedRecords.size +
                newForkProposals.size + changedForkProposals.size + closedForkProposals.size

        val payload = buildSlackMessage("DNS Changes", defaultExtraFields) {
            header("DNS Changes – $totalChanges update${if (totalChanges != 1) "s" else ""}")

            section(fields = buildList {
                if (newRecords.isNotEmpty()) add("*Added*\n${newRecords.size} record${newRecords.size.plural}")
                if (changedRecords.isNotEmpty()) add("*Changed*\n${changedRecords.size} record${changedRecords.size.plural}")
                if (removedRecords.isNotEmpty()) add("*Removed*\n${removedRecords.size} record${removedRecords.size.plural}")
                if (newForkProposals.isNotEmpty()) add("*New proposals*\n${newForkProposals.size}")
                if (changedForkProposals.isNotEmpty()) add("*Updated proposals*\n${changedForkProposals.size}")
                if (closedForkProposals.isNotEmpty()) add("*Closed proposals*\n${closedForkProposals.size}")
            })

            divider()

            if (hasRecordChanges) {
                addDiffSection("🟢  New Records", newRecords.map { it.toNewDiff() }, commitsToSlackId)
                addDiffSection("🟡  Changed Records", changedRecords.map { it.toChangedDiff() }, commitsToSlackId)
                addDiffSection("🔴  Removed Records", removedRecords.map { it.toRemovedDiff() }, commitsToSlackId)
            }

            if (hasForkChanges) {
                divider()
                addDiffSection("🔀  New Proposals", newForkProposals.map { it.toNewDiff() }, commitsToSlackId)
                addDiffSection(
                    "🟡  Updated Proposals",
                    changedForkProposals.map { it.toChangedDiff() },
                    commitsToSlackId
                )
                addDiffSection("🔴  Closed Proposals", closedForkProposals.map { it.toClosedDiff() }, commitsToSlackId)
            }

            contextMarkdown(":star: star the <https://github.com/fantamomo/domain-indexer|repo>")
        }

        postMessage(targetChannel, payload)
    }

    private suspend fun postMessage(targetChannel: String, payload: JsonObject) {
        val payloadMap = payload.toMutableMap()
        payloadMap["channel"] = JsonPrimitive(targetChannel)
        val payloadJson = JsonObject(payloadMap)
        runCatching {
            val response = SharedConstants.client.post("https://slack.com/api/chat.postMessage") {
                bearerAuth(Config.SLACK_BOT_TOKEN)
                contentType(ContentType.Application.Json)
                setBody(payloadJson)
            }
            val responseJson = response.body<JsonObject>()
            if (response.status.isSuccess() && (responseJson["ok"] as? JsonPrimitive)?.booleanOrNull == true) {
                logger.info("Slack notification sent successfully")
                return@runCatching
            }
            val error = (responseJson["error"] as? JsonPrimitive)?.contentOrNull
            if (response.status.value == 400 && error == "invalid_blocks") {
                // this should never happen in a production environment,
                // the only reason for this to happen if we changed the building mechanics and did an error
                logger.error("Failed to send Slack notification due to invalid blocks: $payload")
            } else if (error == "not_in_channel") {
                logger.error("Failed to send Slack notification due to bot not in channel (please add the bot to the channel): $payload")
            } else if (error == "channel_not_found") {
                logger.error("Failed to send Slack notification due to channel not found (check the channel you specified): $payload")
            } else {
                logger.error("Slack notification failed — status: ${response.status}, body: $responseJson")
            }
        }.onFailure {
            logger.error("Slack notification error", it)
        }
    }

    private fun RecordTimeline.toNewDiff() = RecordDiff(
        fqdn = key.fqdn,
        type = key.type,
        valueChange = current?.value?.let { ValueChange.Added(it) },
        ttlChange = current?.ttl?.let { FieldChange(it, it) },
        commit = current?.commit,
    )

    private fun RecordTimeline.toChangedDiff(): RecordDiff {
        val last = timeline.lastOrNull()
        val oldTtl = last?.oldVersion?.ttl
        val newTtl = (last?.newVersion ?: current)?.ttl
        return RecordDiff(
            fqdn = key.fqdn,
            type = key.type,
            valueChange = buildValueChange(
                old = last?.oldVersion?.value,
                new = (last?.newVersion ?: current)?.value,
            ),
            ttlChange = if (oldTtl != null && newTtl != null) FieldChange(oldTtl, newTtl) else null,
            commit = last?.commit ?: current?.commit,
        )
    }

    private fun RecordTimeline.toRemovedDiff(): RecordDiff {
        val old = timeline.lastOrNull()?.oldVersion ?: current
        return RecordDiff(
            fqdn = key.fqdn,
            type = key.type,
            valueChange = old?.value?.let { ValueChange.Removed(it) },
            ttlChange = old?.ttl?.let { FieldChange(it, it) },
        )
    }

    private fun ForkProposal.toNewDiff() = RecordDiff(
        fqdn = key.fqdn,
        type = key.type,
        source = repository.split("/", limit = 2).let { RepoWithBranch(it[0], it[1], branch) },
        valueChange = current?.value?.let { ValueChange.Added(it) },
        ttlChange = current?.ttl?.let { FieldChange(it, it) },
        commit = current?.commit,
        previewLink = true,
    )

    private fun ForkProposal.toChangedDiff(): RecordDiff {
        val last = timeline.lastOrNull()
        val oldTtl = last?.oldVersion?.ttl
        val newTtl = (last?.newVersion ?: current)?.ttl
        return RecordDiff(
            fqdn = key.fqdn,
            type = key.type,
            source = repository.split("/", limit = 2).let { RepoWithBranch(it[0], it[1], branch) },
            valueChange = buildValueChange(
                old = last?.oldVersion?.value,
                new = (last?.newVersion ?: current)?.value,
            ),
            ttlChange = if (oldTtl != null && newTtl != null) FieldChange(oldTtl, newTtl) else null,
            commit = last?.commit ?: current?.commit,
            previewLink = true,
        )
    }

    private fun ForkProposal.toClosedDiff() = RecordDiff(
        fqdn = key.fqdn,
        type = key.type,
        source = repository.split("/", limit = 2).let { RepoWithBranch(it[0], it[1], branch) },
        valueChange = current?.value?.let { ValueChange.Removed(it) },
        ttlChange = current?.ttl?.let { FieldChange(it, it) },
        commit = current?.commit,
        previewLink = true
    )

    private fun buildValueChange(old: String?, new: String?): ValueChange? = when {
        old == null && new != null -> ValueChange.Added(new)
        old != null && new == null -> ValueChange.Removed(old)
        old != null && new != null && old != new -> ValueChange.Modified(old, new)
        old != null && new != null -> ValueChange.Unchanged(new)
        else -> null
    }

    private val RecordKey.fqdn
        get() = if (name == "" || name == "@") host else "$name.$host"

    private val Int.plural get() = if (this != 1) "s" else ""
}