package com.fantamomo.hc.dns.manager

import com.fantamomo.hc.dns.data.SharedValues
import com.fantamomo.hc.dns.data.SharedValues.git
import com.fantamomo.hc.dns.db.ForkTable
import com.fantamomo.hc.dns.db.UserTable
import com.fantamomo.hc.dns.model.dns.*
import com.fantamomo.hc.dns.util.DnsIndexer
import com.fantamomo.hc.dns.util.DnsParser
import com.fantamomo.hc.dns.util.yaml.YamlParser
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.treewalk.TreeWalk
import org.jetbrains.exposed.v1.r2dbc.select
import org.slf4j.LoggerFactory
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

data class DnsIndex(
    val mainTimelines: Map<RecordKey, RecordTimeline>,
    val forkProposals: Map<ForkProposalKey, ForkProposal>
)

object DnsManager {

    private val logger = LoggerFactory.getLogger(DnsManager::class.java)

    private val commitCache = mutableMapOf<String, Map<RecordKey, ParsedRecord>>()

    suspend fun index(): DnsIndex {

        val repoIdToRepo = loadRepoIdMap()

        val allCommits = git.log { all() }.reversed()
        if (allCommits.isEmpty()) return DnsIndex(emptyMap(), emptyMap())

        val headHash = git.repository.resolve("refs/heads/main").name

        val originRefs = git.repository.refDatabase
            .getRefsByPrefix("refs/heads/")
            .filter { it.name != "refs/heads/main" }
            .associate { it.objectId.name to it.name.removePrefix("refs/heads/") }

        val remoteRefs = git.repository.refDatabase
            .getRefsByPrefix("refs/remotes/fork/")
            .associate { it.objectId.name to it.name.removePrefix("refs/remotes/fork/").substringAfter('/') }

        val mainTimelines = mutableMapOf<RecordKey, RecordTimeline>()
        var previousMainState: Map<RecordKey, ParsedRecord> = emptyMap()

        val mainCommits = allCommits.filter { commit ->
            val hash = commit.id.name
            hash == headHash || SharedValues.commitGraphAnalyzer.isAncestorOf(hash, headHash)
        }

        for (commit in mainCommits) {
            val currentState = loadCommitState(commit)
            DnsIndexer.processMainCommit(
                commit = commit.id.name,
                timestamp = Instant.fromEpochSeconds(commit.commitTime.toLong()),
                oldRecords = previousMainState.values.toList(),
                newRecords = currentState.values.toList(),
                mainTimelines = mainTimelines
            )
            previousMainState = currentState
        }

        val currentMainState = previousMainState

        logger.info("Main-branch has been indexed: ${mainTimelines.size} record")

        val forkProposals = mutableMapOf<ForkProposalKey, ForkProposal>()

        for ((tipHash, branchName) in originRefs) {
            processForkBranch(
                repository = "hackclub/dns",
                branch = branchName,
                tipHash = tipHash,
                headHash = headHash,
                currentMainState = currentMainState,
                forkProposals = forkProposals
            )
        }

        for ((tipHash, path) in remoteRefs) {
            val forkId = path.substringBefore('/').toLongOrNull()
            val branchName = path.substringAfter('/')

            if (forkId == null) {
                logger.warn("Illegal fork path $path, skipping")
                continue
            }

            val repoName = repoIdToRepo[forkId]
            if (repoName == null) {
                logger.warn("Unknown fork ID: $forkId, skipping")
                continue
            }

            processForkBranch(
                repository = repoName,
                branch = branchName,
                tipHash = tipHash,
                headHash = headHash,
                currentMainState = currentMainState,
                forkProposals = forkProposals
            )
        }

        logger.info("Found ${forkProposals.size} Fork-Proposals")

        return DnsIndex(mainTimelines, forkProposals)
    }

    private fun processForkBranch(
        repository: String,
        branch: String,
        tipHash: String,
        headHash: String,
        currentMainState: Map<RecordKey, ParsedRecord>,
        forkProposals: MutableMap<ForkProposalKey, ForkProposal>
    ) {
        if (SharedValues.commitGraphAnalyzer.isAncestorOf(tipHash, headHash)) {
            logger.info("Fork $repository:$branch tip $tipHash found in main ancestry, skipping")
            return
        }

        val mergeBaseHash = SharedValues.commitGraphAnalyzer.mergeBase(tipHash, headHash)
        if (mergeBaseHash == null) {
            logger.warn("No merge-base for $repository:$branch ($tipHash), skipping")
            return
        }

        val tipCommit = resolveCommit(tipHash) ?: run {
            logger.warn("Commit $tipHash not resolvable, skipping")
            return
        }

        val mergeBaseTimestamp = resolveCommit(mergeBaseHash)
            ?.let { Instant.fromEpochSeconds(it.commitTime.toLong()) }
            ?: run {
                logger.warn("merge-base $mergeBaseHash not resolvable, skipping")
                return
            }

        val forkState = loadCommitState(tipCommit)

        DnsIndexer.processForkDiff(
            repository = repository,
            branch = branch,
            tipCommit = tipHash,
            tipTimestamp = tipCommit.committerIdent.whenAsInstant.toKotlinInstant(),
            mergeBase = mergeBaseHash,
            mergeBaseTimestamp = mergeBaseTimestamp,
            currentMainRecords = currentMainState,
            forkRecords = forkState,
            forkProposals = forkProposals
        )
    }

    private fun resolveCommit(hash: String): RevCommit? = try {
        git.repository.resolve(hash)?.let { git.repository.parseCommit(it) }
    } catch (e: Exception) {
        logger.warn("Error while resolving $hash", e)
        null
    }

    private fun loadCommitState(commit: RevCommit): Map<RecordKey, ParsedRecord> {
        val hash = commit.id.name
        commitCache[hash]?.let { return it }

        val result = mutableMapOf<RecordKey, ParsedRecord>()

        val treeWalk = TreeWalk(git.repository).apply {
            addTree(commit.tree)
            isRecursive = true
        }

        while (treeWalk.next()) {
            val path = treeWalk.pathString
            if (!path.endsWith(".yaml") || path.contains('/')) continue

            val host = path.removeSuffix(".yaml")
            val objectId = treeWalk.getObjectId(0)
            val loader = git.repository.open(objectId)

            val yaml = loader.openStream().bufferedReader().useLines {
                YamlParser.parse(it)
            }

            for (r in DnsParser.parse(host, yaml)) {
                result[RecordKey(r.host, r.name, r.type)] =
                    ParsedRecord(r.host, r.name, r.type, r.value, r.ttl)
            }
        }

        commitCache[hash] = result
        return result
    }

    private suspend fun loadRepoIdMap(): Map<Long, String> {
        val result = mutableMapOf<Long, String>()
        DatabaseManager.transaction {
            val idToName = UserTable.select(UserTable.id, UserTable.username)
                .map { it[UserTable.id] to it[UserTable.username] }
                .toList()
                .toMap()

            ForkTable.select(ForkTable.id, ForkTable.userId, ForkTable.name)
                .collect {
                    result[it[ForkTable.id]] =
                        "${idToName[it[ForkTable.userId]]}/${it[ForkTable.name]}"
                }
        }
        return result
    }
}