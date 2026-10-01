package com.rostik.touchbot

class QueueEngine {
    private val lock = Any()
    private val items = mutableListOf<QueueItem>()
    private var index = 0
    private var version = 0L
    @Volatile private var onChanged: (() -> Unit)? = null

    fun setChangeListener(listener: (() -> Unit)?) { onChanged = listener }
    private fun changed() { try { onChanged?.invoke() } catch (_: Throwable) {} }

    fun replace(newItems: List<QueueItem>) = synchronized(lock) {
        items.clear(); items.addAll(newItems.map { it.copy() }); index = 0; version++
        changed()
    }

    fun replacePreservingProgress(newItems: List<QueueItem>) = synchronized(lock) {
        val oldById = items.associateBy { it.brawlerId }
        items.clear()
        items.addAll(newItems.map { incoming ->
            oldById[incoming.brawlerId]?.copy(
                currentCups = incoming.currentCups,
                targetCups = incoming.targetCups
            ) ?: incoming.copy()
        })
        index = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        version++
        changed()
    }

    fun snapshot(): List<QueueItem> = synchronized(lock) { items.map { it.copy() } }
    fun current(): QueueItem? = synchronized(lock) { items.getOrNull(index)?.copy() }
    fun currentVersion(): Long = synchronized(lock) { version }

    fun updateCups(value: Int): Boolean = synchronized(lock) {
        val item = items.getOrNull(index) ?: return false
        if (value < 0 || value > 10000) return false
        if (kotlin.math.abs(value - item.currentCups) > 120) return false
        item.currentCups = value
        version++
        changed()
        true
    }

    fun markMatch() = synchronized(lock) {
        items.getOrNull(index)?.let { it.matchesPlayed++ }
        version++
        changed()
    }

    fun next(): Boolean = synchronized(lock) {
        if (index + 1 < items.size) { index++; version++; changed(); true } else false
    }

    fun restore(itemsToRestore: List<QueueItem>, currentIndex: Int = 0) = synchronized(lock) {
        items.clear(); items.addAll(itemsToRestore.map { it.copy() })
        index = currentIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        version++
    }

    fun currentIndex(): Int = synchronized(lock) { index }

    fun finished(): Boolean = synchronized(lock) {
        items.isNotEmpty() && index == items.lastIndex && items.all { it.reached }
    }
}
