package com.fantamomo.hc.dns.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

class WorkSynchronizer {
    private val mutex = Mutex()

    suspend fun lock() = mutex.lock()

    fun unlock() = mutex.unlock()

    @OptIn(ExperimentalContracts::class)
    suspend inline fun withLock(action: suspend () -> Unit) {
        contract {
            callsInPlace(action, InvocationKind.EXACTLY_ONCE)
        }
        lock()
        return try {
            action()
        } finally {
            unlock()
        }
    }

    suspend fun waitUntilUnlocked() {
        if (!mutex.isLocked) return

        // wait until unlocked
        mutex.withLock {}
    }
}