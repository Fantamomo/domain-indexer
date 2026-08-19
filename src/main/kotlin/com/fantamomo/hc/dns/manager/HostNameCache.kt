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

    private val cache = mutableMapOf<Hostname, CachedHost>()

    private data class CachedHost(val address: String?, val expireAfter: Instant)

    suspend fun find(hostName: Hostname): String? {
        val cached = cache[hostName]
        if (cached == null || cached.expireAfter < Clock.System.now()) {
            return refresh(hostName)?.address
        }
        return cached.address
    }

    private suspend fun refresh(hostName: Hostname): CachedHost? {
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
            val newest = value.maxBy { it.timestamp }
            logger.info("Found value for ${hostName.value}: $newest")
            newest.value.removeSuffix(".")
        }
        val cachedHost = CachedHost(address, Clock.System.now() + expire)
        cache[hostName] = cachedHost
        return cachedHost
    }

    fun invalidAll() {
        cache.clear()
    }
}