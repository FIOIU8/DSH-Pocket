package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.screen.ScreenAction
import com.fioiu8.dshpocket.screen.ScreenKey
import com.fioiu8.dshpocket.screen.ScreenOpRequest
import com.fioiu8.dshpocket.screen.ScreenOpRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the deterministic first stage of the adjudication pipeline: check order is
 * fixed (plan §2.3), "look" operations skip the deny list, and every refusal carries a
 * message the AI can read.
 */
class ScreenOpRulesTest {

    private val enabledState = ScreenOpRules.DeviceState(
        enabled = true,
        emergencyStopped = false,
        accessibilityReady = true,
        overlayAllowed = true,
        keyguardLocked = false,
        foregroundPackage = "com.example.launcher",
        screenWidth = 720,
        screenHeight = 1280,
    )

    private fun rate(now: Long = 1_000L, recent: List<Long> = emptyList(), session: Int = 0) =
        ScreenOpRules.RateState(now, recent, session)

    private fun tap(x: Int = 100, y: Int = 200) = ScreenOpRequest(ScreenAction.TAP, "测试", x = x, y = y)

    private fun blockMessage(verdict: ScreenOpRules.Verdict): String = (verdict as ScreenOpRules.Verdict.Block).message

    @Test
    fun `pass through for a plain tap`() {
        assertEquals(ScreenOpRules.Verdict.Pass, ScreenOpRules.check(tap(), enabledState, rate(), emptySet()))
    }

    @Test
    fun `permission ask passes while its own prerequisites are off`() {
        // The whole point of request_permission: it must go through when the
        // accessibility service and the overlay grant are exactly what is missing.
        val ask = ScreenOpRequest(ScreenAction.REQUEST_PERMISSION, "需要操作屏幕", permission = com.fioiu8.dshpocket.screen.ScreenPermission.ACCESSIBILITY)
        assertEquals(
            ScreenOpRules.Verdict.Pass,
            ScreenOpRules.check(ask, enabledState.copy(accessibilityReady = false, overlayAllowed = false), rate(), emptySet()),
        )
    }

    @Test
    fun `permission ask needs the permission parameter`() {
        val ask = ScreenOpRequest(ScreenAction.REQUEST_PERMISSION, "需要操作屏幕")
        assertTrue(blockMessage(ScreenOpRules.check(ask, enabledState, rate(), emptySet())).contains("request_permission"))
    }

    @Test
    fun `screen permission asks stay behind the master switch`() {
        val ask = ScreenOpRequest(ScreenAction.REQUEST_PERMISSION, "需要操作屏幕", permission = com.fioiu8.dshpocket.screen.ScreenPermission.ACCESSIBILITY)
        assertTrue(blockMessage(ScreenOpRules.check(ask, enabledState.copy(enabled = false), rate(), emptySet())).contains("关闭"))
    }

    @Test
    fun `all files ask survives a disabled screen control`() {
        val ask = ScreenOpRequest(ScreenAction.REQUEST_PERMISSION, "访问共享工作区", permission = com.fioiu8.dshpocket.screen.ScreenPermission.ALL_FILES)
        assertEquals(ScreenOpRules.Verdict.Pass, ScreenOpRules.check(ask, enabledState.copy(enabled = false), rate(), emptySet()))
    }

    @Test
    fun `permission ask is rate limited like an operation`() {
        val ask = ScreenOpRequest(ScreenAction.REQUEST_PERMISSION, "需要操作屏幕", permission = com.fioiu8.dshpocket.screen.ScreenPermission.ACCESSIBILITY)
        val burst = ScreenOpRules.RateState(now = 1_000L, recentTimestamps = listOf(1_000L, 900L), sessionCount = 0)
        assertTrue(blockMessage(ScreenOpRules.check(ask, enabledState, burst, emptySet())).contains("频率"))
    }

    @Test
    fun `permission ask is refused while locked`() {
        val ask = ScreenOpRequest(ScreenAction.REQUEST_PERMISSION, "需要操作屏幕", permission = com.fioiu8.dshpocket.screen.ScreenPermission.ACCESSIBILITY)
        assertTrue(blockMessage(ScreenOpRules.check(ask, enabledState.copy(keyguardLocked = true), rate(), emptySet())).contains("锁屏"))
    }

    @Test
    fun `master switch is checked first`() {
        val verdict = ScreenOpRules.check(tap(), enabledState.copy(enabled = false), rate(), emptySet())
        assertTrue(blockMessage(verdict).contains("关闭"))
    }

    @Test
    fun `emergency stop blocks until engine restart`() {
        val verdict = ScreenOpRules.check(tap(), enabledState.copy(emergencyStopped = true), rate(), emptySet())
        assertTrue(blockMessage(verdict).contains("紧急停止"))
    }

    @Test
    fun `accessibility and overlay prerequisites block before anything else user-visible`() {
        assertTrue(blockMessage(ScreenOpRules.check(tap(), enabledState.copy(accessibilityReady = false), rate(), emptySet())).contains("无障碍"))
        assertTrue(blockMessage(ScreenOpRules.check(tap(), enabledState.copy(overlayAllowed = false), rate(), emptySet())).contains("悬浮窗"))
    }

    @Test
    fun `locked device is refused`() {
        val verdict = ScreenOpRules.check(tap(), enabledState.copy(keyguardLocked = true), rate(), emptySet())
        assertTrue(blockMessage(verdict).contains("锁屏"))
    }

    @Test
    fun `deny list blocks act operations on the foreground package`() {
        val verdict = ScreenOpRules.check(tap(), enabledState.copy(foregroundPackage = "com.eg.android.AlipayGphone"), rate(), setOf("com.eg.android.AlipayGphone"))
        assertTrue(blockMessage(verdict).contains("拒绝名单"))
    }

    @Test
    fun `deny list does not apply to look operations`() {
        val read = ScreenOpRequest(ScreenAction.READ, "看看当前页面")
        val screenshot = ScreenOpRequest(ScreenAction.SCREENSHOT, "截屏参考")
        val alipay = setOf("com.eg.android.AlipayGphone")
        assertEquals(ScreenOpRules.Verdict.Pass, ScreenOpRules.check(read, enabledState.copy(foregroundPackage = "com.eg.android.AlipayGphone"), rate(), alipay))
        assertEquals(ScreenOpRules.Verdict.Pass, ScreenOpRules.check(screenshot, enabledState.copy(foregroundPackage = "com.eg.android.AlipayGphone"), rate(), alipay))
    }

    @Test
    fun `unknown foreground package is not blocked by the deny list`() {
        assertEquals(ScreenOpRules.Verdict.Pass, ScreenOpRules.check(tap(), enabledState.copy(foregroundPackage = null), rate(), setOf("com.eg.android.AlipayGphone")))
    }

    @Test
    fun `parameter validation per action`() {
        val missingCoords = ScreenOpRules.check(ScreenOpRequest(ScreenAction.TAP, "测试"), enabledState, rate(), emptySet())
        assertTrue(blockMessage(missingCoords).contains("x 与 y"))

        val offScreen = ScreenOpRules.check(tap(x = 5_000, y = 100), enabledState, rate(), emptySet())
        assertTrue(blockMessage(offScreen).contains("超出屏幕"))

        val negative = ScreenOpRules.check(tap(x = -1, y = 100), enabledState, rate(), emptySet())
        assertTrue(blockMessage(negative).contains("超出屏幕"))

        val noText = ScreenOpRules.check(ScreenOpRequest(ScreenAction.TEXT, "测试"), enabledState, rate(), emptySet())
        assertTrue(blockMessage(noText).contains("text"))

        val longText = ScreenOpRules.check(ScreenOpRequest(ScreenAction.TEXT, "测试", text = "a".repeat(513)), enabledState, rate(), emptySet())
        assertTrue(blockMessage(longText).contains("512"))

        val noKey = ScreenOpRules.check(ScreenOpRequest(ScreenAction.KEY, "测试"), enabledState, rate(), emptySet())
        assertTrue(blockMessage(noKey).contains("key"))

        val badKey = ScreenOpRules.check(ScreenOpRequest(ScreenAction.KEY, "测试", key = ScreenKey.BACK), enabledState, rate(), emptySet())
        assertEquals(ScreenOpRules.Verdict.Pass, badKey)

        val swipeOk = ScreenOpRules.check(ScreenOpRequest(ScreenAction.SWIPE, "测试", x = 10, y = 10, x2 = 600, y2 = 1_100), enabledState, rate(), emptySet())
        assertEquals(ScreenOpRules.Verdict.Pass, swipeOk)

        val swipeOff = ScreenOpRules.check(ScreenOpRequest(ScreenAction.SWIPE, "测试", x = 10, y = 10, x2 = 5_000, y2 = 1_100), enabledState, rate(), emptySet())
        assertTrue(blockMessage(swipeOff).contains("超出屏幕"))
    }

    @Test
    fun `bounds validation tolerates display metrics chrome slack`() {
        // displayMetrics can under-report the real panel by system bars; a quarter of
        // the dimension is the slack, so exactly-height coordinates are fine.
        val verdict = ScreenOpRules.check(tap(x = 719, y = 1_279), enabledState, rate(), emptySet())
        assertEquals(ScreenOpRules.Verdict.Pass, verdict)
    }

    @Test
    fun `rate limit allows two per second and blocks the third`() {
        val now = 10_000L
        // One op already inside the window: the next is the second, still allowed.
        val second = ScreenOpRules.check(tap(), enabledState, rate(now, listOf(now - 200)), emptySet())
        assertEquals(ScreenOpRules.Verdict.Pass, second)
        // Two inside the window: the next one is the third and is refused.
        val third = ScreenOpRules.check(tap(), enabledState, rate(now, listOf(now - 200, now - 400)), emptySet())
        assertTrue(blockMessage(third).contains("频率"))
    }

    @Test
    fun `timestamps older than one second do not count against the rate`() {
        val now = 10_000L
        val verdict = ScreenOpRules.check(tap(), enabledState, rate(now, listOf(now - 1_100, now - 1_500)), emptySet())
        assertEquals(ScreenOpRules.Verdict.Pass, verdict)
    }

    @Test
    fun `session limit blocks the sixty-first operation`() {
        val verdict = ScreenOpRules.check(tap(), enabledState, rate(session = 60), emptySet())
        assertTrue(blockMessage(verdict).contains("60"))
        val atLimit = ScreenOpRules.check(tap(), enabledState, rate(session = 59), emptySet())
        assertEquals(ScreenOpRules.Verdict.Pass, atLimit)
    }

    @Test
    fun `session limit outranks rate limit in the message order`() {
        // Both violated: the session message wins because it is checked first.
        val verdict = ScreenOpRules.check(tap(), enabledState, rate(now = 10_000, recent = listOf(9_900, 9_800), session = 60), emptySet())
        assertTrue(blockMessage(verdict).contains("60"))
    }
}
