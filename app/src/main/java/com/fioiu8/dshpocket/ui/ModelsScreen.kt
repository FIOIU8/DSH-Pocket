package com.fioiu8.dshpocket.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fioiu8.dshpocket.engine.EngineApi
import com.fioiu8.dshpocket.engine.EngineApi.RpcResult
import com.fioiu8.dshpocket.engine.EngineState
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import com.fioiu8.dshpocket.ui.theme.DshSpacing
import com.fioiu8.dshpocket.ui.theme.dshControl

/**
 * 模型配置（m3e-canvas.json 的 models 帧）。
 *
 * 数据全部来自引擎 RPC：`settings/describe` 读 `agent-default-model`（当前默认模型，
 * 带 revision 做乐观锁）与 `llm-pi-ai`（自定义 OpenAI 兼容 provider 清单及其模型），
 * `credentials/describe|set|unset` 管理 API key（值只写不读）。
 * DeepSeek Account 使用引擎提供的网页登录授权接口，不写入普通 API Key 凭据。
 */
private data class ProviderInfo(
    val id: String,
    val displayName: String,
    val baseURL: String?,
    val api: String,
    val apiKeyEnv: String?,
    val models: List<Pair<String, String>>,
    val modelDetails: Map<String, JSONObject>,
    val headers: Map<String, String>,
)

private data class ProviderDraft(
    val id: String,
    val displayName: String,
    val baseURL: String,
    val api: String,
    val apiKey: String,
    val models: String,
    val headers: String,
    val modelDetails: Map<String, JSONObject>,
)

private val providerProtocols = listOf(
    "openai-completions" to "OpenAI Chat Completions",
    "openai-responses" to "OpenAI Responses",
    "anthropic-messages" to "Anthropic Messages",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(state: EngineState, onMenu: () -> Unit) {
    val ready = state as? EngineState.Ready
    if (ready == null) {
        Scaffold(topBar = { DshTopAppBar(title = "模型配置", onMenu = onMenu) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                CenteredMessage(
                    title = "引擎未就绪",
                    body = (state as? EngineState.Busy)?.message ?: "启动引擎后即可配置模型。",
                    busy = state is EngineState.Busy,
                )
            }
        }
        return
    }

    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    var defaultProvider by remember { mutableStateOf<String?>(null) }
    var defaultModel by remember { mutableStateOf<String?>(null) }
    var modelRevision by remember { mutableStateOf<Long?>(null) }
    var providerRevision by remember { mutableStateOf<Long?>(null) }
    var providers by remember { mutableStateOf<List<ProviderInfo>>(emptyList()) }
    var builtinProviders by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var keyStates by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    var pickingProvider by remember { mutableStateOf(false) }
    var pickingModel by remember { mutableStateOf(false) }
    var keyDialogFor by remember { mutableStateOf<ProviderInfo?>(null) }
    var deleteProvider by remember { mutableStateOf<ProviderInfo?>(null) }
    var editorPage by remember { mutableStateOf(false) }
    var headersPage by remember { mutableStateOf(false) }
    var modelsPage by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderInfo?>(null) }
    var editorId by remember { mutableStateOf("") }
    var editorName by remember { mutableStateOf("") }
    var editorBaseUrl by remember { mutableStateOf("") }
    var editorApi by remember { mutableStateOf(providerProtocols.first().first) }
    var editorApiKey by remember { mutableStateOf("") }
    var editorModels by remember { mutableStateOf("") }
    var editorHeaders by remember { mutableStateOf("") }
    var editorModelDetails by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var discoveredModels by remember { mutableStateOf<List<JSONObject>?>(null) }
    var selectedDiscoveredModels by remember { mutableStateOf<Set<String>>(emptySet()) }
    var officialApiPage by remember { mutableStateOf(false) }
    var officialApiKey by remember { mutableStateOf("") }
    var officialApiKeyRef by remember { mutableStateOf("DEEPSEEK_API_KEY") }
    var accountView by remember { mutableStateOf<JSONObject?>(null) }
    val context = LocalContext.current

    fun openEditor(provider: ProviderInfo?) {
        editingProvider = provider
        editorId = provider?.id.orEmpty()
        editorName = provider?.displayName.orEmpty()
        editorBaseUrl = provider?.baseURL.orEmpty()
        editorApi = provider?.api ?: providerProtocols.first().first
        editorApiKey = ""
        editorModels = provider?.models.orEmpty().joinToString("\n") { (modelId, name) ->
            if (name.isBlank()) modelId else "$modelId|$name"
        }
        editorHeaders = provider?.headers.orEmpty().entries.joinToString("\n") { "${it.key}: ${it.value}" }
        editorModelDetails = provider?.modelDetails.orEmpty().mapValues { JSONObject(it.value.toString()) }
        discoveredModels = null
        selectedDiscoveredModels = emptySet()
        headersPage = false
        modelsPage = false
        error = null
        actionError = null
        editorPage = true
    }

    fun openOfficialApiEditor() {
        officialApiKey = ""
        error = null
        actionError = null
        officialApiPage = true
    }

    fun reload() {
        error = null
        actionError = null
        tick += 1
    }

    fun persistDefault(provider: String, model: String) {
        scope.launch {
            busy = true
            val patch = JSONObject().put("provider", provider).put("model", model)
            val args = JSONObject()
                .put("ns", "agent-default-model")
                .put("patch", patch)
            modelRevision?.let { args.put("expectedRevision", it) }
            val result = EngineApi.rpc(ready.baseUrl, "settings/update", args, ready.token)
            busy = false
            when (result) {
                is RpcResult.Err -> error = "保存失败 ${result.code}: ${result.message}"
                is RpcResult.Ok -> reload()
            }
        }
    }

    fun persistProvider(draft: ProviderDraft, existing: ProviderInfo?) {
        val modelEntries = parseProviderModels(draft.models)
        if (modelEntries == null || modelEntries.isEmpty()) {
            error = "至少添加一个有效模型；每行填写 model-id 或 model-id|显示名称。"
            return
        }
        if (!isHttpEndpoint(draft.baseURL)) {
            error = "请输入有效的 http 或 https 服务地址。"
            return
        }
        if (!Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$").matches(draft.id) ||
            (existing == null && providers.any { it.id == draft.id })
        ) {
            error = "服务 ID 需以小写字母开头，只能包含小写字母、数字和连字符，且不能重复。"
            return
        }
        if (draft.api !in providerProtocols.map { it.first }) {
            error = "不支持的 API 协议。"
            return
        }
        val headers = parseProviderHeaders(draft.headers)
        if (headers == null) {
            error = "Header 格式错误或名称重复。"
            return
        }
        if (defaultProvider == draft.id && defaultModel?.let { model -> modelEntries.none { it.first == model } } == true) {
            error = "当前默认模型将被移除，请先切换默认模型。"
            return
        }

        val keyRef = existing?.apiKeyEnv ?: providerCredentialRef(draft.id)
        scope.launch {
            busy = true
            error = null
            val profile = JSONObject()
                .put("displayName", draft.displayName.trim().ifBlank { draft.id })
                .put("baseURL", draft.baseURL.trim())
                .put("api", draft.api)
                .put("models", JSONArray().apply {
                    modelEntries.forEach { (id, name) ->
                        val model = draft.modelDetails[id]?.let { JSONObject(it.toString()) }
                            ?: existing?.modelDetails?.get(id)?.let { JSONObject(it.toString()) }
                            ?: JSONObject()
                        model.put("id", id)
                        if (name.isNotBlank()) model.put("name", name) else model.remove("name")
                        put(model)
                    }
                })
            if (headers.isNotEmpty()) profile.put("headers", JSONObject(headers))
            if (existing?.apiKeyEnv != null) profile.put("apiKeyEnv", existing.apiKeyEnv)
            if (draft.apiKey.isNotBlank()) profile.put("apiKeyEnv", keyRef)

            val ops = JSONArray()
            if (existing == null) {
                ops.put(JSONObject().put("op", "set").put("path", JSONArray().put("providers").put(draft.id)).put("value", profile))
            } else {
                listOf("displayName", "baseURL", "api", "models").forEach { field ->
                    ops.put(
                        JSONObject()
                            .put("op", "set")
                            .put("path", JSONArray().put("providers").put(draft.id).put(field))
                            .put("value", profile.get(field)),
                    )
                }
                val headersOp = JSONObject()
                    .put("op", if (headers.isEmpty()) "unset" else "set")
                    .put("path", JSONArray().put("providers").put(draft.id).put("headers"))
                if (headers.isNotEmpty()) headersOp.put("value", JSONObject(headers))
                ops.put(headersOp)
                if (draft.apiKey.isNotBlank() && existing.apiKeyEnv == null) {
                    ops.put(
                        JSONObject()
                            .put("op", "set")
                            .put("path", JSONArray().put("providers").put(draft.id).put("apiKeyEnv"))
                            .put("value", keyRef),
                    )
                }
            }
            val args = JSONObject().put("ns", "llm-pi-ai").put("ops", ops)
            providerRevision?.let { args.put("expectedRevision", it) }
            when (val result = EngineApi.rpc(ready.baseUrl, "settings/mutate", args, ready.token)) {
                is RpcResult.Err -> actionError = "保存模型服务失败 ${result.code}: ${result.message}"
                is RpcResult.Ok -> {
                    editorPage = false
                    var credentialError: String? = null
                    if (draft.apiKey.isNotBlank()) {
                        when (val credential = EngineApi.rpc(
                            ready.baseUrl,
                            "credentials/set",
                            JSONObject().put("ref", keyRef).put("value", draft.apiKey.trim()),
                            ready.token,
                        )) {
                            is RpcResult.Err -> credentialError = "服务配置已保存，但 API Key 保存失败：${credential.message}"
                            is RpcResult.Ok -> Unit
                        }
                    }
                    if (credentialError == null) {
                        reload()
                    } else {
                        actionError = credentialError
                        tick += 1
                    }
                }
            }
            busy = false
        }
    }

    fun removeProvider(provider: ProviderInfo) {
        if (defaultProvider == provider.id) {
            error = "请先切换默认模型，再删除当前默认服务。"
            deleteProvider = null
            return
        }
        scope.launch {
            busy = true
            error = null
            val credentialRef = provider.apiKeyEnv?.takeIf { keyStates[it] == true }
            if (credentialRef != null) {
                when (val credential = EngineApi.rpc(
                    ready.baseUrl,
                    "credentials/unset",
                    JSONObject().put("ref", credentialRef),
                    ready.token,
                )) {
                    is RpcResult.Err -> {
                        actionError = "删除 API Key 失败 ${credential.code}: ${credential.message}"
                        busy = false
                        deleteProvider = null
                        return@launch
                    }
                    is RpcResult.Ok -> Unit
                }
            }
            val ops = JSONArray().put(
                JSONObject()
                    .put("op", "unset")
                    .put("path", JSONArray().put("providers").put(provider.id)),
            )
            val args = JSONObject().put("ns", "llm-pi-ai").put("ops", ops)
            providerRevision?.let { args.put("expectedRevision", it) }
            when (val result = EngineApi.rpc(ready.baseUrl, "settings/mutate", args, ready.token)) {
                is RpcResult.Err -> {
                    actionError = "删除模型服务失败 ${result.code}: ${result.message}（API Key 已清除）"
                    tick += 1
                }
                is RpcResult.Ok -> reload()
            }
            busy = false
            deleteProvider = null
        }
    }

    LaunchedEffect(tick) {
        busy = true
        error = null
        val describe = EngineApi.rpc(ready.baseUrl, "settings/describe", EngineApi.emptyArgs(), ready.token)
        if (describe !is RpcResult.Ok) {
            busy = false
            error = (describe as? RpcResult.Err)?.let { "${it.code}: ${it.message}" } ?: "未知错误"
            return@LaunchedEffect
        }
        var loadedProviders = providers
        var resolvedOfficialKeyRef = "DEEPSEEK_API_KEY"
        val namespaces = (describe.value as? JSONObject)?.optJSONArray("namespaces") ?: JSONArray()
        for (i in 0 until namespaces.length()) {
            val ns = namespaces.optJSONObject(i) ?: continue
            when (ns.optString("ns")) {
                "agent-default-model" -> {
                    val value = ns.optJSONObject("value")
                    defaultProvider = value?.optString("provider")?.takeIf { it.isNotBlank() }
                    defaultModel = value?.optString("model")?.takeIf { it.isNotBlank() }
                    if (ns.has("revision")) modelRevision = ns.optLong("revision")
                }

                "llm-pi-ai" -> {
                    if (ns.has("revision")) providerRevision = ns.optLong("revision")
                    val configs = ns.optJSONObject("value")?.optJSONObject("providers")
                    loadedProviders = buildList {
                        configs?.keys()?.forEach { id ->
                            val p = configs.optJSONObject(id) ?: return@forEach
                            val modelConfigs = p.optJSONArray("models")
                            val modelEntries = modelConfigs?.let { models ->
                                (0 until models.length()).mapNotNull { k ->
                                    val model = models.optJSONObject(k) ?: return@mapNotNull null
                                    model.optString("id").takeIf { it.isNotBlank() }
                                        ?.let { it to model.optString("name") }
                                }
                            }.orEmpty()
                            val modelDetails = modelConfigs?.let { models ->
                                buildMap {
                                    for (k in 0 until models.length()) {
                                        val model = models.optJSONObject(k) ?: continue
                                        val modelId = model.optString("id").takeIf { it.isNotBlank() } ?: continue
                                        put(modelId, JSONObject(model.toString()))
                                    }
                                }
                            }.orEmpty()
                            add(
                                ProviderInfo(
                                    id = id,
                                    displayName = p.optString("displayName").ifBlank { id },
                                    baseURL = p.optString("baseURL").takeIf { it.isNotBlank() },
                                    api = p.optString("api").ifBlank { "openai-completions" },
                                    apiKeyEnv = p.optString("apiKeyEnv").takeIf { it.isNotBlank() },
                                    models = modelEntries,
                                    modelDetails = modelDetails,
                                    headers = p.optJSONObject("headers")?.let { headerObject ->
                                        buildMap {
                                            headerObject.keys().forEach { key -> put(key, headerObject.optString(key)) }
                                        }
                                    }.orEmpty(),
                                ),
                            )
                        }
                    }
                }

                "llm-deepseek" -> {
                    resolvedOfficialKeyRef = ns.optJSONObject("value")?.optString("apiKeyEnv")
                        ?.takeIf { it.isNotBlank() } ?: "DEEPSEEK_API_KEY"
                }
            }
        }
        providers = loadedProviders
        officialApiKeyRef = resolvedOfficialKeyRef
        val listProviders = EngineApi.rpc(ready.baseUrl, "llm/listProviders", EngineApi.emptyArgs(), ready.token)
        if (listProviders is RpcResult.Ok) {
            builtinProviders = EngineApi.itemsOf(listProviders.value).let { items ->
                (0 until items.length()).mapNotNull { i ->
                    val p = items.optJSONObject(i) ?: return@mapNotNull null
                    p.optString("id").takeIf { it.isNotBlank() }?.let { it to p.optString("name") }
                }
            }
        }
        val accountState = EngineApi.rpc(ready.baseUrl, "account/getState", EngineApi.emptyArgs(), ready.token)
        accountView = (accountState as? RpcResult.Ok)?.value as? JSONObject
        val officialApiEnabled = builtinProviders.any { it.first == "deepseek-official" }
        val keyRefs = (loadedProviders.mapNotNull { it.apiKeyEnv } +
            if (officialApiEnabled) listOf(resolvedOfficialKeyRef) else emptyList()).distinct()
        if (keyRefs.isNotEmpty()) {
            val refs = JSONArray().apply { keyRefs.forEach { put(it) } }
            val describeKeys = EngineApi.rpc(
                ready.baseUrl,
                "credentials/describe",
                JSONObject().put("refs", refs),
                ready.token,
            )
            if (describeKeys is RpcResult.Ok) {
                keyStates = (describeKeys.value as? JSONObject)?.let { obj ->
                    keyRefs.associateWith { env -> obj.optJSONObject(env)?.optBoolean("configured") ?: false }
                }.orEmpty()
            }
        }
        busy = false
    }

    LaunchedEffect(accountView?.optJSONObject("attempt")?.optString("phase")) {
        val phase = accountView?.optJSONObject("attempt")?.optString("phase")
        if (phase in setOf("initializing", "waiting-browser", "exchanging", "committing")) {
            while (true) {
                delay(1_500)
                val latest = EngineApi.rpc(ready.baseUrl, "account/getState", EngineApi.emptyArgs(), ready.token)
                val value = (latest as? RpcResult.Ok)?.value as? JSONObject ?: break
                accountView = value
                val nextPhase = value.optJSONObject("attempt")?.optString("phase")
                if (nextPhase !in setOf("initializing", "waiting-browser", "exchanging", "committing")) break
            }
        }
    }

    BackHandler(enabled = editorPage || officialApiPage) {
        if (officialApiPage) officialApiPage = false
        else when { headersPage -> headersPage = false; modelsPage -> modelsPage = false; else -> editorPage = false }
    }

    Scaffold(
        topBar = {
            if (officialApiPage) {
                TopAppBar(
                    title = { Text("DeepSeek API") },
                    navigationIcon = {
                        IconButton(onClick = { officialApiPage = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            } else if (editorPage) {
                TopAppBar(
                    title = { Text(when { headersPage -> "自定义 Header"; modelsPage -> "模型列表"; editingProvider == null -> "添加模型服务"; else -> "编辑模型服务" }) },
                    navigationIcon = {
                        IconButton(onClick = { when { headersPage -> headersPage = false; modelsPage -> modelsPage = false; else -> editorPage = false } }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            } else DshTopAppBar(title = "模型配置", onMenu = onMenu)
        },
    ) { padding ->
        if (officialApiPage) {
            DeepSeekApiKeyPage(
                apiKey = officialApiKey,
                onApiKeyChange = { officialApiKey = it },
                credentialRef = officialApiKeyRef,
                configured = keyStates[officialApiKeyRef] == true,
                busy = busy,
                errorMessage = error ?: actionError,
                modifier = Modifier.fillMaxSize().padding(padding),
                onSave = {
                    val value = officialApiKey.trim()
                    if (value.isNotBlank()) scope.launch {
                        busy = true
                        actionError = null
                        when (val result = EngineApi.rpc(
                            ready.baseUrl,
                            "credentials/set",
                            JSONObject().put("ref", officialApiKeyRef).put("value", value),
                            ready.token,
                        )) {
                            is RpcResult.Err -> actionError = "保存 DeepSeek API Key 失败：${result.message}"
                            is RpcResult.Ok -> {
                                officialApiKey = ""
                                officialApiPage = false
                                reload()
                            }
                        }
                        busy = false
                    }
                },
                onClear = {
                    scope.launch {
                        busy = true
                        actionError = null
                        when (val result = EngineApi.rpc(
                            ready.baseUrl,
                            "credentials/unset",
                            JSONObject().put("ref", officialApiKeyRef),
                            ready.token,
                        )) {
                            is RpcResult.Err -> actionError = "清除 DeepSeek API Key 失败：${result.message}"
                            is RpcResult.Ok -> {
                                officialApiPage = false
                                reload()
                            }
                        }
                        busy = false
                    }
                },
            )
            return@Scaffold
        }
        if (editorPage) {
            ProviderEditorPage(
                provider = editingProvider,
                id = editorId,
                onIdChange = { editorId = it },
                displayName = editorName,
                onDisplayNameChange = { editorName = it },
                baseUrl = editorBaseUrl,
                onBaseUrlChange = { editorBaseUrl = it },
                api = editorApi,
                onApiChange = { editorApi = it },
                apiKey = editorApiKey,
                onApiKeyChange = { editorApiKey = it },
                models = editorModels,
                onModelsChange = { editorModels = it },
                modelDetails = editorModelDetails,
                discoveredModels = discoveredModels,
                selectedDiscoveredModels = selectedDiscoveredModels,
                onSelectedDiscoveredModelsChange = { selectedDiscoveredModels = it },
                onDismissDiscoveredModels = { discoveredModels = null },
                onDiscoverModels = {
                    scope.launch {
                        busy = true
                        actionError = null
                        val request = JSONObject()
                            .put("baseURL", editorBaseUrl.trim())
                            .put("api", editorApi)
                        editingProvider?.let { request.put("provider", it.id) }
                        editorApiKey.trim().takeIf { it.isNotBlank() }?.let { request.put("apiKey", it) }
                        when (val result = EngineApi.rpc(
                            ready.baseUrl,
                            "llm/discoverModels",
                            JSONObject().put("settingsNs", "llm-pi-ai").put("request", request),
                            ready.token,
                        )) {
                            is RpcResult.Err -> actionError = "获取模型列表失败：${result.message}"
                            is RpcResult.Ok -> {
                                val models = (result.value as? JSONArray)?.let { array ->
                                    (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let { model -> JSONObject(model.toString()) } }
                                }.orEmpty()
                                if (models.isEmpty()) actionError = "服务没有返回可用模型。"
                                else {
                                    val currentIds = parseProviderModels(editorModels).orEmpty().map { it.first }.toSet()
                                    discoveredModels = models
                                    selectedDiscoveredModels = models.mapNotNull { it.optString("id").takeIf(String::isNotBlank) }
                                        .filterNot { it in currentIds }.toSet()
                                }
                            }
                        }
                        busy = false
                    }
                },
                onApplyDiscoveredModels = {
                    val candidates = discoveredModels.orEmpty().filter { it.optString("id") in selectedDiscoveredModels }
                    val current = parseProviderModels(editorModels).orEmpty().toMutableList()
                    val known = current.map { it.first }.toMutableSet()
                    val details = editorModelDetails.toMutableMap()
                    candidates.forEach { model ->
                        val id = model.optString("id").takeIf { it.isNotBlank() } ?: return@forEach
                        if (known.add(id)) {
                            current += id to model.optString("name")
                            details[id] = JSONObject().put("id", id).apply {
                                model.optString("name").takeIf { it.isNotBlank() }?.let { put("name", it) }
                                if (model.has("contextWindow")) put("contextWindow", model.optInt("contextWindow"))
                                if (model.has("maxTokens")) put("maxTokens", model.optInt("maxTokens"))
                                model.optJSONArray("inputModalities")?.let { put("input", JSONArray(it.toString())) }
                            }
                        }
                    }
                    editorModelDetails = details
                    editorModels = encodeProviderModels(current)
                    discoveredModels = null
                },
                headers = editorHeaders,
                onHeadersChange = { editorHeaders = it },
                headersPage = headersPage,
                onHeadersPage = { headersPage = it },
                modelsPage = modelsPage,
                onModelsPage = { modelsPage = it },
                errorMessage = error ?: actionError,
                takenIds = providers.map { it.id },
                defaultProvider = defaultProvider,
                defaultModel = defaultModel,
                busy = busy,
                modifier = Modifier.fillMaxSize().padding(padding),
                onSave = { draft -> persistProvider(draft, editingProvider) },
            )
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            (error ?: actionError)?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page, vertical = 8.dp),
                )
            }

            SectionHeader("当前模型")
            GroupedList(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DshSpacing.page),
                items = listOf {
                    ListItem(
                        headlineContent = {
                            Text(
                                "${defaultProvider ?: "未设置"} / ${defaultModel ?: "未设置"}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        supportingContent = {
                            Text("新会话将默认使用这个模型；已存在会话各自保留自己的选择。")
                        },
                        trailingContent = {
                            Icon(
                                Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        modifier = Modifier.heightIn(min = DshSpacing.touchTarget).clickable { pickingProvider = true },
                    )
                },
            )

            SectionHeader("模型服务")
            OutlinedButton(
                onClick = { openEditor(null) },
                enabled = !busy,
                modifier = Modifier.padding(horizontal = DshSpacing.page),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("添加自定义服务", modifier = Modifier.padding(start = 8.dp))
            }
            GroupedList(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DshSpacing.page),
                items = providers.map { provider ->
                    {
                        Column(
                            Modifier.padding(DshSpacing.row),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(provider.displayName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = { openEditor(provider) }, enabled = !busy) {
                                    Icon(Icons.Filled.Edit, contentDescription = "编辑 ${provider.displayName}")
                                }
                                IconButton(onClick = { deleteProvider = provider }, enabled = !busy) {
                                    Icon(Icons.Filled.DeleteOutline, contentDescription = "删除 ${provider.displayName}")
                                }
                            }
                            Text(
                                "${provider.id} · ${providerProtocols.firstOrNull { it.first == provider.api }?.second ?: provider.api}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            provider.baseURL?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = provider.models.joinToString("、") { it.second.ifBlank { it.first } }
                                    .ifBlank { "（未配置模型清单）" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            provider.apiKeyEnv?.let { env ->
                                val configured = keyStates[env] == true
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.Key,
                                        contentDescription = null,
                                        tint = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                    Text(
                                        text = if (configured) "API Key：已配置" else "API Key：未配置",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    )
                                    TextButton(onClick = { keyDialogFor = provider }, modifier = Modifier.dshControl()) {
                                        Text(if (configured) "更换" else "设置")
                                    }
                                    if (configured) {
                                        TextButton(onClick = {
                                            scope.launch {
                                                busy = true
                                                when (val result = EngineApi.rpc(
                                                    ready.baseUrl,
                                                    "credentials/unset",
                                                    JSONObject().put("ref", env),
                                                    ready.token,
                                                )) {
                                                    is RpcResult.Err -> actionError = "清除 API Key 失败：${result.message}"
                                                    is RpcResult.Ok -> reload()
                                                }
                                                busy = false
                                            }
                                        }, modifier = Modifier.dshControl()) { Text("清除") }
                                    }
                                }
                            }
                        }
                    }
                },
            )

            if (builtinProviders.any { it.first == "deepseek-official" }) {
                val configured = keyStates[officialApiKeyRef] == true
                GroupedList(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page),
                    items = listOf {
                        ListItem(
                            headlineContent = { Text("DeepSeek API") },
                            supportingContent = {
                                Text(if (configured) "API Key：已配置" else "API Key：未配置")
                            },
                            trailingContent = {
                                TextButton(onClick = { openOfficialApiEditor() }, enabled = !busy) {
                                    Text(if (configured) "更换" else "设置")
                                }
                            },
                            modifier = Modifier.clickable(enabled = !busy) {
                                openOfficialApiEditor()
                            },
                        )
                    },
                )
            }

            val builtinOnly = builtinProviders.filter { (id, _) ->
                id != "deepseek-account" && id != "deepseek-official" && providers.none { it.id == id }
            }
            if (builtinOnly.isNotEmpty()) {
                GroupedList(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DshSpacing.page),
                    items = builtinOnly.map { (id, name) ->
                        {
                            ListItem(
                                headlineContent = { Text("内置：$name") },
                                trailingContent = { Text(id) },
                            )
                        }
                    },
                )
            }

            if (builtinProviders.any { it.first == "deepseek-account" }) {
                val status = accountView?.optString("status") ?: "signed-out"
                val phase = accountView?.optJSONObject("attempt")?.optString("phase")
                val signInRunning = phase in setOf("initializing", "waiting-browser", "exchanging", "committing")
                GroupedList(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page),
                    items = listOf {
                        Column(
                            Modifier.padding(DshSpacing.row),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("DeepSeek Account", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (status == "credential-stored") "已授权" else if (signInRunning) "等待网页登录" else "未授权",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (status == "credential-stored") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                when (phase) {
                                    "initializing" -> "正在准备网页登录…"
                                    "waiting-browser" -> "请在浏览器中完成 DeepSeek 登录。"
                                    "exchanging", "committing" -> "正在确认账号授权…"
                                    "failed" -> "授权失败：${accountView?.optJSONObject("attempt")?.optString("errorCode") ?: "未知错误"}"
                                    "expired" -> "授权已过期，请重新登录。"
                                    else -> "使用 DeepSeek 网页账号授权，不需要填写 API Key。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    enabled = !busy && !signInRunning,
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            val now = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
                                            val client = JSONObject()
                                                .put("version", "DSHPocket Android")
                                                .put("locale", java.util.Locale.getDefault().toLanguageTag())
                                                .put("timezoneOffsetSeconds", now)
                                            when (val result = EngineApi.rpc(
                                                ready.baseUrl,
                                                "account/startSignIn",
                                                JSONObject()
                                                    .put("client", client)
                                                    .put("callbackOrigin", ready.baseUrl.trimEnd('/'))
                                                    .put("loginSource", "desktop"),
                                                ready.token,
                                            )) {
                                                is RpcResult.Err -> actionError = "启动 DeepSeek 网页授权失败：${result.message}"
                                                is RpcResult.Ok -> {
                                                    accountView = result.value as? JSONObject
                                                    val authorizeUrl = accountView?.optJSONObject("attempt")?.optString("authorizeUrl")
                                                    if (!authorizeUrl.isNullOrBlank()) {
                                                        runCatching {
                                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorizeUrl)))
                                                        }.onFailure { actionError = "无法打开系统浏览器：${it.message}" }
                                                    }
                                                }
                                            }
                                            busy = false
                                        }
                                    },
                                ) { Text(if (signInRunning) "授权中" else if (status == "credential-stored") "重新授权" else "网页登录授权") }
                                if (signInRunning) {
                                    TextButton(
                                        enabled = !busy,
                                        onClick = {
                                            val attemptId = accountView?.optJSONObject("attempt")?.optString("id")
                                            if (!attemptId.isNullOrBlank()) scope.launch {
                                                busy = true
                                                when (val result = EngineApi.rpc(
                                                    ready.baseUrl,
                                                    "account/cancelSignIn",
                                                    JSONObject().put("attemptId", attemptId),
                                                    ready.token,
                                                )) {
                                                    is RpcResult.Err -> actionError = "取消授权失败：${result.message}"
                                                    is RpcResult.Ok -> accountView = result.value as? JSONObject
                                                }
                                                busy = false
                                            }
                                        },
                                    ) { Text("取消授权") }
                                }
                                if (status == "credential-stored") {
                                    TextButton(
                                        enabled = !busy,
                                        onClick = {
                                            scope.launch {
                                                busy = true
                                                val now = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
                                                val client = JSONObject()
                                                    .put("version", "DSHPocket Android")
                                                    .put("locale", java.util.Locale.getDefault().toLanguageTag())
                                                    .put("timezoneOffsetSeconds", now)
                                                when (val result = EngineApi.rpc(
                                                    ready.baseUrl,
                                                    "account/signOut",
                                                    JSONObject().put("client", client),
                                                    ready.token,
                                                )) {
                                                    is RpcResult.Err -> actionError = "退出 DeepSeek Account 失败：${result.message}"
                                                    is RpcResult.Ok -> accountView = result.value as? JSONObject
                                                }
                                                busy = false
                                            }
                                        },
                                    ) { Text("退出账号") }
                                }
                            }
                        }
                    },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(
                text = "API Key 只写入本机引擎的凭据存储，读取接口不会返回明文。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = DshSpacing.page),
            )
        }
    }

    if (pickingProvider) {
        RadioDialog(
            title = "选择模型服务",
            options = providers.map { it.id to it.displayName },
            selected = defaultProvider,
            onDismiss = { pickingProvider = false },
            onPick = { picked ->
                pickingProvider = false
                val target = providers.firstOrNull { it.id == picked }
                // 换 provider 时若原默认模型不在新清单里，回落到该清单第一项。
                val model = target?.models?.firstOrNull { it.first == defaultModel }?.first
                    ?: target?.models?.firstOrNull()?.first
                if (target != null && model != null) persistDefault(target.id, model)
            },
        )
    }
    if (pickingModel) {
        val current = providers.firstOrNull { it.id == defaultProvider }
        if (current != null && current.models.isNotEmpty()) {
            RadioDialog(
                title = "选择模型",
                options = current.models.map { it.first to it.second.ifBlank { it.first } },
                selected = defaultModel,
                onDismiss = { pickingModel = false },
                onPick = { picked ->
                    pickingModel = false
                    persistDefault(current.id, picked)
                },
            )
        } else {
            pickingModel = false
        }
    }

    keyDialogFor?.let { provider ->
        var keyValue by remember(provider.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { keyDialogFor = null },
            title = { Text("设置 ${provider.displayName} 的 API Key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "将写入凭据 ${provider.apiKeyEnv}；保存后即可在对话中使用。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = keyValue,
                        onValueChange = { keyValue = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        placeholder = { Text("sk-…") },
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = keyValue.isNotBlank(), onClick = {
                    val value = keyValue.trim()
                    keyDialogFor = null
                    scope.launch {
                        busy = true
                        when (val result = EngineApi.rpc(
                            ready.baseUrl,
                            "credentials/set",
                            JSONObject().put("ref", provider.apiKeyEnv).put("value", value),
                            ready.token,
                        )) {
                            is RpcResult.Err -> actionError = "保存 API Key 失败：${result.message}"
                            is RpcResult.Ok -> reload()
                        }
                        busy = false
                    }
                }, modifier = Modifier.dshControl()) { Text("保存") }
            },
        dismissButton = { TextButton(onClick = { keyDialogFor = null }, modifier = Modifier.dshControl()) { Text("取消") } },
        )
    }

    deleteProvider?.let { provider ->
        val isDefault = defaultProvider == provider.id
        AlertDialog(
            onDismissRequest = { if (!busy) deleteProvider = null },
            title = { Text("删除模型服务？") },
            text = {
                Text(
                    if (isDefault) "${provider.displayName} 是当前默认服务。请先切换默认模型，再删除。"
                    else "将删除 ${provider.displayName} 的服务配置和本机保存的 API Key。此操作不可撤销。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { removeProvider(provider) },
                    enabled = !busy && !isDefault,
                    modifier = Modifier.dshControl(),
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteProvider = null }, modifier = Modifier.dshControl()) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DeepSeekApiKeyPage(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    credentialRef: String,
    configured: Boolean,
    busy: Boolean,
    errorMessage: String?,
    modifier: Modifier = Modifier,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = DshSpacing.page, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("DeepSeek 官方 API", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            if (configured) "API Key 已配置。重新输入密钥即可更换。" else "填写 DeepSeek API Key 以启用官方模型。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            label = { Text("API Key") },
            placeholder = { Text("sk-…") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("凭据引用：$credentialRef。密钥保存在本机 DSH 凭据库中，读取接口不会返回明文。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSave, enabled = !busy && apiKey.isNotBlank()) {
                Text(if (busy) "保存中…" else if (configured) "更换 API Key" else "保存 API Key")
            }
            if (configured) {
                TextButton(onClick = onClear, enabled = !busy, modifier = Modifier.dshControl()) { Text("清除") }
            }
        }
    }
}

@Composable
private fun ProviderEditorPage(
    provider: ProviderInfo?,
    id: String,
    onIdChange: (String) -> Unit,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    api: String,
    onApiChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    models: String,
    onModelsChange: (String) -> Unit,
    modelDetails: Map<String, JSONObject>,
    discoveredModels: List<JSONObject>?,
    selectedDiscoveredModels: Set<String>,
    onSelectedDiscoveredModelsChange: (Set<String>) -> Unit,
    onDismissDiscoveredModels: () -> Unit,
    onDiscoverModels: () -> Unit,
    onApplyDiscoveredModels: () -> Unit,
    headers: String,
    onHeadersChange: (String) -> Unit,
    headersPage: Boolean,
    onHeadersPage: (Boolean) -> Unit,
    modelsPage: Boolean,
    onModelsPage: (Boolean) -> Unit,
    errorMessage: String?,
    takenIds: List<String>,
    defaultProvider: String?,
    defaultModel: String?,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onSave: (ProviderDraft) -> Unit,
) {
    var protocolMenu by remember { mutableStateOf(false) }
    var modelRows by remember(modelsPage) {
        mutableStateOf(parseProviderModels(models).orEmpty().ifEmpty { listOf("" to "") })
    }
    val parsedModels = parseProviderModels(models)
    val parsedHeaders = parseProviderHeaders(headers)
    val idValid = id.matches(Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")) && (provider != null || id !in takenIds)
    val defaultModelRemoved = provider?.id == defaultProvider && defaultModel?.let { selected ->
        parsedModels?.none { it.first == selected }
    } == true
    val canSave = !busy && idValid && isHttpEndpoint(baseUrl) && api in providerProtocols.map { it.first } &&
        !parsedModels.isNullOrEmpty() && parsedHeaders != null && !defaultModelRemoved

    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = DshSpacing.page, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (headersPage) {
            Text("为兼容服务添加自定义请求头。每行填写 Header-Name: value。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = headers,
                onValueChange = onHeadersChange,
                label = { Text("请求头") },
                placeholder = { Text("X-Organization: team-a") },
                supportingText = { Text("留空表示不使用自定义 Header") },
                minLines = 5,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (modelsPage) {
            Text("模型 ID 必填，显示名称可选。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = onDiscoverModels,
                enabled = !busy && isHttpEndpoint(baseUrl),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "正在获取…" else "获取可用模型")
            }
            if (!isHttpEndpoint(baseUrl)) {
                Text("填写有效的服务地址后即可获取模型。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            modelRows.forEachIndexed { index, (modelId, modelName) ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = modelId,
                            onValueChange = { nextValue ->
                                val next = modelRows.toMutableList().also { it[index] = nextValue to modelName }
                                modelRows = next
                                onModelsChange(encodeProviderModels(next))
                            },
                            label = { Text("模型 ID") },
                            placeholder = { Text("deepseek-chat") },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = modelName,
                            onValueChange = { nextValue ->
                                val next = modelRows.toMutableList().also { it[index] = modelId to nextValue }
                                modelRows = next
                                onModelsChange(encodeProviderModels(next))
                            },
                            label = { Text("显示名称（可选）") },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    IconButton(
                        onClick = {
                            val next = modelRows.toMutableList().also { it.removeAt(index) }
                            modelRows = next
                            onModelsChange(encodeProviderModels(next))
                        },
                        enabled = !busy,
                    ) { Icon(Icons.Filled.DeleteOutline, contentDescription = "删除模型") }
                }
            }
            OutlinedButton(
                onClick = { modelRows = modelRows + ("" to "") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("添加模型", modifier = Modifier.padding(start = 8.dp))
            }
            when {
                parsedModels == null -> Text("模型 ID 不能为空，也不能重复。", color = MaterialTheme.colorScheme.error)
                else -> Text("${parsedModels.size} 个模型", style = MaterialTheme.typography.labelMedium)
            }
        } else {
            OutlinedTextField(
                value = id,
                onValueChange = onIdChange,
                label = { Text("服务 ID") },
                placeholder = { Text("my-gateway") },
                supportingText = { Text(if (provider == null) "小写字母开头，可含数字和连字符" else "服务 ID 创建后不可更改") },
                singleLine = true,
                enabled = !busy && provider == null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = displayName,
                onValueChange = onDisplayNameChange,
                label = { Text("显示名称") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                label = { Text("服务地址") },
                placeholder = { Text("https://api.example.com/v1") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Column {
                Text("API 协议", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = { protocolMenu = true }, enabled = !busy) {
                    Text(providerProtocols.firstOrNull { it.first == api }?.second ?: api)
                }
                DropdownMenu(expanded = protocolMenu, onDismissRequest = { protocolMenu = false }) {
                    providerProtocols.forEach { (value, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { onApiChange(value); protocolMenu = false })
                    }
                }
            }
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("API Key${if (provider == null) "（可选）" else "（留空则不更换）"}") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            ListItem(
                headlineContent = { Text("自定义 Header") },
                supportingContent = { Text(headers.lineSequence().count { it.isNotBlank() }.let { if (it == 0) "未设置" else "$it 项请求头" }) },
                trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable(enabled = !busy) { onHeadersPage(true) },
            )
            ListItem(
                headlineContent = { Text("模型列表") },
                supportingContent = { Text(parsedModels?.size?.let { "$it 个模型" } ?: "请填写有效模型") },
                trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable(enabled = !busy) { onModelsPage(true) },
            )
            when {
                id.isNotBlank() && !idValid -> Text("服务 ID 格式错误或已存在。", color = MaterialTheme.colorScheme.error)
                baseUrl.isNotBlank() && !isHttpEndpoint(baseUrl) -> Text("服务地址需为有效的 http 或 https URL。", color = MaterialTheme.colorScheme.error)
                models.isNotBlank() && parsedModels == null -> Text("模型 ID 不能为空，也不能重复。", color = MaterialTheme.colorScheme.error)
                parsedModels.isNullOrEmpty() -> Text("请至少填写一个模型。", color = MaterialTheme.colorScheme.error)
                parsedHeaders == null -> Text("Header 格式错误或名称重复。", color = MaterialTheme.colorScheme.error)
                defaultModelRemoved -> Text("这会移除当前默认模型，请先切换默认模型。", color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    onSave(ProviderDraft(id, displayName, baseUrl, api, apiKey, models, headers, modelDetails))
                }, enabled = canSave) {
                    Text(if (busy) "保存中…" else "保存")
                }
            }
        }
    }
    discoveredModels?.let { candidates ->
        AlertDialog(
            onDismissRequest = onDismissDiscoveredModels,
            title = { Text("选择要添加的模型") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    candidates.forEach { model ->
                        val modelId = model.optString("id")
                        if (modelId.isNotBlank()) {
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    onSelectedDiscoveredModelsChange(
                                        if (modelId in selectedDiscoveredModels) selectedDiscoveredModels - modelId
                                        else selectedDiscoveredModels + modelId,
                                    )
                                }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = modelId in selectedDiscoveredModels,
                                    onCheckedChange = { checked ->
                                        onSelectedDiscoveredModelsChange(
                                            if (checked) selectedDiscoveredModels + modelId else selectedDiscoveredModels - modelId,
                                        )
                                    },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(model.optString("name").ifBlank { modelId })
                                    if (model.optString("name").isNotBlank()) {
                                        Text(modelId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    val contextWindow = model.optInt("contextWindow", 0)
                                    if (contextWindow > 0) Text(
                                        "上下文 ${contextWindow}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onApplyDiscoveredModels,
                    enabled = selectedDiscoveredModels.isNotEmpty(),
                    modifier = Modifier.dshControl(),
                ) { Text("添加 ${selectedDiscoveredModels.size} 个") }
            },
            dismissButton = {
                TextButton(onClick = onDismissDiscoveredModels, modifier = Modifier.dshControl()) { Text("取消") }
            },
        )
    }
}

internal fun parseProviderModels(value: String): List<Pair<String, String>>? {
    val entries = value.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
        val parts = line.split('|', limit = 2)
        parts[0].trim() to parts.getOrNull(1).orEmpty().trim()
    }.toList()
    if (entries.any { it.first.isBlank() } || entries.map { it.first }.distinct().size != entries.size) return null
    return entries
}

private fun encodeProviderModels(models: List<Pair<String, String>>): String =
    models.joinToString("\n") { (id, name) -> if (name.isBlank()) id else "$id|$name" }.trimEnd()

private fun parseProviderHeaders(value: String): Map<String, String>? {
    val entries = mutableListOf<Pair<String, String>>()
    for (line in value.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }) {
        val separator = line.indexOf(':')
        if (separator <= 0) return null
        val name = line.substring(0, separator).trim()
        val content = line.substring(separator + 1).trim()
        if (!name.matches(Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")) || content.isBlank()) return null
        entries += name to content
    }
    if (entries.map { it.first.lowercase(Locale.ROOT) }.distinct().size != entries.size) return null
    return entries.toMap()
}

private fun isHttpEndpoint(value: String): Boolean = runCatching {
    val endpoint = java.net.URI(value.trim())
    (endpoint.scheme == "https" || endpoint.scheme == "http") && !endpoint.host.isNullOrBlank()
}.getOrDefault(false)

private fun providerCredentialRef(id: String): String = "${id.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]+"), "_")}_API_KEY"

@Composable
private fun RadioDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = DshSpacing.touchTarget)
                            .clickable(onClick = { onPick(value) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = { onPick(value) })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.dshControl()) { Text("取消") } },
    )
}
