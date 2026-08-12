package com.ethran.notable.utils

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn


/**
 * Groups whatever arrives within [timeoutMillisSelector] of the window opening into one list.
 *
 * The window opens before the first `receive`, so an item arriving into an idle flow is emitted on
 * its own straight away; a burst is batched.
 *
 * Gathering the rest of the window polls rather than spinning. It used to `continue` on an empty
 * `tryReceive`, which burned a whole dispatcher thread for the length of every window — and the
 * windows land exactly when the flow is busy.
 */
fun <T> Flow<T>.chunked(timeoutMillisSelector: Long): Flow<List<T>> = flow {
    coroutineScope {
        val channel = produceIn(this)
        while (true) {
            val start = System.currentTimeMillis()
            val received = channel.receiveCatching().getOrNull() ?: break
            val buffer = mutableListOf<T>(received)

            var closed = false
            while (!closed && System.currentTimeMillis() - start < timeoutMillisSelector) {
                val next = channel.tryReceive()
                val value = next.getOrNull()
                when {
                    value != null -> buffer.add(value)
                    next.isClosed -> closed = true
                    else -> delay(POLL_INTERVAL_MS)
                }
            }
            emit(buffer.toList())
            if (closed) break
        }
    }
}

private const val POLL_INTERVAL_MS = 20L

fun <T : Any> Flow<T>.withPrevious(): Flow<Pair<T?, T>> = flow {
    var prev: T? = null
    this@withPrevious.collect {
        emit(prev to it)
        prev = it
    }
}