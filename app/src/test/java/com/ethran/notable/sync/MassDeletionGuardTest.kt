package com.ethran.notable.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [looksLikeStaleStateWipe], the pure guard that refuses a mass deletion which is
 * better explained by lost local state (local rows wiped while sync-state survived) than by an
 * intentional bulk delete. It must trip only when the deletion is both at least
 * [MASS_DELETION_SAFETY_FLOOR] notebooks AND a strict majority of the reference set, so ordinary
 * deletes always propagate.
 */
class MassDeletionGuardTest {

    @Test
    fun trips_on_near_total_wipe() {
        // The observed failure: 128 of 130 synced notebooks "deleted" because the local rows were
        // lost. This must be refused rather than tombstoning them for every device.
        assertTrue(looksLikeStaleStateWipe(deletionCount = 128, referenceCount = 130))
    }

    @Test
    fun allows_a_single_deletion() {
        assertFalse(looksLikeStaleStateWipe(deletionCount = 1, referenceCount = 130))
    }

    @Test
    fun allows_a_few_deletions_below_the_floor_even_if_they_are_the_majority() {
        // Deleting 3 of 4 notebooks is a majority but below the absolute floor, so a user with a tiny
        // library is never blocked from clearing it.
        assertFalse(looksLikeStaleStateWipe(deletionCount = 3, referenceCount = 4))
    }

    @Test
    fun allows_a_large_but_minority_deletion() {
        // Deleting 40 of 200 is well above the floor but not a majority: a real, if big, cleanup.
        assertFalse(looksLikeStaleStateWipe(deletionCount = 40, referenceCount = 200))
    }

    @Test
    fun trips_exactly_at_the_floor_when_a_majority() {
        // Floor is inclusive; 10 of 11 is both >= floor and a strict majority.
        assertTrue(looksLikeStaleStateWipe(deletionCount = MASS_DELETION_SAFETY_FLOOR, referenceCount = 11))
    }

    @Test
    fun allows_at_the_floor_when_exactly_half() {
        // 10 of 20 is at the floor but only half, not a strict majority, so it is allowed.
        assertFalse(looksLikeStaleStateWipe(deletionCount = MASS_DELETION_SAFETY_FLOOR, referenceCount = 20))
    }

    @Test
    fun allows_when_reference_set_is_empty() {
        // No reference to compare against (nothing synced / empty server) can never be a wipe.
        assertFalse(looksLikeStaleStateWipe(deletionCount = 0, referenceCount = 0))
    }
}
