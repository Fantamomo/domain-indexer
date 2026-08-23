package com.fantamomo.hc.dns.manager

import com.fantamomo.hc.dns.db.ForkProposalTable
import com.fantamomo.hc.dns.model.Hostname
import com.fantamomo.hc.dns.model.dns.RecordType
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.select
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

object HostNameCache {
    private val logger = LoggerFactory.getLogger(HostNameCache::class.java)

    private val expire = 1.minutes

    private val cache = mutableMapOf<Pair<Hostname, String?>, CachedHost>()

    private data class CachedHost(val address: String?, val expireAfter: Instant)

    suspend fun find(hostName: Hostname, targetAddress: String? = null): String? {
        val cached = cache[hostName to targetAddress]
        if (cached == null || cached.expireAfter < Clock.System.now()) {
            return refresh(hostName, targetAddress).address
        }
        return cached.address
    }

    private suspend fun refresh(hostName: Hostname, targetAddress: String?): CachedHost {
        val subdomain = hostName.subdomain
        @Suppress("UNCHECKED_CAST")
        val value = DatabaseManager.transaction {
            ForkProposalTable.select(ForkProposalTable.host, ForkProposalTable.name, ForkProposalTable.currentValue, ForkProposalTable.type)
                .where {
                    (ForkProposalTable.host eq hostName.host) and
                            if (subdomain != null) {
                                ForkProposalTable.name eq subdomain
                            } else {
                                (ForkProposalTable.name eq "") or
                                        (ForkProposalTable.name eq "@")
                            } and
                            (ForkProposalTable.currentValue.isNotNull()) and
                            (ForkProposalTable.type inList listOf(RecordType.AAAA, RecordType.A, RecordType.CNAME))
                }
                .map { it[ForkProposalTable.currentValue]!! }
                .toList()
        }
        val address = if (value.isEmpty()) {
            logger.warn("No value found for ${hostName.value}")
            null
        } else {
            if (targetAddress != null) {
                if (value.any { it.value.removeSuffix(".") == targetAddress.removeSuffix(".") }) {
                    logger.info("Found expected target address for ${hostName.value}: $targetAddress")
                    targetAddress
                } else {
                    logger.warn("Found entries for ${hostName.value}, but none with expected target address $targetAddress")
                    null
                }
            } else {
                val newest = value.maxBy { it.timestamp }
                logger.info("Found value for ${hostName.value}: $newest")
                newest.value.removeSuffix(".")
            }
        }
        val cachedHost = CachedHost(address, Clock.System.now() + expire)
        cache[hostName to targetAddress] = cachedHost
        return cachedHost
    }

    fun invalidAll() {
        cache.clear()
    }
}