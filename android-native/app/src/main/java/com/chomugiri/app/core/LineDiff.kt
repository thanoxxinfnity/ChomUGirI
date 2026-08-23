package com.chomugiri.app.core

/**
 * Real added/removed line counts between two versions of a file — the numbers behind the
 * "+110 -0" shown on each file row in chat. These have to be honest: a made-up or approximated
 * count is worse than none, because it reads as precise.
 *
 * Strategy is the standard one: strip the common prefix and suffix first (which is the whole
 * story for most real edits), then run an LCS over whatever genuinely differs in the middle.
 * The LCS is quadratic, so it is bounded — past the cap the trimmed block counts as a wholesale
 * replacement, which is the honest reading of a change that large anyway.
 */
private const val LCS_CELL_BUDGET = 1_000_000

fun diffLineCounts(before: String, after: String): Pair<Int, Int> {
    if (before == after) return 0 to 0
    val a = before.lines()
    val b = after.lines()

    var lo = 0
    val maxLo = minOf(a.size, b.size)
    while (lo < maxLo && a[lo] == b[lo]) lo++

    var hi = 0
    while (hi < (maxLo - lo) && a[a.size - 1 - hi] == b[b.size - 1 - hi]) hi++

    val aMid = a.subList(lo, a.size - hi)
    val bMid = b.subList(lo, b.size - hi)
    if (aMid.isEmpty()) return bMid.size to 0
    if (bMid.isEmpty()) return 0 to aMid.size

    if (aMid.size.toLong() * bMid.size.toLong() > LCS_CELL_BUDGET) {
        return bMid.size to aMid.size
    }

    // Rolling two-row LCS: only the length is needed, never the alignment itself.
    var prev = IntArray(bMid.size + 1)
    var cur = IntArray(bMid.size + 1)
    for (i in aMid.indices) {
        for (j in bMid.indices) {
            cur[j + 1] = if (aMid[i] == bMid[j]) prev[j] + 1
            else maxOf(prev[j + 1], cur[j])
        }
        val swap = prev; prev = cur; cur = swap
        java.util.Arrays.fill(cur, 0)
    }
    val common = prev[bMid.size]
    return (bMid.size - common) to (aMid.size - common)
}

/**
 * What changed between the project's previous file set and the one the swarm just produced.
 * Untouched files are left out entirely — a row for a file nobody edited is noise.
 */
fun fileActionsBetween(before: List<GeneratedFile>, after: List<GeneratedFile>): List<FileAction> {
    val old = before.associateBy { it.path }
    return after.mapNotNull { f ->
        val prior = old[f.path]
        if (prior == null) {
            FileAction(f.path, "Created", f.content.lines().size, 0)
        } else if (prior.content != f.content) {
            val (added, removed) = diffLineCounts(prior.content, f.content)
            FileAction(f.path, "Edited", added, removed)
        } else null
    }
}
