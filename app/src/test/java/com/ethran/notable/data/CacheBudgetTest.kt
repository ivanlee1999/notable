package com.ethran.notable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure cache-budget / eviction / accounting policy.
 * No Android or Room dependencies — this is why the policy
 * lives in [PageCache].
 */
class CacheBudgetTest {

    private val mb = 1024L * 1024L

    @Test
    fun `cap is fraction of heap minus reserve`() {
        val budget = CacheBudget(
            maxHeapBytes = { 256 * mb },
            fraction = 0.5,
            reserveBytes = 32 * mb,
        )
        // 256*0.5 - 32 = 96 MB
        assertEquals(96 * mb, budget.capBytes())
    }

    @Test
    fun `cap never goes negative`() {
        val budget = CacheBudget(
            maxHeapBytes = { 16 * mb },
            fraction = 0.5,
            reserveBytes = 64 * mb,
        )
        assertEquals(0L, budget.capBytes())
    }

    @Test
    fun `no eviction when it already fits`() {
        val candidates = listOf(
            EvictCandidate("a", 10 * mb, pinned = false, seq = 1),
            EvictCandidate("b", 10 * mb, pinned = false, seq = 2),
        )
        val victims = selectEvictions(candidates, currentResident = 20 * mb, incoming = 10 * mb, cap = 40 * mb)
        assertTrue(victims.isEmpty())
    }

    @Test
    fun `evicts least-recently-used first until it fits`() {
        val candidates = listOf(
            EvictCandidate("old", 10 * mb, pinned = false, seq = 1),
            EvictCandidate("mid", 10 * mb, pinned = false, seq = 2),
            EvictCandidate("new", 10 * mb, pinned = false, seq = 3),
        )
        // resident 30, incoming 10 → 40; cap 25 → must free 15 → evict two LRU (old, mid).
        val victims = selectEvictions(candidates, currentResident = 30 * mb, incoming = 10 * mb, cap = 25 * mb)
        assertEquals(listOf("old", "mid"), victims)
    }

    @Test
    fun `stops as soon as it fits`() {
        val candidates = listOf(
            EvictCandidate("old", 10 * mb, pinned = false, seq = 1),
            EvictCandidate("mid", 10 * mb, pinned = false, seq = 2),
            EvictCandidate("new", 10 * mb, pinned = false, seq = 3),
        )
        // free just 5 MB → evicting one LRU suffices.
        val victims = selectEvictions(candidates, currentResident = 30 * mb, incoming = 0L, cap = 25 * mb)
        assertEquals(listOf("old"), victims)
    }

    @Test
    fun `never evicts pinned pages`() {
        val candidates = listOf(
            EvictCandidate("current", 30 * mb, pinned = true, seq = 1),
            EvictCandidate("loading", 30 * mb, pinned = true, seq = 2),
        )
        // Way over budget but everything pinned → nothing to evict (current page never refused).
        val victims = selectEvictions(candidates, currentResident = 60 * mb, incoming = 30 * mb, cap = 40 * mb)
        assertTrue(victims.isEmpty())
    }

    @Test
    fun `over-budget current page evicts all unpinned then loads anyway`() {
        val candidates = listOf(
            EvictCandidate("pinnedCurrent", 20 * mb, pinned = true, seq = 3),
            EvictCandidate("n1", 10 * mb, pinned = false, seq = 1),
            EvictCandidate("n2", 10 * mb, pinned = false, seq = 2),
        )
        // Incoming alone (100) exceeds cap (40): evict every unpinned page, order LRU.
        val victims = selectEvictions(candidates, currentResident = 40 * mb, incoming = 100 * mb, cap = 40 * mb)
        assertEquals(listOf("n1", "n2"), victims)
    }

    @Test
    fun `entryBytes is the sum of the per-type costs`() {
        val expected = 2 * PageMemoryModel.BYTES_PER_STROKE +
            100 * PageMemoryModel.BYTES_PER_POINT +
            3 * PageMemoryModel.BYTES_PER_IMAGE
        assertEquals(expected, PageMemoryModel.entryBytes(strokeCount = 2, totalPointCount = 100, imageCount = 3))
    }

    @Test
    fun `estimate scales with blob size and is conservative`() {
        val blob = 50_000L
        assertEquals(blob * PageMemoryModel.BLOB_EXPANSION_K, PageMemoryModel.estimateResidentBytes(blob))
        // K must over-estimate: a point costs ~96B resident but only a few compressed bytes.
        assertTrue(PageMemoryModel.BLOB_EXPANSION_K >= 1)
    }

    @Test
    fun `estimate charges for markdown, so a page of prose and no ink is not free`() {
        val chars = 200_000L
        assertEquals(
            chars * PageMemoryModel.BYTES_PER_MD_CHAR,
            PageMemoryModel.estimateResidentBytes(sumBlobBytes = 0, sumMarkdownChars = chars),
        )
        assertTrue(PageMemoryModel.BYTES_PER_MD_CHAR >= 2) // a JVM char is two bytes resident
    }

    // --- admission policy (1b-4, with 1b-R4/R5 fixes) ---

    @Test
    fun `prefetch loads into spare budget without evicting`() {
        val candidates = listOf(EvictCandidate("n", 10 * mb, pinned = false, seq = 1))
        val d = decideAdmission(
            isPrefetch = true, estimateBytes = 5 * mb, alreadyResidentBytes = 0L,
            residentBytes = 10 * mb, cap = 40 * mb, candidates = candidates,
        )
        assertEquals(AdmissionDecision.Load(emptyList(), overBudget = false), d)
    }

    @Test
    fun `prefetch that does not fit is skipped, never evicts`() {
        val candidates = listOf(EvictCandidate("n", 10 * mb, pinned = false, seq = 1))
        val d = decideAdmission(
            isPrefetch = true, estimateBytes = 40 * mb, alreadyResidentBytes = 0L,
            residentBytes = 30 * mb, cap = 40 * mb, candidates = candidates,
        )
        assertEquals(AdmissionDecision.Skip, d)
    }

    @Test
    fun `prefetch fails closed when the estimate is unknown`() {
        val d = decideAdmission(
            isPrefetch = true, estimateBytes = null, alreadyResidentBytes = 0L,
            residentBytes = 0L, cap = 40 * mb, candidates = emptyList(),
        )
        assertEquals(AdmissionDecision.Skip, d)
    }

    @Test
    fun `current page fails open when the estimate is unknown`() {
        val d = decideAdmission(
            isPrefetch = false, estimateBytes = null, alreadyResidentBytes = 0L,
            residentBytes = 100 * mb, cap = 40 * mb, candidates = emptyList(),
        )
        // Loads anyway (never refused); no unpinned candidates → nothing to evict, over budget.
        assertEquals(AdmissionDecision.Load(emptyList(), overBudget = true), d)
    }

    @Test
    fun `current page evicts LRU to fit its estimate`() {
        val candidates = listOf(
            EvictCandidate("old", 20 * mb, pinned = false, seq = 1),
            EvictCandidate("current", 10 * mb, pinned = true, seq = 2),
        )
        val d = decideAdmission(
            isPrefetch = false, estimateBytes = 20 * mb, alreadyResidentBytes = 0L,
            residentBytes = 30 * mb, cap = 40 * mb, candidates = candidates,
        )
        assertEquals(AdmissionDecision.Load(listOf("old"), overBudget = false), d)
    }

    @Test
    fun `partially-resident page is not double-counted`() {
        // Page already holds 18MB; full estimate 20MB → only 2MB incremental. Resident 40, cap 40:
        // 40 + 2 - (nothing evictable except itself, which is pinned)… make room via an unpinned n.
        val candidates = listOf(
            EvictCandidate("current", 18 * mb, pinned = true, seq = 2),
            EvictCandidate("n", 5 * mb, pinned = false, seq = 1),
        )
        // resident 23 (18 current + 5 n). incremental = 20-18 = 2. 23+2=25 <= 40 → fits, no evict.
        val fits = decideAdmission(
            isPrefetch = false, estimateBytes = 20 * mb, alreadyResidentBytes = 18 * mb,
            residentBytes = 23 * mb, cap = 40 * mb, candidates = candidates,
        )
        assertEquals(AdmissionDecision.Load(emptyList(), overBudget = false), fits)

        // Without the subtraction the check would be 23 + 20 = 43 > 40 and evict "n" needlessly.
        val naive = decideAdmission(
            isPrefetch = false, estimateBytes = 20 * mb, alreadyResidentBytes = 0L,
            residentBytes = 23 * mb, cap = 40 * mb, candidates = candidates,
        )
        assertEquals(AdmissionDecision.Load(listOf("n"), overBudget = false), naive)
    }

    /**
     * The running-total invariant that [PageDataManager] maintains by construction:
     * total == sum of per-entry sizes across a sequence of add/replace/remove operations.
     * Modeled here with plain arithmetic to prove the delta protocol converges.
     */
    @Test
    fun `running total equals sum of entry sizes under add-replace-remove`() {
        val sizes = HashMap<String, Long>()
        var total = 0L

        fun put(id: String, bytes: Long) {
            total += bytes - (sizes[id] ?: 0L)
            sizes[id] = bytes
        }

        fun remove(id: String) {
            total -= sizes.remove(id) ?: 0L
        }

        put("a", PageMemoryModel.entryBytes(1, 10, 0))
        put("b", PageMemoryModel.entryBytes(5, 500, 2))
        put("a", PageMemoryModel.entryBytes(3, 90, 1)) // replace (edit) a
        remove("b")
        put("c", PageMemoryModel.entryBytes(0, 0, 0))

        assertEquals(sizes.values.sum(), total)
    }
}
