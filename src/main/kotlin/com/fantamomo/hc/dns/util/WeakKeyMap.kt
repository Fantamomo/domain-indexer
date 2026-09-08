package com.fantamomo.hc.dns.util

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

class WeakKeyMap<K : Any, V : Any> {

    private class WeakKey<K : Any>(
        key: K,
        queue: ReferenceQueue<K>
    ) : WeakReference<K>(key, queue) {

        private val hash = key.hashCode()

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true

            val otherKey = other as? WeakKey<*> ?: return false

            val thisReferent = get()
            val otherReferent = otherKey.get()

            return thisReferent != null &&
                   otherReferent != null &&
                   thisReferent == otherReferent
        }
    }

    private val queue = ReferenceQueue<K>()

    private val map = ConcurrentHashMap<WeakKey<K>, V>()

    private fun cleanup() {
        while (true) {
            @Suppress("UNCHECKED_CAST")
            val key = queue.poll() as WeakKey<K>? ?: break

            map.remove(key)
            key.clear()
        }
    }

    operator fun get(key: K): V? {
        cleanup()
        return map[WeakKey(key, queue)]
    }

    operator fun set(key: K, value: V) {
        cleanup()
        map[WeakKey(key, queue)] = value
    }

    fun remove(key: K): V? {
        cleanup()
        return map.remove(WeakKey(key, queue))
    }

    fun containsKey(key: K): Boolean {
        cleanup()
        return map.containsKey(WeakKey(key, queue))
    }

    fun size(): Int {
        cleanup()
        return map.size
    }

    fun clear() {
        map.clear()

        while (queue.poll() != null) {
            // empty queue
        }
    }
}