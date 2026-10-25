package com.fioiu8.dshpocket.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

/**
 * The hands and eyes of the feature (plan §2.4): reads the active window tree, injects
 * gestures, commits text, presses system keys and (API 30+) captures the screen.
 *
 * The instance is exposed through a static weak reference — the system owns the service
 * lifecycle, the executor only borrows it while an operation runs. The user turning the
 * service off in system settings makes [peek] return null and every operation degrades
 * to RULE_BLOCKED, which is the documented "capability off" behavior.
 */
class PocketAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PocketAccessibility"
        const val GESTURE_TIMEOUT_MS = 10_000L
        const val SCREENSHOT_TIMEOUT_MS = 5_000L
        const val KEY_TIMEOUT_MS = 2_000L
        const val MAX_TREE_NODES = 200
        const val MAX_WALK_DEPTH = 40
        const val MAX_NODE_TEXT = 120
        const val TAP_DURATION_MS = 50L
        const val SWIPE_SPEED_PX_PER_MS = 2f
        const val MIN_SWIPE_MS = 250L
        const val MAX_SWIPE_MS = 1_500L

        /** Long-side cap of the model-facing screenshot copy; keeps it far below the harness attachment limits. */
        const val MAX_MODEL_IMAGE_LONG_SIDE = 1152
        const val MODEL_IMAGE_JPEG_QUALITY = 80

        @Volatile
        private var instance: WeakReference<PocketAccessibilityService>? = null

        fun peek(): PocketAccessibilityService? = instance?.get()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastWindowPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        // Interactive windows give read() the whole window stack instead of one root.
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    override fun onDestroy() {
        if (instance?.get() === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        lastWindowPackage = pkg
    }

    override fun onInterrupt() {}

    /** Best-effort foreground package: window-state events, falling back to window roots. */
    fun foregroundPackageNow(): String? {
        lastWindowPackage?.let { return it }
        return try {
            windows.firstOrNull()?.root?.packageName?.toString()
                ?: rootInActiveWindow?.packageName?.toString()
        } catch (_: Throwable) {
            null
        }
    }

    /** Physical display size used by the rule stage for bounds validation. */
    fun displaySize(): Pair<Int, Int> = resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    /** Short text of the node under the point — the reviewer's "target summary" for taps. */
    fun targetTextAt(x: Int, y: Int): String? {
        var found: String? = null
        forEachNode(rootInActiveWindow, 0) { node ->
            if (found == null) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.contains(x, y)) {
                    found = (node.text?.toString() ?: node.contentDescription?.toString())?.takeIf { it.isNotBlank() }
                }
            }
        }
        return found?.take(MAX_NODE_TEXT)
    }

    /** Package name plus the first few texts of the active window — the reviewer's summary for reads. */
    fun activeWindowSummary(): String? {
        val root = rootInActiveWindow ?: return null
        val texts = ArrayList<String>()
        forEachNode(root, 0) { node ->
            if (texts.size < 3) {
                (node.text?.toString() ?: node.contentDescription?.toString())
                    ?.takeIf { it.isNotBlank() }
                    ?.let { texts.add(it.take(60)) }
            }
        }
        return (root.packageName?.toString().orEmpty() + " " + texts.joinToString(" / ")).trim().take(MAX_NODE_TEXT)
    }

    // ---------------------------------------------------------------- read

    fun readScreen(): ScreenOpResult = try {
        val roots = collectRoots()
        if (roots.isEmpty()) {
            ScreenOpResult.execFailed("当前没有可读的窗口内容（可能刚切换屏幕，请稍后重试）")
        } else {
            ScreenOpResult.Ok(buildTreeJson(roots))
        }
    } catch (t: Throwable) {
        ScreenOpResult.execFailed("读屏失败：${t.message ?: t.javaClass.simpleName}")
    }

    private fun collectRoots(): List<WindowRoot> {
        val fromWindows = try {
            windows.mapNotNull { window ->
                // AccessibilityWindowInfo carries no package name; the root node does.
                window.root?.let { root ->
                    WindowRoot(root.packageName?.toString() ?: "?", root, window.isActive)
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
        if (fromWindows.isNotEmpty()) return fromWindows
        return rootInActiveWindow?.let { root ->
            listOf(WindowRoot(root.packageName?.toString() ?: "?", root, active = true))
        } ?: emptyList()
    }

    private data class WindowRoot(
        val packageName: String,
        val node: AccessibilityNodeInfo,
        val active: Boolean,
    )

    private fun buildTreeJson(roots: List<WindowRoot>): JSONObject {
        val windowArray = JSONArray()
        var count = 0
        var truncated = false
        for (root in prioritizeActive(roots) { it.active }) {
            if (count >= MAX_TREE_NODES) {
                truncated = true
                break
            }
            val budget = MAX_TREE_NODES - count
            val nodes = collectNodes(root.node, budget)
            count += nodes.size
            if (nodes.size >= budget) truncated = true
            val nodeArray = JSONArray()
            nodes.forEach { nodeArray.put(nodeJson(it.node, it.depth)) }
            windowArray.put(JSONObject().put("package", root.packageName).put("nodes", nodeArray))
        }
        return JSONObject()
            .put("windows", windowArray)
            .put("nodeCount", count)
            .put("truncated", truncated)
    }

    /** Breadth of the tree is unbounded; the budget keeps the JSON reply small and the walk fast. */
    private fun collectNodes(root: AccessibilityNodeInfo, budget: Int): List<NodeAtDepth> {
        val out = ArrayList<NodeAtDepth>()
        fun recurse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_WALK_DEPTH || out.size >= budget) return
            out.add(NodeAtDepth(node, depth))
            for (index in 0 until node.childCount) {
                if (out.size >= budget) return
                try {
                    recurse(node.getChild(index), depth + 1)
                } catch (_: Throwable) {
                    // A window can be torn down mid-walk; keep what we already have.
                }
            }
        }
        recurse(root, 0)
        return out
    }

    private data class NodeAtDepth(val node: AccessibilityNodeInfo, val depth: Int)

    private fun nodeJson(node: AccessibilityNodeInfo, depth: Int): JSONObject {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val info = JSONObject()
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { info.put("text", it.take(MAX_NODE_TEXT)) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { info.put("desc", it.take(MAX_NODE_TEXT)) }
        node.viewIdResourceName?.let { info.put("id", it) }
        node.className?.toString()?.substringAfterLast('.')?.let { info.put("class", it) }
        info.put("depth", depth)
        if (node.isClickable) info.put("clickable", true)
        if (node.isScrollable) info.put("scrollable", true)
        if (node.isEditable) info.put("editable", true)
        info.put(
            "bounds",
            JSONObject()
                .put("l", bounds.left)
                .put("t", bounds.top)
                .put("r", bounds.right)
                .put("b", bounds.bottom),
        )
        return info
    }

    // ---------------------------------------------------------------- gestures

    fun tap(x: Int, y: Int): ScreenOpResult = gesture {
        GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(pointPath(x, y), 0, TAP_DURATION_MS))
            .build()
    }

    fun swipe(x: Int, y: Int, x2: Int, y2: Int): ScreenOpResult = gesture {
        val distance = hypot((x2 - x).toDouble(), (y2 - y).toDouble()).toFloat()
        val duration = (distance / SWIPE_SPEED_PX_PER_MS).toInt().coerceIn(MIN_SWIPE_MS.toInt(), MAX_SWIPE_MS.toInt())
        GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(linePath(x, y, x2, y2), 0, duration.toLong()))
            .build()
    }

    private fun gesture(build: () -> GestureDescription): ScreenOpResult {
        val latch = CountDownLatch(1)
        var completed = false
        val dispatched = try {
            dispatchGesture(build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            }, null)
        } catch (t: Throwable) {
            Log.w(TAG, "dispatchGesture threw", t)
            false
        }
        if (!dispatched) return ScreenOpResult.execFailed("系统拒绝注入手势（无障碍服务可能尚未就绪）")
        val finished = try {
            latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        return if (finished) {
            if (completed) {
                ScreenOpResult.ok("performed", true)
            } else {
                ScreenOpResult.execFailed("手势被系统取消（弹窗、锁屏或系统手势拦截）")
            }
        } else {
            ScreenOpResult.execFailed("手势执行超时")
        }
    }

    private fun pointPath(x: Int, y: Int): Path = linePath(x, y, x + 1, y + 1)

    private fun linePath(x: Int, y: Int, x2: Int, y2: Int): Path = Path().apply {
        moveTo(x.toFloat(), y.toFloat())
        lineTo(x2.toFloat(), y2.toFloat())
    }

    // ---------------------------------------------------------------- keys

    fun pressKey(key: ScreenKey): ScreenOpResult {
        val action = when (key) {
            ScreenKey.BACK -> GLOBAL_ACTION_BACK
            ScreenKey.HOME -> GLOBAL_ACTION_HOME
            ScreenKey.RECENTS -> GLOBAL_ACTION_RECENTS
        }
        val latch = CountDownLatch(1)
        var accepted = false
        // performGlobalAction is documented thread-safe, but latching through the main
        // thread keeps the ordering with gesture dispatch predictable.
        mainHandler.post {
            accepted = try {
                performGlobalAction(action)
            } catch (_: Throwable) {
                false
            }
            latch.countDown()
        }
        val finished = try {
            latch.await(KEY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        return if (finished && accepted) {
            ScreenOpResult.ok("performed", true)
        } else {
            ScreenOpResult.execFailed("系统键执行失败或超时")
        }
    }

    // ---------------------------------------------------------------- text

    fun inputText(text: String, x: Int?, y: Int?): ScreenOpResult {
        val target = findEditableTarget(x, y)
            ?: return ScreenOpResult.execFailed("没有找到可输入的控件；请先 tap 目标输入框获得焦点，再调用 text")
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return try {
            if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                ScreenOpResult.ok("typed", text.length)
            } else {
                ScreenOpResult.execFailed("目标控件拒绝了文本设置（可能不是文本输入框）")
            }
        } catch (t: Throwable) {
            ScreenOpResult.execFailed("输入失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    /**
     * Focused node first, then an editable node at the requested point (deepest match
     * wins), then any editable node on screen. There is no synthetic-IME channel behind
     * a gesture, so "type without any target" has no meaningful fallback and fails with
     * advice — the plan's "逐字手势" degradation is unimplementable (recorded in the doc).
     */
    private fun findEditableTarget(x: Int?, y: Int?): AccessibilityNodeInfo? {
        try {
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focus ->
                if (focus.isEditable) return focus
            }
        } catch (_: Throwable) {
        }
        if (x != null && y != null) {
            findEditableAt(rootInActiveWindow, x, y, 0)?.let { return it }
        }
        var firstEditable: AccessibilityNodeInfo? = null
        forEachNode(rootInActiveWindow, 0) { node ->
            if (firstEditable == null && node.isEditable) firstEditable = node
        }
        return firstEditable
    }

    private fun findEditableAt(node: AccessibilityNodeInfo?, x: Int, y: Int, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_WALK_DEPTH) return null
        for (index in 0 until node.childCount) {
            try {
                findEditableAt(node.getChild(index), x, y, depth + 1)?.let { return it }
            } catch (_: Throwable) {
            }
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return if (node.isEditable && bounds.contains(x, y)) node else null
    }

    private fun forEachNode(node: AccessibilityNodeInfo?, depth: Int, visit: (AccessibilityNodeInfo) -> Unit) {
        if (node == null || depth > MAX_WALK_DEPTH) return
        visit(node)
        for (index in 0 until node.childCount) {
            try {
                forEachNode(node.getChild(index), depth + 1, visit)
            } catch (_: Throwable) {
            }
        }
    }

    // ---------------------------------------------------------------- screenshot

    fun takeScreenshot(destinationDir: File): ScreenOpResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ScreenOpResult.execFailed("截屏需要 Android 11（API 30）以上，此设备不支持")
        }
        val latch = CountDownLatch(1)
        var success: ScreenshotResult? = null
        var errorCode = -1
        try {
            // The display-less overload is absent from the current SDK stubs; the default
            // display is the screen the user is looking at.
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                java.util.concurrent.Executor { runnable -> runnable.run() },
                object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    success = screenshot
                    latch.countDown()
                }

                override fun onFailure(error: Int) {
                    errorCode = error
                    latch.countDown()
                }
            })
        } catch (t: Throwable) {
            return ScreenOpResult.execFailed("截屏调用失败：${t.message ?: t.javaClass.simpleName}")
        }
        val finished = try {
            latch.await(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!finished) return ScreenOpResult.execFailed("截屏超时")
        val screenshot = success ?: return ScreenOpResult.execFailed("系统拒绝截屏（错误码 $errorCode）")
        val hardware = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
        if (hardware == null) {
            screenshot.hardwareBuffer.close()
            return ScreenOpResult.execFailed("截屏数据无法转换为图像")
        }
        val software = try {
            hardware.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Throwable) {
            null
        } finally {
            screenshot.hardwareBuffer.close()
        }
        if (software == null) return ScreenOpResult.execFailed("截屏数据无法转换为图像")
        val cacheFile = File(applicationContext.cacheDir, "screen-${SystemClock.uptimeMillis()}.png")
        try {
            FileOutputStream(cacheFile).use { out -> software.compress(Bitmap.CompressFormat.PNG, 100, out) }
            destinationDir.mkdirs()
            // The suffix keeps two captures in the same millisecond from overwriting each other.
            val destination = File(destinationDir, "screen-${System.currentTimeMillis()}-${Random.nextInt(1000)}.png")
            cacheFile.copyTo(destination, overwrite = true)
            cacheFile.delete()
            val data = JSONObject().put("path", destination.absolutePath)
            encodeModelImage(software)?.let { data.put("image", it) }
            return ScreenOpResult.Ok(data)
        } catch (t: Throwable) {
            return ScreenOpResult.execFailed("截屏保存失败：${t.message ?: t.javaClass.simpleName}")
        } finally {
            software.recycle()
        }
    }

    /**
     * Model-facing copy of the screenshot: downscaled to [MAX_MODEL_IMAGE_LONG_SIDE] and
     * JPEG-compressed so it stays far below the harness attachment limits (the engine
     * admits it without re-encoding) and the base64 bridge reply stays small. A failure
     * here degrades the result to the saved PNG path, never fails the capture.
     */
    private fun encodeModelImage(source: Bitmap): JSONObject? = try {
        val longSide = maxOf(source.width, source.height)
        val scale = if (longSide > MAX_MODEL_IMAGE_LONG_SIDE) {
            MAX_MODEL_IMAGE_LONG_SIDE.toFloat() / longSide
        } else {
            1f
        }
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            null
        }
        val bitmap = scaled ?: source
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, MODEL_IMAGE_JPEG_QUALITY, out)
            out.toByteArray()
        }
        val image = JSONObject()
            .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("mediaType", "image/jpeg")
            .put("width", bitmap.width)
            .put("height", bitmap.height)
        if (scaled != null) scaled.recycle()
        image
    } catch (_: Throwable) {
        null
    }
}
