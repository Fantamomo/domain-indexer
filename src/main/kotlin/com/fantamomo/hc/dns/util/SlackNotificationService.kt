package com.fantamomo.hc.dns.util

import com.fantamomo.hc.dns.data.Config
import com.fantamomo.hc.dns.data.SharedConstants
import com.fantamomo.hc.dns.db.CommitTable
import com.fantamomo.hc.dns.db.UserTable
import com.fantamomo.hc.dns.manager.DatabaseManager
import com.fantamomo.hc.dns.model.dns.ForkProposal
import com.fantamomo.hc.dns.model.dns.RecordKey
import com.fantamomo.hc.dns.model.dns.RecordTimeline
import com.fantamomo.hc.dns.util.slack.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select
import org.slf4j.LoggerFactory

object SlackNotificationService {

    private val logger = LoggerFactory.getLogger(SlackNotificationService::class.java)

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

        // - start
        // there is s betterm way to do this with joins,
        // but I dont know how to use them in Expose,
        // so we are doing it the old way
        val commitsToUser = DatabaseManager.transaction {
            CommitTable.select(CommitTable.id, CommitTable.author, CommitTable.commiter)
                .where { (CommitTable.author neq null) or (CommitTable.commiter neq null) }
                .map { Pair(it[CommitTable.id], Pair(it[CommitTable.author], it[CommitTable.commiter])) }
                .toList()
                .toMap()
        }
        val users = DatabaseManager.transaction {
            UserTable.select(UserTable.id, UserTable.slackId)
                .where { UserTable.slackId neq null }
                .map { Pair(it[UserTable.id], it[UserTable.slackId]) }
                .toList()
                .toMap()
        }
        val commitsToSlackId = commitsToUser.mapValues { (_, user) -> users[user.first] to users[user.second] }
        // - end

        val totalChanges = newRecords.size + changedRecords.size + removedRecords.size +
                newForkProposals.size + changedForkProposals.size + closedForkProposals.size

        val payload = buildSlackMessage("DNS Changes") {
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

        post(webhookUrl, payload)
    }

    suspend fun sendNewRecordsNotification(newRecords: List<RecordTimeline>) =
        sendDnsChangeNotification(newRecords = newRecords)

    private suspend fun post(webhookUrl: String, payload: kotlinx.serialization.json.JsonObject) {
        runCatching {
            val response = SharedConstants.client.post(webhookUrl) {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            val bodyAsText = response.bodyAsText()
            if (response.status.isSuccess()) {
                logger.info("Slack notification sent successfully")
            } else if (response.status.value == 400 && bodyAsText == "invalid_blocks") {
                // this should never happen in a production environment,
                // the only reason for this to happen if we changed the building mechanics and did an error
                logger.error("Failed to send Slack notification due to invalid blocks: $payload")
            } else {
                logger.error("Slack notification failed — status: ${response.status}, body: $bodyAsText")
            }
        }.onFailure {
            logger.error("Slack notification error", it)
        }
    }

    private fun RecordTimeline.toNewDiff() = RecordDiff(
        fqdn = key.fqdn,
        type = key.type.name,
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
            type = key.type.name,
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
            type = key.type.name,
            valueChange = old?.value?.let { ValueChange.Removed(it) },
            ttlChange = old?.ttl?.let { FieldChange(it, it) },
        )
    }

    private fun ForkProposal.toNewDiff() = RecordDiff(
        fqdn = key.fqdn,
        type = key.type.name,
        repo = "${repository}:${branch}",
        valueChange = current?.value?.let { ValueChange.Added(it) },
        ttlChange = current?.ttl?.let { FieldChange(it, it) },
        commit = current?.commit,
    )

    private fun ForkProposal.toChangedDiff(): RecordDiff {
        val last = timeline.lastOrNull()
        val oldTtl = last?.oldVersion?.ttl
        val newTtl = (last?.newVersion ?: current)?.ttl
        return RecordDiff(
            fqdn = key.fqdn,
            type = key.type.name,
            repo = "${repository}:${branch}",
            valueChange = buildValueChange(
                old = last?.oldVersion?.value,
                new = (last?.newVersion ?: current)?.value,
            ),
            ttlChange = if (oldTtl != null && newTtl != null) FieldChange(oldTtl, newTtl) else null,
            commit = last?.commit ?: current?.commit,
        )
    }

    private fun ForkProposal.toClosedDiff() = RecordDiff(
        fqdn = key.fqdn,
        type = key.type.name,
        repo = "${repository}:${branch}",
        valueChange = current?.value?.let { ValueChange.Removed(it) },
        ttlChange = current?.ttl?.let { FieldChange(it, it) },
        commit = current?.commit,
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