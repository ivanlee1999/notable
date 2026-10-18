package com.ethran.notable.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationRequestGuardTest {
    @Test fun `a slow first lookup cannot override a later tap`() {
        val guard = NavigationRequestGuard()
        val first = guard.begin()
        val second = guard.begin()
        assertTrue(guard.isCurrent(second))
        assertFalse(guard.isCurrent(first))
    }
    @Test fun `exit or selecting current note invalidates an outstanding lookup`() {
        val guard = NavigationRequestGuard()
        val opening = guard.begin()
        guard.begin()
        assertFalse(guard.isCurrent(opening))
    }
}
