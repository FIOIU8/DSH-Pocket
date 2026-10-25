package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.capability.AgentBriefing
import com.fioiu8.dshpocket.screen.prioritizeActive
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen-control host tool is a plain .mjs asset executed by the bundled harness, so
 * its wire-side contract cannot be exercised on the JVM. What CAN be locked down is the
 * contract with the harness itself — most importantly `output.render(args, value)`: the
 * original single-parameter render silently showed the model its own arguments instead
 * of every tool result (read trees, refusals, screenshot paths all looked like echoes).
 */
class ScreenControlToolContractTest {

    private fun asset(name: String): String {
        // Gradle unit tests run with the module directory as the working directory,
        // but resolve defensively so a runner started from the repo root also works.
        val candidates = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
        val file = candidates.firstOrNull(File::isFile) ?: error("asset not found on disk: $name")
        return file.readText()
    }

    @Test
    fun renderDeclaresBothHarnessParameters() {
        val tool = asset("pocket-screen-control.mjs")
        assertTrue(
            "output.render must be (args, value); a single-parameter render receives the " +
                "model's arguments and the real result never reaches the model",
            Regex("render:\\s*\\(_args,\\s*value\\)").containsMatchIn(tool),
        )
        assertFalse("single-parameter render must not reappear", Regex("render:\\s*\\(value\\)").containsMatchIn(tool))
    }

    @Test
    fun screenshotReturnsTheImageInlineThroughTheAttachmentStore() {
        val tool = asset("pocket-screen-control.mjs")
        assertTrue("the tool must declare the optional attachments service", tool.contains("""ctx.inject(["attachments"]"""))
        assertTrue("screenshot bytes must go through attachments.saveImage", tool.contains("attachments.saveImage("))
        assertTrue(
            "the model-visible image block must be built from the value (attachment ref), not from the arguments",
            tool.contains("""{ type: "image", attachment: image }"""),
        )
    }

    @Test
    fun toolDescriptionTellsTheModelTheScreenshotArrivesInline() {
        val tool = asset("pocket-screen-control.mjs")
        assertTrue(tool.contains("returns the image inline"))
    }

    @Test
    fun permissionRequestsCarryTheirOwnLongerTimeout() {
        val tool = asset("pocket-screen-control.mjs")
        assertTrue(
            "request_permission must be offered to the model",
            tool.contains("request_permission"),
        )
        assertTrue(
            "granting happens in system settings; the default 60s bridge timeout would abort the wait",
            Regex("payload\\.action === \"request_permission\" \\? PERMISSION_ASK_TIMEOUT_MS : BRIDGE_TIMEOUT_MS").containsMatchIn(tool),
        )
    }

    @Test
    fun briefingAnnouncesInlineScreenshots() {
        val enabled = AgentBriefing.renderScreenControl(
            AgentBriefing.ScreenControlFacts(enabled = true, accessibilityReady = true, overlayGranted = true),
        )
        assertTrue("the briefing should describe the inline screenshot result", enabled.contains("inline"))
        assertTrue("the briefing should stop the model from reading the saved PNGs", enabled.contains("do not read the saved PNG"))

        val disabled = AgentBriefing.renderScreenControl(
            AgentBriefing.ScreenControlFacts(enabled = false, accessibilityReady = false, overlayGranted = false),
        )
        assertTrue(disabled.contains("disabled in DSH Pocket settings"))
    }
}

/**
 * The read tree spends one shared node budget window by window; [prioritizeActive] is
 * what keeps a busy launcher or system overlay from starving the app the user is
 * actually looking at.
 */
class ScreenWindowPrioritizationTest {

    @Test
    fun activeWindowMovesToFront() {
        val ordered = prioritizeActive(listOf("launcher", "target", "system")) { it == "target" }
        assertEquals(listOf("target", "launcher", "system"), ordered)
    }

    @Test
    fun inactiveWindowsKeepTheirOriginalOrder() {
        val ordered = prioritizeActive(listOf("a", "b", "c")) { false }
        assertEquals(listOf("a", "b", "c"), ordered)
    }

    @Test
    fun multipleActiveWindowsStayInRelativeOrderBeforeTheInactive() {
        val ordered = prioritizeActive(listOf("a", "b", "c", "d")) { it == "b" || it == "d" }
        assertEquals(listOf("b", "d", "a", "c"), ordered)
    }

    @Test
    fun emptyListPassesThrough() {
        assertEquals(emptyList<String>(), prioritizeActive(emptyList<String>()) { true })
    }
}
