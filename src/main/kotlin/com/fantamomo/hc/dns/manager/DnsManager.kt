package com.fantamomo.hc.dns.manager

import com.fantamomo.hc.dns.data.SharedValues
import com.fantamomo.hc.dns.data.SharedValues.git
import com.fantamomo.hc.dns.db.ForkTable
import com.fantamomo.hc.dns.db.UserTable
import com.fantamomo.hc.dns.model.dns.ParsedRecord
import com.fantamomo.hc.dns.model.dns.RecordKey
import com.fantamomo.hc.dns.model.dns.RecordTimeline
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

object DnsManager {

    private val logger = LoggerFactory.getLogger(DnsManager::class.java)

    // cache: commit -> full parsed state at that point
    private val commitCache = mutableMapOf<String, Map<RecordKey, ParsedRecord>>()

    // final result: full timelines
    private val timelines = mutableMapOf<RecordKey, RecordTimeline>()

    suspend fun index(): Map<RecordKey, RecordTimeline> {

        val commits = git.log {
            all()
        }.reversed()

        val head = git.repository.resolve("refs/heads/main").name // hackclub/dns main branch
        val repoIdToRepo = mutableMapOf<Long, String>()
        DatabaseManager.transaction {
            val idToName = UserTable.select(UserTable.id, UserTable.username)
                .map { it[UserTable.id] to it[UserTable.username] }
                .toList()
                .toMap()

            ForkTable.select(ForkTable.id, ForkTable.userId, ForkTable.name)
                .collect {
                    val id = it[ForkTable.id]
                    val userId = it[ForkTable.userId]
                    val name = it[ForkTable.name]

                    repoIdToRepo[id] = "${idToName[userId]}/$name"
                }
        }

        val originRefs =
            git.repository.refDatabase.getRefsByPrefix("refs/heads/").filter { it.name != "refs/heads/main" }
                .associate { it.objectId.name to it.name }
        val remoteRefs =
            git.repository.refDatabase.getRefsByPrefix("refs/remotes/fork/").associate { it.objectId.name to it.name }

        if (commits.isEmpty()) return emptyMap()

        var previousState: Map<RecordKey, ParsedRecord> = emptyMap()

        for (commit in commits) {

            val currentState = loadCommitState(commit)

            val commitHash = commit.id.name
            val (repo, branch) = if (SharedValues.commitGraphAnalyzer.isAncestorOf(head, commitHash)) {
                "hackclub/dns" to "main"
            } else {
                val originEntry =
                    originRefs.entries.find { SharedValues.commitGraphAnalyzer.isAncestorOf(it.key, commitHash) }
                        ?.let {
                            "hackclub/dns" to it.value.removePrefix("refs/heads/")
                        }
                if (originEntry != null) originEntry
                else {
                    val entry =
                        remoteRefs.entries.find {
                            SharedValues.commitGraphAnalyzer.isAncestorOf(
                                it.key,
                                commitHash
                            )
                        }
                    if (entry == null) {
                        logger.warn("Commit $commitHash is not in any fork, skipping")
                        continue
                    }
                    val name = entry.value.removePrefix("refs/remotes/fork/").substringAfter('/')
                    val repoId = name.substringBefore('/').toLongOrNull()
                    if (repoId == null) {
                        logger.warn("Invalid fork name: $name, skipping")
                        continue
                    }
                    val repo = repoIdToRepo[repoId]
                    if (repo == null) {
                        logger.warn("Unknown fork id: $repoId, skipping")
                        continue
                    }
                    repo to name.substringAfter('/')
                }
            }

            DnsIndexer.processCommit(
                repository = repo,
                branch = branch,
                commit = commitHash,
                timestamp = Instant.fromEpochSeconds(commit.commitTime.toLong()),

                oldRecords = previousState.values.toList(),
                newRecords = currentState.values.toList(),

                timelines = timelines
            )

            previousState = currentState
        }

        return timelines
    }

    /**
     * Builds full YAML state for a commit (merged view of all *.yaml files)
     */
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
            if (!path.endsWith(".yaml")) continue
            if (path.contains('/')) continue

            val host = path.removeSuffix(".yaml")

            val objectId = treeWalk.getObjectId(0)
            val loader = git.repository.open(objectId)

            val yaml = loader.openStream().bufferedReader().useLines {
                YamlParser.parse(it)
            }

            val records = DnsParser.parse(host, yaml)

            for (r in records) {

                val key = RecordKey(
                    host = r.host,
                    name = r.name,
                    type = r.type
                )

                result[key] = r.run { ParsedRecord(host, name, type, value, ttl) }
            }
        }

        commitCache[hash] = result
        return result
    }
}