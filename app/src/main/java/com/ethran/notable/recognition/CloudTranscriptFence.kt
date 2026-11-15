package com.ethran.notable.recognition

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Tiny per-gesture/write markers; no source hashing or network work runs on input callbacks. */
@Singleton
class CloudTranscriptFence @Inject constructor() {
    internal data class State(val generation: Long = 0, val settled: Boolean = true)
    private data class Contact(val pageId: String, val generation: Long)
    private val lock = Any()
    private val contacts = mutableMapOf<Any, Contact>()
    private val staged = mutableMapOf<String, MutableSet<Long>>()
    private val writes = mutableMapOf<String, MutableSet<Long>>()
    private val states = MutableStateFlow<Map<String, State>>(emptyMap())
    internal val changes = states.asStateFlow()
    internal fun state(pageId: String): State = states.value[pageId] ?: State()
    private fun publish(pageId: String, advance: Boolean = false) {
        val previous = state(pageId)
        states.value = states.value + (pageId to State(previous.generation + if (advance) 1 else 0,
            contacts.values.none { it.pageId == pageId } && staged[pageId].isNullOrEmpty() && writes[pageId].isNullOrEmpty()))
    }
    fun contact(owner: Any, pageId: String?, down: Boolean) = synchronized(lock) {
        if (down && pageId != null) {
            val generation = state(pageId).generation + 1
            contacts[owner] = Contact(pageId, generation)
            publish(pageId, advance = true)
        } else if (!down) contacts.remove(owner)?.let { publish(it.pageId) }
    }
    /** Retain the fence across the raw callback's asynchronous main-thread content handoff. */
    fun stage(owner: Any): Map<String, Long> = synchronized(lock) {
        val contact = contacts[owner] ?: return@synchronized emptyMap()
        staged.getOrPut(contact.pageId) { mutableSetOf() }.add(contact.generation)
        publish(contact.pageId)
        mapOf(contact.pageId to contact.generation)
    }
    fun unstaged(tokens: Map<String, Long>) = synchronized(lock) {
        tokens.forEach { (pageId, token) -> staged[pageId]?.remove(token); publish(pageId) }
    }
    fun changed(pageIds: Collection<String>): Map<String, Long> = synchronized(lock) {
        pageIds.associateWith { pageId ->
            val generation = state(pageId).generation + 1
            writes.getOrPut(pageId) { mutableSetOf() }.add(generation)
            publish(pageId, advance = true)
            generation
        }
    }
    fun saved(tokens: Map<String, Long>) = synchronized(lock) {
        tokens.forEach { (pageId, token) -> writes[pageId]?.remove(token); publish(pageId) }
    }
}
