package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.engine.EngineApi.RpcResult
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

        val toggleCalls = mutableListOf<Pair<String, Boolean>>()

        override suspend fun listPlugins(): RpcResult = listResult

        override suspend fun setPluginEnabled(id: String, enabled: Boolean): RpcResult {
            toggleCalls += id to enabled
            return toggleResult
        }
    }

    private fun plugin(id: String, title: Any?, description: Any? = null, enabled: Boolean = true) =
        JSONObject()
            .put("entryId", id)
            .put("moduleName", "plugins/$id")
            .put("meta", JSONObject().apply { title?.let { put("title", it) }; description?.let { put("description", it) } })
            .put("enabled", enabled)

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
}
