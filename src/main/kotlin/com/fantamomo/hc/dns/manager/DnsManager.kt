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
import com.fantamomo.hc.dns.util.yaml.YamlElement
import com.fantamomo.hc.dns.util.yaml.YamlParser
import kotlinx.coroutines.flow.associate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectLoader
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
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

    private const val MAX_COMMIT_CACHE_SIZE = 4

    private val commitCache = LimitedHashMap()

    private class LimitedHashMap : LinkedHashMap<String, Map<RecordKey, ParsedRecord>>(
        MAX_COMMIT_CACHE_SIZE,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Map<RecordKey, ParsedRecord>>
        ): Boolean = size > MAX_COMMIT_CACHE_SIZE
    }

    suspend fun index(): DnsIndex {
        val repoIdToRepo = loadRepoIdMap()

        val headRef = git.repository.resolve("refs/heads/main")
            ?: return DnsIndex(emptyMap(), emptyMap())

        val headHash = headRef.name

        val mainTimelines = mutableMapOf<RecordKey, RecordTimeline>()

        indexMainBranch(
            headHash = headHash,
            mainTimelines = mainTimelines
        )

        logger.info("Main-branch indexed: ${mainTimelines.size} records")

        val heads = DatabaseManager.transaction {
            HeadTable.selectAll()
                .map {
                    Head(
                        it[HeadTable.repoId],
                        it[HeadTable.branch],
                        it[HeadTable.commit]
                    )
                }
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
            val head = headsById[SharedConstants.HACKCLUB_DNS_ID]
                ?.find { it.branch == branch }

            val tipHash = ref.objectId.name

            if (head == null || tipHash != head.commit) {
                processForkBranch(
                    repository = "hackclub/dns",
                    branch = branch,
                    tipHash = tipHash,
                    headHash = headHash,
                    forkProposals = forkProposals
                )

                foundOriginHeads += Head(
                    SharedConstants.HACKCLUB_DNS_ID,
                    branch,
                    tipHash
                )
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
            val path = ref.name
                .removePrefix("refs/remotes/fork/")
                .substringAfter('/')

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

            val head = headsById[forkId]
                ?.find { it.branch == branch }

            if (head == null || tipHash != head.commit) {
                processForkBranch(
                    repository = repoName,
                    branch = branch,
                    tipHash = tipHash,
                    headHash = headHash,
                    forkProposals = forkProposals
                )

                foundRemoteHeads += Head(
                    forkId,
                    branch,
                    tipHash
                )
            } else {
                logger.debug("Skipping $repoName:$branch, already indexed")
                foundRemoteHeads += head
            }
        }

        try {
            DatabaseManager.transaction {
                HeadTable.batchUpsert(
                    foundOriginHeads,
                    shouldReturnGeneratedValues = false
                ) {
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
                HeadTable.batchUpsert(
                    foundRemoteHeads,
                    shouldReturnGeneratedValues = false
                ) {
                    this[HeadTable.repoId] = it.repoId
                    this[HeadTable.branch] = it.branch
                    this[HeadTable.commit] = it.commit
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to insert remote heads", e)
        }

        logger.info("Found ${forkProposals.size} fork proposals")

        synchronized(commitCache) {
            commitCache.clear()
        }

        return DnsIndex(
            mainTimelines = mainTimelines,
            forkProposals = forkProposals
        )
    }

    private fun indexMainBranch(
        headHash: String,
        mainTimelines: MutableMap<RecordKey, RecordTimeline>
    ) {
        RevWalk(git.repository).use { revWalk ->
            val headObjectId = git.repository.resolve(headHash)
                ?: return

            val headCommit = revWalk.parseCommit(headObjectId)

            revWalk.sort(RevSort.REVERSE)
            revWalk.markStart(headCommit)

            var previousState: Map<RecordKey, ParsedRecord> = emptyMap()

            while (true) {
                val commit = revWalk.next() ?: break

                val currentState = loadCommitState(commit)

                DnsIndexer.processMainCommit(
                    commit = commit.id.name,
                    timestamp = Instant.fromEpochSeconds(
                        commit.commitTime.toLong()
                    ),
                    previousState = previousState,
                    currentState = currentState,
                    mainTimelines = mainTimelines
                )

                previousState = currentState
            }
        }
    }

    private fun processForkBranch(
        repository: String,
        branch: String,
        tipHash: String,
        headHash: String,
        forkProposals: MutableMap<ForkProposalKey, ForkProposal>
    ) {
        RevWalk(git.repository).use { revWalk ->
            val tipCommit = revWalk.parseCommit(git.repository.resolve(tipHash))

            val headCommit = revWalk.parseCommit(git.repository.resolve(headHash))

            revWalk.reset()
            revWalk.markStart(tipCommit)
            revWalk.markStart(headCommit)

            val mergeBaseCommit =
                findMergeBase(tipHash, headHash)
                    ?: run {
                        logger.warn("No merge-base found for $repository:$branch, skipping")
                        return
                    }

            val mergeBaseHash = mergeBaseCommit.id.name

            if (mergeBaseHash == tipHash) {
                logger.info("Fork $repository:$branch is fully behind main (tip == merge-base), skipping")
                return
            }

            val mergeBaseState = loadCommitState(
                mergeBaseCommit,
                cacheResult = true
            )

            val mergeBaseTimestamp = Instant.fromEpochSeconds(mergeBaseCommit.commitTime.toLong())

            val forkOnlyCommits = collectForkOnlyCommits(tipHash, headHash)

            if (forkOnlyCommits.isEmpty()) {
                logger.info("Fork $repository:$branch has no unique commits, skipping")
                return
            }

            logger.info(
                "Fork $repository:$branch: " +
                        "${forkOnlyCommits.size} unique commit(s), " +
                        "merge-base=$mergeBaseHash"
            )

            val forkCommits = ArrayList<DnsIndexer.ForkCommit>(forkOnlyCommits.size)

            for (commit in forkOnlyCommits) {
                forkCommits += DnsIndexer.ForkCommit(
                    hash = commit.id.name,
                    timestamp = commit.committerIdent.whenAsInstant
                        .toKotlinInstant(),
                    state = loadCommitState(commit)
                )
            }

            DnsIndexer.processForkBranch(
                repository = repository,
                branch = branch,
                mergeBase = mergeBaseHash,
                mergeBaseState = mergeBaseState,
                mergeBaseTimestamp = mergeBaseTimestamp,
                forkCommits = forkCommits,
                forkProposals = forkProposals
            )
        }
    }

    private fun findMergeBase(
        hashA: String,
        hashB: String
    ): RevCommit? {
        return try {
            RevWalk(git.repository).use { revWalk ->
                val commitA = revWalk.parseCommit(git.repository.resolve(hashA))

                val commitB = revWalk.parseCommit(git.repository.resolve(hashB))

                revWalk.revFilter = RevFilter.MERGE_BASE

                revWalk.markStart(commitA)
                revWalk.markStart(commitB)

                revWalk.next()
            }
        } catch (e: Exception) {
            logger.warn("Error computing merge-base for $hashA / $hashB", e)
            null
        }
    }

    private fun collectForkOnlyCommits(
        tipHash: String,
        headHash: String
    ): List<RevCommit> {
        RevWalk(git.repository).use { revWalk ->
            return try {
                val tipCommit = revWalk.parseCommit(git.repository.resolve(tipHash))

                val headCommit = revWalk.parseCommit(git.repository.resolve(headHash))

                revWalk.markStart(tipCommit)
                revWalk.markUninteresting(headCommit)

                revWalk.toList().reversed()
            } catch (e: Exception) {
                logger.warn("Error collecting fork-only commits for tip=$tipHash", e)
                emptyList()
            }
        }
    }

    private fun loadCommitState(
        commit: RevCommit,
        cacheResult: Boolean = false
    ): Map<RecordKey, ParsedRecord> {
        val hash = commit.id.name

        synchronized(commitCache) {
            commitCache[hash]?.let { return it }
        }

        val result = HashMap<RecordKey, ParsedRecord>()

        TreeWalk(git.repository).use { treeWalk ->
            treeWalk.addTree(commit.tree)
            treeWalk.isRecursive = true

            while (treeWalk.next()) {
                val path = treeWalk.pathString

                if (!path.endsWith(".yaml") || path.contains('/')) {
                    continue
                }

                val host = path.removeSuffix(".yaml")
                val objectId = treeWalk.getObjectId(0)

                val yaml = openYaml(objectId)

                for ((host1, name, type, value, ttl) in DnsParser.parse(host, yaml)) {
                    val key = RecordKey(host1, name, type)
                    val parsedRecord = ParsedRecord(host1, name, type, value, ttl)
                    result[key] = parsedRecord
                }
            }
        }

        if (cacheResult) {
            synchronized(commitCache) {
                commitCache[hash] = result
            }
        }

        return result
    }

    private fun openYaml(
        objectId: ObjectId
    ): YamlElement {
        val loader: ObjectLoader = git.repository.open(objectId)

        return loader.openStream().use { input ->
            input.bufferedReader().useLines { lines ->
                YamlParser.parse(lines)
            }
        }
    }

    private suspend fun loadRepoIdMap(): Map<Long, String> {
        return DatabaseManager.transaction {
            (ForkTable innerJoin UserTable)
                .select(
                    ForkTable.id,
                    UserTable.username,
                    ForkTable.name
                )
                .associate {
                    it[ForkTable.id] to
                            "${it[UserTable.username]}/${it[ForkTable.name]}"
                }
        }
    }
}