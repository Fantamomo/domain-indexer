package com.fantamomo.hc.dns.manager

import com.fantamomo.hc.dns.data.SharedConstants
import com.fantamomo.hc.dns.data.SharedValues.git
import com.fantamomo.hc.dns.db.ForkTable
import com.fantamomo.hc.dns.db.HeadTable
import com.fantamomo.hc.dns.db.UserTable
import com.fantamomo.hc.dns.model.Head
import com.fantamomo.hc.dns.model.dns.*
import com.fantamomo.hc.dns.util.DnsIndexer
import com.fantamomo.hc.dns.util.DnsParser
import com.fantamomo.hc.dns.util.RepositoriesToIgnore
import com.fantamomo.hc.dns.util.yaml.YamlParser
import kotlinx.coroutines.flow.associate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.jetbrains.exposed.v1.r2dbc.batchUpsert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
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

        val headRef = git.repository.resolve("refs/heads/main")
            ?: return DnsIndex(emptyMap(), emptyMap())
        val headHash = headRef.name

        val mainTimelines = mutableMapOf<RecordKey, RecordTimeline>()
        indexMainBranch(headHash, mainTimelines)
        logger.info("Main-branch indexed: ${mainTimelines.size} records")

        val heads = DatabaseManager.transaction {
            HeadTable.selectAll()
                .map { Head(it[HeadTable.repoId], it[HeadTable.branch], it[HeadTable.commit]) }
                .toList()
        }
        val headsById = heads.groupBy { it.repoId }

        val forkProposals = mutableMapOf<ForkProposalKey, ForkProposal>()

        val originRefs = git.repository.refDatabase
            .getRefsByPrefix("refs/heads/")
            .filter { it.name != "refs/heads/main" }

        val foundOriginHeads = mutableSetOf<Head>()

        for (ref in originRefs) {
            val branch = ref.name.removePrefix("refs/heads/")
            val head = headsById[SharedConstants.HACKCLUB_DNS_ID]?.find { it.branch == branch }
            val tipHash = ref.objectId.name
            if (head == null || tipHash != head.commit) {
                processForkBranch("hackclub/dns", branch, tipHash, headHash, forkProposals)
                foundOriginHeads += Head(SharedConstants.HACKCLUB_DNS_ID, branch, tipHash)
            } else {
                foundOriginHeads += head
                logger.debug("Skipping hackclub/dns:$branch, already indexed")
            }
        }

        val remoteRefs = git.repository.refDatabase
            .getRefsByPrefix("refs/remotes/fork/")

        val foundRemoteHeads = mutableSetOf<Head>()

        for (ref in remoteRefs) {
            val tipHash = ref.objectId.name
            val path = ref.name.removePrefix("refs/remotes/fork/").substringAfter('/')
            val forkId = path.substringBefore('/').toLongOrNull()
            val branch = path.substringAfter('/')

            if (forkId == null) {
                logger.warn("Illegal fork path $path, skipping")
                continue
            }
            val repoName = repoIdToRepo[forkId] ?: run {
                if (!RepositoriesToIgnore.canIndex(forkId)) {
                    logger.warn("Unknown fork ID: $forkId, skipping")
                }
                continue
            }
            val head = headsById[forkId]?.find { it.branch == branch }

            if (head == null || tipHash != head.commit) {
                processForkBranch(repoName, branch, tipHash, headHash, forkProposals)
                foundRemoteHeads += Head(forkId, branch, tipHash)
            } else {
                logger.debug("Skipping $repoName:$branch, already indexed")
                foundRemoteHeads += head
            }
        }

        try {
            DatabaseManager.transaction {
                HeadTable.batchUpsert(foundOriginHeads, shouldReturnGeneratedValues = false) {
                    this[HeadTable.repoId] = it.repoId
                    this[HeadTable.branch] = it.branch
                    this[HeadTable.commit] = it.commit
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to insert origin heads", e)
        }
        try {
            DatabaseManager.transaction {
                HeadTable.batchUpsert(foundRemoteHeads, shouldReturnGeneratedValues = false) {
                    this[HeadTable.repoId] = it.repoId
                    this[HeadTable.branch] = it.branch
                    this[HeadTable.commit] = it.commit
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to insert remote heads", e)
        }

        logger.info("Found ${forkProposals.size} fork proposals")
        return DnsIndex(mainTimelines, forkProposals)
    }

    private fun indexMainBranch(
        headHash: String,
        mainTimelines: MutableMap<RecordKey, RecordTimeline>
    ) {
        val revWalk = RevWalk(git.repository)
        val headCommit = revWalk.parseCommit(git.repository.resolve(headHash))
        revWalk.markStart(headCommit)

        val mainCommits = revWalk.toList().reversed()
        revWalk.dispose()

        var previousState: Map<RecordKey, ParsedRecord> = emptyMap()
        for (commit in mainCommits) {
            val currentState = loadCommitState(commit)
            DnsIndexer.processMainCommit(
                commit = commit.id.name,
                timestamp = Instant.fromEpochSeconds(commit.commitTime.toLong()),
                previousState = previousState,
                currentState = currentState,
                mainTimelines = mainTimelines
            )
            previousState = currentState
        }
    }

    private fun processForkBranch(
        repository: String,
        branch: String,
        tipHash: String,
        headHash: String,
        forkProposals: MutableMap<ForkProposalKey, ForkProposal>
    ) {
        val revWalk = RevWalk(git.repository)
        try {
            val tipCommit = revWalk.parseCommit(git.repository.resolve(tipHash))
            val headCommit = revWalk.parseCommit(git.repository.resolve(headHash))

            revWalk.run {
                reset()
                markStart(tipCommit)
                markStart(headCommit)
            }

            val mergeBaseCommit = findMergeBase(tipHash, headHash) ?: run {
                logger.warn("No merge-base found for $repository:$branch, skipping")
                return
            }

            val mergeBaseHash = mergeBaseCommit.id.name

            if (mergeBaseHash == tipHash) {
                logger.info("Fork $repository:$branch is fully behind main (tip == merge-base), skipping")
                return
            }

            val mergeBaseState = loadCommitState(mergeBaseCommit)
            val mergeBaseTimestamp = Instant.fromEpochSeconds(mergeBaseCommit.commitTime.toLong())

            val forkOnlyCommits = collectForkOnlyCommits(tipHash, headHash)

            if (forkOnlyCommits.isEmpty()) {
                logger.info("Fork $repository:$branch has no unique commits, skipping")
                return
            }

            logger.info("Fork $repository:$branch: ${forkOnlyCommits.size} unique commit(s), merge-base=$mergeBaseHash")

            DnsIndexer.processForkBranch(
                repository = repository,
                branch = branch,
                mergeBase = mergeBaseHash,
                mergeBaseState = mergeBaseState,
                mergeBaseTimestamp = mergeBaseTimestamp,
                forkCommits = forkOnlyCommits.map { commit ->
                    DnsIndexer.ForkCommit(
                        hash = commit.id.name,
                        timestamp = commit.committerIdent.whenAsInstant.toKotlinInstant(),
                        state = loadCommitState(commit)
                    )
                },
                forkProposals = forkProposals
            )
        } finally {
            revWalk.dispose()
        }
    }

    private fun findMergeBase(hashA: String, hashB: String): RevCommit? {
        return try {
            val revWalk = RevWalk(git.repository)
            val commitA = revWalk.parseCommit(git.repository.resolve(hashA))
            val commitB = revWalk.parseCommit(git.repository.resolve(hashB))

            revWalk.revFilter = org.eclipse.jgit.revwalk.filter.RevFilter.MERGE_BASE
            revWalk.markStart(commitA)
            revWalk.markStart(commitB)
            val base = revWalk.next()
            revWalk.dispose()
            base
        } catch (e: Exception) {
            logger.warn("Error computing merge-base for $hashA / $hashB", e)
            null
        }
    }

    private fun collectForkOnlyCommits(tipHash: String, headHash: String): List<RevCommit> {
        val revWalk = RevWalk(git.repository)
        return try {
            val tipCommit = revWalk.parseCommit(git.repository.resolve(tipHash))
            val headCommit = revWalk.parseCommit(git.repository.resolve(headHash))

            revWalk.markStart(tipCommit)
            revWalk.markUninteresting(headCommit)

            revWalk.toList().reversed()
        } catch (e: Exception) {
            logger.warn("Error collecting fork-only commits for tip=$tipHash", e)
            emptyList()
        } finally {
            revWalk.dispose()
        }
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

            val yaml = loader.openStream().bufferedReader().useLines { YamlParser.parse(it) }

            for (r in DnsParser.parse(host, yaml)) {
                result[RecordKey(r.host, r.name, r.type)] =
                    ParsedRecord(r.host, r.name, r.type, r.value, r.ttl)
            }
        }

        commitCache[hash] = result
        return result
    }

    private suspend fun loadRepoIdMap(): Map<Long, String> {
        return DatabaseManager.transaction {
            (ForkTable innerJoin UserTable)
                .select(ForkTable.id, UserTable.username, ForkTable.name)
                .associate {
                    it[ForkTable.id] to
                            "${it[UserTable.username]}/${it[ForkTable.name]}"
                }
        }
    }
}