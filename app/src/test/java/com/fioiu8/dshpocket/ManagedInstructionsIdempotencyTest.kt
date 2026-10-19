package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.capability.AgentBriefing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

private const val CAP_START = "<!-- DSH Pocket managed capabilities: start -->"
private const val CAP_END = "<!-- DSH Pocket managed capabilities: end -->"
private const val POL_START = "<!-- DSH Pocket managed policy: start -->"
private const val POL_END = "<!-- DSH Pocket managed policy: end -->"

private val REPLACEMENT = "$CAP_START\nGENERATED\n$CAP_END"

/**
 * [AgentBriefing.mergeManagedInstructions] rewrites `AGENTS.md` on every boot, so it must
 * reach a fixed point: whatever the file looked like, running the merge again may not
 * change it. These cases feed it the damage a hand-edited or half-written file can carry.
 */
@RunWith(Parameterized::class)
class ManagedInstructionsIdempotencyTest(
    private val name: String,
    private val existing: String,
    private val survives: List<String>,
) {

    @Test
    fun mergeReachesAFixedPoint() {
        val once = AgentBriefing.mergeManagedInstructions(existing, REPLACEMENT)
        val twice = AgentBriefing.mergeManagedInstructions(once, REPLACEMENT)
        assertEquals("merging twice must not change the file (case: $name)", once, twice)
    }

    @Test
    fun nestedStartMarkerKeepsTextRatherThanDeletingIt() {
        // A start marker nested inside another is malformed. The merge pairs from the
        // inner marker, so the outer block's text falls outside the replacement and is
        // kept as user content.
        //
        // Consequence worth knowing: if a stale generated block ever ends up nested, its
        // text leaks into the user-owned part of the file and is never refreshed again.
        // Preserving is still the safer failure — deleting text from a file the app only
        // partially owns is the one outcome that cannot be undone by the user. Flagged
        // for a decision in the state-layer work rather than changed here.
        val nested = "$CAP_START\nouter\n$CAP_START\ninner\n$CAP_END\ntail"
        val result = AgentBriefing.mergeManagedInstructions(nested, REPLACEMENT)

        assertFalse("the inner block should have been replaced", result.contains("inner"))
        assertTrue("text outside the matched block must not be deleted", result.contains("outer"))
        assertTrue(result.contains("tail"))
    }

    @Test
    fun mergeLeavesExactlyOneGeneratedBlock() {
        val result = AgentBriefing.mergeManagedInstructions(existing, REPLACEMENT)
        for (marker in listOf(CAP_START, CAP_END, POL_START, POL_END)) {
            assertEquals("expected one '$marker' (case: $name)", 1, Regex(Regex.escape(marker)).findAll(result).count())
        }
        assertTrue("generated content missing (case: $name)", result.contains("GENERATED"))
    }

    @Test
    fun mergeKeepsUserOwnedText() {
        val result = AgentBriefing.mergeManagedInstructions(existing, REPLACEMENT)
        for (fragment in survives) {
            assertTrue("user text '$fragment' was dropped (case: $name)", result.contains(fragment))
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf<Any>("empty file", "", emptyList<String>()),
            arrayOf<Any>("user text only", "my own notes\nabout my agent\n", listOf("my own notes\nabout my agent\n")),
            arrayOf<Any>(
                "single well-formed block",
                "intro\n$CAP_START\nstale\n$CAP_END\noutro",
                listOf("intro\n", "outro"),
            ),
            arrayOf<Any>(
                "block with no trailing newline",
                "before${CAP_START}${CAP_END}after",
                listOf("before", "after"),
            ),
            arrayOf<Any>(
                "orphan end marker",
                "before\n$CAP_END\ntail",
                listOf("before\n", "tail"),
            ),
            arrayOf<Any>(
                "orphan start marker",
                "before\n$CAP_START\norphan text\ntail",
                listOf("before\n", "orphan text\n", "tail"),
            ),
            arrayOf<Any>(
                "duplicate blocks",
                "$CAP_START\none\n$CAP_END\nmid\n$CAP_START\ntwo\n$CAP_END\ntail",
                listOf("mid\n", "tail"),
            ),
            arrayOf<Any>(
                "nested start markers",
                "$CAP_START\nouter\n$CAP_START\ninner\n$CAP_END\ntail",
                listOf("tail"),
            ),
            arrayOf<Any>(
                "end before start",
                "$CAP_END\nbefore\n$CAP_START\nafter\n$CAP_END\ntail",
                listOf("before\n", "tail"),
            ),
            arrayOf<Any>(
                "markers glued to prose",
                "see${CAP_START}x${CAP_END}and continue\n",
                listOf("see", "and continue\n"),
            ),
        )
    }
}

/**
 * The stale-content assertions the original two tests relied on, kept as an explicit
 * statement that generated text is replaced rather than appended.
 */
@RunWith(Parameterized::class)
class ManagedInstructionsReplaceStaleContentTest(
    private val name: String,
    private val existing: String,
    private val stale: List<String>,
) {

    @Test
    fun staleGeneratedFactsDoNotSurvive() {
        val result = AgentBriefing.mergeManagedInstructions(existing, REPLACEMENT)
        for (fragment in stale) {
            assertFalse("stale text '$fragment' survived (case: $name)", result.contains(fragment))
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf<Any>("inside a replaced block", "$CAP_START\nold facts\n$CAP_END", listOf("old facts")),
            arrayOf<Any>("inside a duplicate block", "$CAP_START\none\n$CAP_END\nmid\n$CAP_START\ntwo\n$CAP_END", listOf("one", "two")),
            arrayOf<Any>("glued without newlines", "before${CAP_START}${CAP_END}after", emptyList<String>()),
        )
    }
}
