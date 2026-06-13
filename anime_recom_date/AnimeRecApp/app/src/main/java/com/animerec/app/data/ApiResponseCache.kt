/*
 * AnimeRec - Anime Recommendation App
 * Copyright (C) 2025 Shuvam Banerji Seal
 *
 * Developed by: Shuvam Banerji Seal
 * GitHub: https://github.com/technicallittlemaster
 *
 * This file is part of AnimeRec.
 * Licensed under the MIT License.
 */
package com.animerec.app.data

import android.util.Log
import java.util.Collections

/**
 * Thread-safe in-memory cache for API responses with LRU eviction.
 *
 * The previous implementation used a plain [mutableMapOf], which throws
 * ConcurrentModificationException under concurrent get/put. The current
 * implementation uses a synchronized LinkedHashMap configured for LRU access
 * order and bounded by [maxSize] entries.
 */
class ApiResponseCache(private val maxSize: Int = 100) {

    private val TAG = "ApiResponseCache"

    private data class CacheEntry<T>(
        val data: T,
        val expirationTime: Long
    )

    /**
     * accessOrder = true → iteration order is "least recently accessed first",
     * which lets [removeEldestEntry] evict the LRU key when we hit [maxSize].
     */
    private val cache = object : LinkedHashMap<String, CacheEntry<*>>(
        /* initialCapacity = */ 16,
        /* loadFactor = */ 0.75f,
        /* accessOrder = */ true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry<*>>?): Boolean {
            return size > maxSize
        }
    }

    // Synchronize on the cache map for every read/write so we never call
    // user-supplied lambdas under the lock.
    private val lock = Any()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? = synchronized(lock) {
        val entry = cache[key] as? CacheEntry<T> ?: return null
        if (System.currentTimeMillis() < entry.expirationTime) {
            Log.d(TAG, "Cache hit for key: $key")
            entry.data
        } else {
            Log.d(TAG, "Cache expired for key: $key")
            cache.remove(key)
            null
        }
    }

    fun <T> put(key: String, data: T, expirationMs: Long) {
        val entry = CacheEntry(data, System.currentTimeMillis() + expirationMs)
        synchronized(lock) {
            cache[key] = entry
        }
    }

    fun remove(key: String) {
        synchronized(lock) { cache.remove(key) }
    }

    fun clear() {
        synchronized(lock) {
            cache.clear()
            Log.d(TAG, "Cache cleared")
        }
    }

    fun clearExpired() {
        val currentTime = System.currentTimeMillis()
        synchronized(lock) {
            val iter = cache.entries.iterator()
            while (iter.hasNext()) {
                if (iter.next().value.expirationTime < currentTime) iter.remove()
            }
            Log.d(TAG, "Cleared expired cache entries; size now ${cache.size}")
        }
    }

    fun size(): Int = synchronized(lock) { cache.size }
}

