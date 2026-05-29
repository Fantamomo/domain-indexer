package com.fantamomo.hc.dns.util

import com.github.syari.kgit.KGit
import org.eclipse.jgit.revwalk.RevCommit
import java.util.*

class KGitCommitGraphAnalyzer(
    private val repo: KGit
) {

    private val hashToId = HashMap<String, Int>()
    private val idToHash = ArrayList<String>()

    private val parents = ArrayList<IntArray>()

    private val reachableCache = HashMap<Int, BitSet>()

    init {
        buildGraph()
    }

    private fun getOrCreateId(hash: String): Int {
        return hashToId.getOrPut(hash) {
            val id = idToHash.size
            idToHash += hash
            parents += IntArray(0)
            id
        }
    }

    private fun buildGraph() {
        val commits: Iterable<RevCommit> = repo.log {
            all()
        }

        for (commit in commits) {
            val hash = commit.name
            val id = getOrCreateId(hash)

            val parentIds = IntArray(commit.parentCount)

            for (i in 0 until commit.parentCount) {
                val parentHash = commit.getParent(i).name
                parentIds[i] = getOrCreateId(parentHash)
            }

            parents[id] = parentIds
        }
    }

    private fun reachable(id: Int): BitSet {
        reachableCache[id]?.let {
            return it.clone() as BitSet
        }

        val result = BitSet(idToHash.size)

        val queue = ArrayDeque<Int>()
        queue += id

        while (queue.isNotEmpty()) {
            val current = queue.removeLast()

            if (result.get(current)) continue

            result.set(current)

            for (parent in parents[current]) {
                queue += parent
            }
        }

        reachableCache[id] = result
        return result.copy()
    }

    fun relation(hashA: String, hashB: String): CommitRelation {
        val a = hashToId[hashA]
            ?: error("Unknown commit: $hashA")

        val b = hashToId[hashB]
            ?: error("Unknown commit: $hashB")

        val aReach = reachable(a)
        val bReach = reachable(b)

        val mergeBaseId = findMergeBaseId(aReach, bReach)

        val aheadBits = aReach.copy()
        aheadBits.andNot(bReach)

        val behindBits = bReach.copy()
        behindBits.andNot(aReach)

        val aheadCommits = bitSetToHashList(aheadBits)
        val behindCommits = bitSetToHashList(behindBits)

        return CommitRelation(
            commitA = hashA,
            commitB = hashB,
            mergeBase = mergeBaseId?.let(idToHash::get),
            ahead = aheadCommits.size,
            behind = behindCommits.size,
            aheadCommits = aheadCommits,
            behindCommits = behindCommits
        )
    }

    fun mergeBase(hashA: String, hashB: String): String? {
        val a = hashToId[hashA] ?: return null
        val b = hashToId[hashB] ?: return null

        val aReach = reachable(a)
        val bReach = reachable(b)

        return findMergeBaseId(aReach, bReach)
            ?.let(idToHash::get)
    }

    fun isAncestorOf(ancestor: String, descendant: String): Boolean {
        val a = hashToId[ancestor] ?: return false
        val d = hashToId[descendant] ?: return false

        return reachable(d).get(a)
    }

    fun hashes(): Set<String> = hashToId.keys

    private fun findMergeBaseId(
        a: BitSet,
        b: BitSet
    ): Int? {

        val common = a.copy()
        common.and(b)

        if (common.isEmpty) {
            return null
        }

        return common.previousSetBit(idToHash.size - 1)
            .takeIf { it >= 0 }
    }

    private fun bitSetToHashList(bitSet: BitSet): List<String> {
        val result = mutableListOf<String>()
        var id = bitSet.nextSetBit(0)
        while (id >= 0) {
            result.add(idToHash[id])
            id = bitSet.nextSetBit(id + 1)
        }
        return result
    }

    private fun BitSet.copy(): BitSet = clone() as BitSet

    data class CommitRelation(
        val commitA: String,
        val commitB: String,

        val mergeBase: String?,

        // number of commits reachable only from A
        val ahead: Int,

        // number of commits reachable only from B
        val behind: Int,

        // commits reachable only from A
        val aheadCommits: List<String>,

        // commits reachable only from B
        val behindCommits: List<String>
    )
}