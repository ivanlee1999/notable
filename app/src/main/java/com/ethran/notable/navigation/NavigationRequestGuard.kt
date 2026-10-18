package com.ethran.notable.navigation

/** Main-thread navigation intent ordering, independent of asynchronous database completion. */
internal class NavigationRequestGuard {
    private var generation = 0L
    fun begin(): Long = ++generation
    fun isCurrent(request: Long): Boolean = request == generation
}
