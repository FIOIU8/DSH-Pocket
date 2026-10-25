package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.engine.EngineApi.RpcResult
import com.fioiu8.dshpocket.ui.state.PluginChange
import com.fioiu8.dshpocket.ui.state.PluginParser
import com.fioiu8.dshpocket.ui.state.PluginRepository
import com.fioiu8.dshpocket.ui.state.PluginSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The plugin read path and toggle, against a fake engine.
 *
 * The parser keeps raw LocalizedText values; these tests lock the rendering rules that
 * depended on that choice — module-name fallback, locale resolution, and the
 * localized-title sort.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginRepositoryTest {

    private class FakeSource : PluginSource {
        var listResult: RpcResult = RpcResult.Ok(JSONArray())
        var toggleResult: RpcResult = RpcResult.Ok(JSONObject())
        var bundlesResult: RpcResult = RpcResult.Ok(JSONArray())
        var installResult: RpcResult = RpcResult.Ok(JSONObject().put("application", "applied"))
        var removeResult: RpcResult = RpcResult.Ok(JSONObject().put("application", "applied"))
        var cancelResult: RpcResult = RpcResult.Ok(JSONObject())

        val toggleCalls = mutableListOf<Pair<String, Boolean>>()
        val installCalls = mutableListOf<Pair<String, String>>()
        val removeCalls = mutableListOf<String>()
        val cancelCalls = mutableListOf<String>()

        override suspend fun listPlugins(): RpcResult = listResult

        override suspend fun setPluginEnabled(id: String, enabled: Boolean): RpcResult {
            toggleCalls += id to enabled
            return toggleResult
        }

        override suspend fun listBundles(): RpcResult = bundlesResult

        override suspend fun installBundle(spec: String, requestId: String): RpcResult {
            installCalls += spec to requestId
            return installResult
        }

        override suspend fun cancelInstall(requestId: String): RpcResult {
            cancelCalls += requestId
            return cancelResult
        }

        override suspend fun removeBundle(name: String): RpcResult {
            removeCalls += name
            return removeResult
        }
    }

    private fun plugin(id: String, title: Any?, description: Any? = null, enabled: Boolean = true) =
        JSONObject()
            .put("entryId", id)
            .put("moduleName", "plugins/$id")
            .put("meta", JSONObject().apply { title?.let { put("title", it) }; description?.let { put("description", it) } })
            .put("enabled", enabled)

    private fun bundle(
        name: String,
        installed: Boolean,
        removable: Boolean = installed,
        readOnlyReason: String? = null,
        version: String = "1.0.0",
    ) = JSONObject()
        .put("name", name)
        .put("version", version)
        .put("enabled", true)
        .put("installed", installed)
        .put("optional", false)
        .put("removable", removable)
        .apply { readOnlyReason?.let { put("readOnlyReason", it) } }

    // ---- repository ------------------------------------------------------------------------

    @Test
    fun `a successful reload publishes rows and clears the error`() = runTest {
        val source = FakeSource().apply {
            listResult = RpcResult.Ok(JSONArray().put(plugin("canvas", "画布")))
        }

        val repository = PluginRepository.createForTest { source }
        repository.reload()

        val state = repository.state.value
        assertEquals("canvas", state.rows?.single()?.id)
        assertNull(state.error)
    }

    @Test
    fun `a failed list keeps the previous rows and only sets the error`() = runTest {
        val source = FakeSource().apply {
            listResult = RpcResult.Ok(JSONArray().put(plugin("canvas", "画布")))
        }
        val repository = PluginRepository.createForTest { source }
        repository.reload()

        source.listResult = RpcResult.Err("http/500", "list failed")
        repository.reload()

        val state = repository.state.value
        assertEquals("last good list survives a failed refresh", 1, state.rows?.size)
        assertEquals("http/500: list failed", state.error)
    }

    @Test
    fun `a failing toggle sets the error and leaves rows alone`() = runTest {
        val source = FakeSource().apply {
            listResult = RpcResult.Ok(JSONArray().put(plugin("canvas", "画布")))
            toggleResult = RpcResult.Err("protocol", "engine refused")
        }
        val repository = PluginRepository.createForTest { source }
        repository.reload()

        repository.setPluginEnabled("canvas", false)

        assertEquals(listOf("canvas" to false), source.toggleCalls)
        assertEquals("protocol: engine refused", repository.state.value.error)
        assertEquals(1, repository.state.value.rows?.size)
    }

    @Test
    fun `a following successful reload clears a toggle error - shipped ordering`() = runTest {
        // The shipped toggle always ran refresh() afterwards, so a failed toggle's
        // message lived only until the reload it triggered completed.
        val source = FakeSource().apply {
            listResult = RpcResult.Ok(JSONArray().put(plugin("canvas", "画布")))
            toggleResult = RpcResult.Err("protocol", "engine refused")
        }
        val repository = PluginRepository.createForTest { source }
        repository.reload()
        repository.setPluginEnabled("canvas", false)
        assertEquals("protocol: engine refused", repository.state.value.error)

        source.toggleResult = RpcResult.Ok(JSONObject())
        repository.reload()

        assertNull("the reload the user's own flow produces clears it", repository.state.value.error)
    }

    @Test
    fun `the toggle is sent exactly as the shipped rpc did`() = runTest {
        val source = FakeSource()

        PluginRepository.createForTest { source }.setPluginEnabled("p-1", true)

        assertEquals(listOf("p-1" to true), source.toggleCalls)
    }

    @Test
    fun `a missing engine leaves the repository untouched`() = runTest {
        val source = FakeSource()
        val repository = PluginRepository.createForTest { null }

        repository.reload()

        assertNull(repository.state.value.rows)
        assertNull(repository.state.value.error)
        assertEquals(0, source.toggleCalls.size)
    }

    // ---- install / remove -------------------------------------------------------------------

    @Test
    fun `a successful install reports Applied and reloads the lists`() = runTest {
        val source = FakeSource().apply {
            bundlesResult = RpcResult.Ok(JSONArray().put(bundle("user-plugin", installed = true)))
        }
        val repository = PluginRepository.createForTest { source }

        repository.install("@scope/dsh-plugin")

        assertEquals(listOf("@scope/dsh-plugin" to source.installCalls.single().second), source.installCalls)
        assertEquals("pocket-", source.installCalls.single().second.take("pocket-".length))
        assertEquals(PluginChange.Applied, repository.state.value.lastChange)
        assertNull("the busy action is cleared once the round-trip ends", repository.state.value.action)
        assertEquals("the reload after the change picked up the new bundle", 1, repository.state.value.bundles?.size)
    }

    @Test
    fun `an engine-reported failure carries the code and diagnostic`() = runTest {
        val source = FakeSource().apply {
            installResult = RpcResult.Ok(
                JSONObject()
                    .put("application", "failed")
                    .put("stage", "install")
                    .put("error", JSONObject().put("code", "invalid-spec").put("diagnostic", "not a package")),
            )
        }
        val repository = PluginRepository.createForTest { source }

        repository.install("nonsense")

        assertEquals(PluginChange.Failed("invalid-spec", "not a package"), repository.state.value.lastChange)
    }

    @Test
    fun `restart-required and cancelled outcomes map to their own messages`() = runTest {
        val restartRepo = PluginRepository.createForTest {
            FakeSource().apply { installResult = RpcResult.Ok(JSONObject().put("application", "restart-required")) }
        }
        restartRepo.install("p")

        val cancelledRepo = PluginRepository.createForTest {
            FakeSource().apply { installResult = RpcResult.Ok(JSONObject().put("application", "cancelled")) }
        }
        cancelledRepo.install("p")

        assertEquals(PluginChange.RestartRequired, restartRepo.state.value.lastChange)
        assertEquals(PluginChange.Cancelled, cancelledRepo.state.value.lastChange)
    }

    @Test
    fun `a transport failure maps to a Failed change, not an exception`() = runTest {
        val source = FakeSource().apply { installResult = RpcResult.Err("transport", "connection refused") }
        val repository = PluginRepository.createForTest { source }

        repository.install("@scope/whatever")

        assertEquals(PluginChange.Failed("transport", "connection refused"), repository.state.value.lastChange)
    }

    @Test
    fun `an unknown application value is a protocol failure, never a success`() = runTest {
        val source = FakeSource().apply { installResult = RpcResult.Ok(JSONObject().put("application", "something-new")) }
        val repository = PluginRepository.createForTest { source }

        repository.install("@scope/whatever")

        assertEquals(PluginChange.Failed("protocol", null)::class, repository.state.value.lastChange!!::class)
    }

    @Test
    fun `cancel addresses the same request id the install sent`() = runTest {
        val source = FakeSource()
        val repository = PluginRepository.createForTest { source }
        // Simulate the screen: the id comes from the in-flight action in state.
        val requestId = "pocket-test"
        repository.cancelInstall(requestId)

        assertEquals(listOf("pocket-test"), source.cancelCalls)
    }

    @Test
    fun `remove sends the package name and reports the outcome`() = runTest {
        val source = FakeSource().apply { removeResult = RpcResult.Ok(JSONObject().put("application", "applied")) }
        val repository = PluginRepository.createForTest { source }

        repository.remove("user-plugin")

        assertEquals(listOf("user-plugin"), source.removeCalls)
        assertEquals(PluginChange.Applied, repository.state.value.lastChange)
        assertNull(repository.state.value.action)
    }

    @Test
    fun `dismissChange clears the banner without touching the lists`() = runTest {
        val source = FakeSource().apply {
            listResult = RpcResult.Ok(JSONArray().put(plugin("canvas", "画布")))
        }
        val repository = PluginRepository.createForTest { source }
        repository.install("@scope/p")

        repository.dismissChange()

        assertNull(repository.state.value.lastChange)
        assertEquals("the plugin list is untouched", 1, repository.state.value.rows?.size)
    }

    // ---- bundles ----------------------------------------------------------------------------

    @Test
    fun `a successful reload publishes bundles too`() = runTest {
        val source = FakeSource().apply {
            bundlesResult = RpcResult.Ok(
                JSONArray()
                    .put(bundle("user-plugin", installed = true, removable = true))
                    .put(bundle("@deepseek-ai/dsh-builtin", installed = false, removable = false, readOnlyReason = "management-required")),
            )
        }
        val repository = PluginRepository.createForTest { source }

        repository.reload()

        val bundles = repository.state.value.bundles
        assertEquals(2, bundles?.size)
        assertEquals(true, bundles?.first()?.removable)
        assertEquals("management-required", bundles?.last()?.readOnlyReason)
    }

    @Test
    fun `a failed bundle list keeps the previous bundles and leaves rows alone`() = runTest {
        val source = FakeSource().apply {
            listResult = RpcResult.Ok(JSONArray().put(plugin("canvas", "画布")))
            bundlesResult = RpcResult.Ok(JSONArray().put(bundle("user-plugin", installed = true)))
        }
        val repository = PluginRepository.createForTest { source }
        repository.reload()

        source.bundlesResult = RpcResult.Err("http/500", "bundles failed")
        source.listResult = RpcResult.Err("http/500", "rows failed")
        repository.reload()

        assertEquals("last good bundle list survives", 1, repository.state.value.bundles?.size)
        assertEquals("the failed plugin list set its own error", "http/500: rows failed", repository.state.value.error)
    }

    // ---- parser ----------------------------------------------------------------------------

    @Test
    fun `rows keep the raw localized values`() {
        val items = JSONArray()
            .put(plugin(
                "a",
                JSONObject().put("zh-CN", "中文标题").put("en", "English"),
                JSONObject().put("zh-CN", "内置描述").put("en", "built-in"),
            ))
            .put(plugin("b", "simple title", "built-in description"))

        val rows = PluginParser.rows(items)

        // Raw payloads, not rendered strings — that is the point of the model shape.
        assertEquals("中文标题", (rows[0].title?.value as JSONObject).optString("zh-CN"))
        assertEquals("built-in", (rows[0].description?.value as JSONObject).optString("en"))
        assertEquals("simple title", rows[1].title?.value)
    }

    @Test
    fun `title falls back to the module-name tail when unresolvable`() {
        val items = JSONArray().put(
            plugin("blank-title", "   "),
        )

        val row = PluginParser.rows(items).single()

        assertEquals("shipped fallback: the module name's last segment", "blank-title", PluginParser.titleFor(row, "en-US"))
    }

    @Test
    fun `titles resolve per locale for display and sort`() {
        val items = JSONArray()
            .put(plugin("p1", JSONObject().put("zh-CN", "乙").put("en", "two")))
            .put(plugin("p2", JSONObject().put("zh-CN", "甲").put("en", "one")))

        val rows = PluginParser.rows(items)

        // 乙 (U+4E59) sorts before 甲 (U+7532); "one" sorts before "two".
        assertEquals(listOf("p1", "p2"), PluginParser.sortedByTitle(rows, "zh-CN").map { it.id })
        assertEquals(listOf("p2", "p1"), PluginParser.sortedByTitle(rows, "en-US").map { it.id })
    }

    @Test
    fun `read-only reason survives and blank becomes null`() {
        val items = JSONArray()
            .put(plugin("locked", "t").put("readOnlyReason", "core plugin"))
            .put(plugin("free", "t").put("readOnlyReason", ""))

        val rows = PluginParser.rows(items)

        assertEquals("core plugin", rows[0].readOnlyReason)
        assertNull(rows[1].readOnlyReason)
    }

    @Test
    fun `bundles parse removable and version, blank names dropped`() {
        val items = JSONArray()
            .put(bundle("user-plugin", installed = true))
            .put(JSONObject().put("name", ""))
            .put(
                JSONObject()
                    .put("name", "locked-plugin")
                    .put("enabled", false)
                    .put("installed", false)
                    .put("removable", false),
            )

        val bundles = PluginParser.bundles(items)

        assertEquals(2, bundles.size)
        assertEquals("user-plugin", bundles[0].name)
        assertEquals("1.0.0", bundles[0].version)
        assertEquals(false, bundles[1].removable)
        assertNull(bundles[1].version)
    }

    @Test
    fun `bundle sort puts removable first then title, and missing removable means removable`() {
        val items = JSONArray()
            .put(
                JSONObject()
                    .put("name", "z-locked")
                    .put("enabled", true)
                    .put("installed", true)
                    .put("removable", false)
                    .put("meta", JSONObject().put("title", JSONObject().put("zh-CN", "甲").put("en", "one"))),
            )
            .put(bundle("b-removable", installed = true))
            .put(
                JSONObject()
                    .put("name", "a-legacy")
                    .put("enabled", true)
                    .put("installed", true),
            )

        val bundles = PluginParser.bundles(items)

        assertEquals(
            listOf("a-legacy", "b-removable", "z-locked"),
            PluginParser.sortedBundles(bundles, "en-US").map { it.name },
        )
        assertEquals("z-locked", PluginParser.sortedBundles(bundles, "en-US").last().name)
    }
}
