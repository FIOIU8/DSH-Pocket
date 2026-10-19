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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
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
import com.fioiu8.dshpocket.ui.state.PROVIDER_PROTOCOLS as providerProtocols
import com.fioiu8.dshpocket.ui.state.ModelRepository
import com.fioiu8.dshpocket.ui.state.ProviderDraft
import com.fioiu8.dshpocket.ui.state.ProviderInfo
import com.fioiu8.dshpocket.ui.state.encodeProviderModels
import com.fioiu8.dshpocket.ui.state.isHttpEndpoint
import com.fioiu8.dshpocket.ui.state.isValidProviderId
import com.fioiu8.dshpocket.ui.state.parseProviderHeaders
import com.fioiu8.dshpocket.ui.state.parseProviderModels
import com.fioiu8.dshpocket.ui.state.providerCredentialRef

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(state: EngineState, onMenu: () -> Unit, modelRepository: ModelRepository) {
    val ready = state as? EngineState.Ready
    if (ready == null) {
        Scaffold(topBar = { DshTopAppBar(title = stringResource(com.fioiu8.dshpocket.R.string.nav_models), onMenu = onMenu) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                CenteredMessage(
                    title = stringResource(com.fioiu8.dshpocket.R.string.engine_not_ready),
                    body = (state as? EngineState.Busy)?.message ?: stringResource(com.fioiu8.dshpocket.R.string.engine_start_to_configure_models),
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

    // The model repository owns the read path. `collectAsState` keeps every read fresh
    // through the delegate, both in render scope and inside write callbacks - the same
    // property the replaced `by remember` delegates had.
    val modelState by modelRepository.state.collectAsState()

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
    val context = LocalContext.current

    // Resolved here, in composable scope, then captured. `stringResource` cannot be
    // called from a LaunchedEffect block or a callback lambda, and reading the string
    // through LocalContext there would not recompose when the language changes.
    val labelSaveApiKey = stringResource(com.fioiu8.dshpocket.R.string.models_save_api_key)
    val labelAddOneError = stringResource(com.fioiu8.dshpocket.R.string.models_add_one_error)
    val labelUrlError = stringResource(com.fioiu8.dshpocket.R.string.models_url_error)
    val labelServiceIdHint = stringResource(com.fioiu8.dshpocket.R.string.models_service_id_hint)
    val labelProtocolError = stringResource(com.fioiu8.dshpocket.R.string.models_protocol_error)
    val labelHeadersError = stringResource(com.fioiu8.dshpocket.R.string.models_headers_error)
    val labelDefaultRemovedError = stringResource(com.fioiu8.dshpocket.R.string.models_default_removed_error)
    val labelDefaultServiceDeleteError = stringResource(com.fioiu8.dshpocket.R.string.models_default_service_delete_error)
    val labelNoModelsReturned = stringResource(com.fioiu8.dshpocket.R.string.models_no_models_returned)
    val labelBrowserFailed = stringResource(com.fioiu8.dshpocket.R.string.models_browser_failed)
    val labelStartLoginFailed = stringResource(com.fioiu8.dshpocket.R.string.models_start_login_failed)
    val labelSignoutFailed = stringResource(com.fioiu8.dshpocket.R.string.models_signout_failed)
    val labelCancelLoginFailed = stringResource(com.fioiu8.dshpocket.R.string.models_cancel_login_failed)
    val labelClear = stringResource(com.fioiu8.dshpocket.R.string.models_clear)

    // Format-arg variants: the arguments (engine error codes) only exist inside the
    // lambda, so what is captured is a formatter rather than a finished string. The
    // `Resources` handle comes from `LocalResources`, which recomposes with
    // configuration changes, rather than from `LocalContext.current`, which does not.
    val resources = LocalResources.current
    fun formatResource(id: Int, vararg args: Any): String = resources.getString(id, *args)


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
            modelState.settings.modelRevision?.let { args.put("expectedRevision", it) }
            val result = EngineApi.rpc(ready.baseUrl, "settings/update", args, ready.token)
            busy = false
            when (result) {
                is RpcResult.Err -> error = formatResource(com.fioiu8.dshpocket.R.string.models_save_error, result.code, result.message)
                is RpcResult.Ok -> reload()
            }
        }
    }

    fun persistProvider(draft: ProviderDraft, existing: ProviderInfo?) {
        val modelEntries = parseProviderModels(draft.models)
        if (modelEntries == null || modelEntries.isEmpty()) {
            error = labelAddOneError
            return
        }
        if (!isHttpEndpoint(draft.baseURL)) {
            error = labelUrlError
            return
        }
        if (!isValidProviderId(draft.id) ||
            (existing == null && modelState.settings.providers.any { it.id == draft.id })
        ) {
            error = labelServiceIdHint
            return
        }
        if (draft.api !in providerProtocols.map { it.first }) {
            error = labelProtocolError
            return
        }
        val headers = parseProviderHeaders(draft.headers)
        if (headers == null) {
            error = labelHeadersError
            return
        }
        if (modelState.settings.defaultProvider == draft.id && modelState.settings.defaultModel?.let { model -> modelEntries.none { it.first == model } } == true) {
            error = labelDefaultRemovedError
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
            modelState.settings.providerRevision?.let { args.put("expectedRevision", it) }
            when (val result = EngineApi.rpc(ready.baseUrl, "settings/mutate", args, ready.token)) {
                is RpcResult.Err -> actionError = formatResource(com.fioiu8.dshpocket.R.string.models_save_service_error, result.code, result.message)
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
                            is RpcResult.Err -> credentialError = formatResource(com.fioiu8.dshpocket.R.string.models_key_save_error, credential.message)
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
        if (modelState.settings.defaultProvider == provider.id) {
            error = labelDefaultServiceDeleteError
            deleteProvider = null
            return
        }
        scope.launch {
            busy = true
            error = null
            val credentialRef = provider.apiKeyEnv?.takeIf { modelState.keyStates[it] == true }
            if (credentialRef != null) {
                when (val credential = EngineApi.rpc(
                    ready.baseUrl,
                    "credentials/unset",
                    JSONObject().put("ref", credentialRef),
                    ready.token,
                )) {
                    is RpcResult.Err -> {
                        actionError = formatResource(com.fioiu8.dshpocket.R.string.models_delete_key_error, credential.code, credential.message)
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
            modelState.settings.providerRevision?.let { args.put("expectedRevision", it) }
            when (val result = EngineApi.rpc(ready.baseUrl, "settings/mutate", args, ready.token)) {
                is RpcResult.Err -> {
                    actionError = formatResource(com.fioiu8.dshpocket.R.string.models_delete_service_error, result.code, result.message)
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
        modelRepository.reload()
        error = modelRepository.state.value.error
        busy = false
    }

    LaunchedEffect(modelState.accountView?.optJSONObject("attempt")?.optString("phase")) {
        val phase = modelState.accountView?.optJSONObject("attempt")?.optString("phase")
        if (phase in setOf("initializing", "waiting-browser", "exchanging", "committing")) {
            while (true) {
                delay(1_500)
                modelRepository.refreshAccount()
                val nextPhase = modelRepository.state.value.accountView?.optJSONObject("attempt")?.optString("phase")
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
                    title = { Text(stringResource(com.fioiu8.dshpocket.R.string.models_deepseek_api)) },
                    navigationIcon = {
                        IconButton(onClick = { officialApiPage = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.back))
                        }
                    },
                )
            } else if (editorPage) {
                TopAppBar(
                    title = { Text(when { headersPage -> stringResource(com.fioiu8.dshpocket.R.string.models_custom_headers); modelsPage -> stringResource(com.fioiu8.dshpocket.R.string.models_model_list); editingProvider == null -> stringResource(com.fioiu8.dshpocket.R.string.models_add_service); else -> stringResource(com.fioiu8.dshpocket.R.string.models_edit_service) }) },
                    navigationIcon = {
                        IconButton(onClick = { when { headersPage -> headersPage = false; modelsPage -> modelsPage = false; else -> editorPage = false } }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.back))
                        }
                    },
                )
            } else DshTopAppBar(title = stringResource(com.fioiu8.dshpocket.R.string.nav_models), onMenu = onMenu)
        },
    ) { padding ->
        if (officialApiPage) {
            DeepSeekApiKeyPage(
                apiKey = officialApiKey,
                onApiKeyChange = { officialApiKey = it },
                credentialRef = modelState.settings.officialApiKeyRef,
                configured = modelState.keyStates[modelState.settings.officialApiKeyRef] == true,
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
                            JSONObject().put("ref", modelState.settings.officialApiKeyRef).put("value", value),
                            ready.token,
                        )) {
                            is RpcResult.Err -> actionError = "${labelSaveApiKey}: ${result.message}"
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
                            JSONObject().put("ref", modelState.settings.officialApiKeyRef),
                            ready.token,
                        )) {
                            is RpcResult.Err -> actionError = "${labelClear}: ${result.message}"
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
                            is RpcResult.Err -> actionError = formatResource(com.fioiu8.dshpocket.R.string.models_discover_error, result.message)
                            is RpcResult.Ok -> {
                                val models = (result.value as? JSONArray)?.let { array ->
                                    (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let { model -> JSONObject(model.toString()) } }
                                }.orEmpty()
                                if (models.isEmpty()) actionError = labelNoModelsReturned
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
                takenIds = modelState.settings.providers.map { it.id },
                defaultProvider = modelState.settings.defaultProvider,
                defaultModel = modelState.settings.defaultModel,
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

            SectionHeader(stringResource(com.fioiu8.dshpocket.R.string.models_current))
            GroupedList(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DshSpacing.page),
                items = listOf {
                    ListItem(
                        headlineContent = {
                            Text(
                                "${modelState.settings.defaultProvider ?: stringResource(com.fioiu8.dshpocket.R.string.models_not_set)} / ${modelState.settings.defaultModel ?: stringResource(com.fioiu8.dshpocket.R.string.models_not_set)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        supportingContent = {
                            Text(stringResource(com.fioiu8.dshpocket.R.string.models_new_session_hint))
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

            SectionHeader(stringResource(com.fioiu8.dshpocket.R.string.models_services))
            OutlinedButton(
                onClick = { openEditor(null) },
                enabled = !busy,
                modifier = Modifier.padding(horizontal = DshSpacing.page),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(com.fioiu8.dshpocket.R.string.models_add_custom), modifier = Modifier.padding(start = 8.dp))
            }
            GroupedList(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DshSpacing.page),
                items = modelState.settings.providers.map { provider ->
                    {
                        Column(
                            Modifier.padding(DshSpacing.row),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(provider.displayName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = { openEditor(provider) }, enabled = !busy) {
                                    Icon(Icons.Filled.Edit, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.edit, provider.displayName))
                                }
                                IconButton(onClick = { deleteProvider = provider }, enabled = !busy) {
                                    Icon(Icons.Filled.DeleteOutline, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.delete) + " ${provider.displayName}")
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
                                    .ifBlank { stringResource(com.fioiu8.dshpocket.R.string.models_unconfigured) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            provider.apiKeyEnv?.let { env ->
                                val configured = modelState.keyStates[env] == true
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
                                        text = if (configured) stringResource(com.fioiu8.dshpocket.R.string.models_api_key_configured) else stringResource(com.fioiu8.dshpocket.R.string.models_api_key_missing),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    )
                                    TextButton(onClick = { keyDialogFor = provider }, modifier = Modifier.dshControl()) {
                                        Text(if (configured) stringResource(com.fioiu8.dshpocket.R.string.models_change_api_key) else stringResource(com.fioiu8.dshpocket.R.string.models_set_api_key))
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
                                                    is RpcResult.Err -> actionError = "${labelClear}: ${result.message}"
                                                    is RpcResult.Ok -> reload()
                                                }
                                                busy = false
                                            }
                                        }, modifier = Modifier.dshControl()) { Text(stringResource(com.fioiu8.dshpocket.R.string.models_clear)) }
                                    }
                                }
                            }
                        }
                    }
                },
            )

            if (modelState.builtinProviders.any { it.first == "deepseek-official" }) {
                val configured = modelState.keyStates[modelState.settings.officialApiKeyRef] == true
                GroupedList(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page),
                    items = listOf {
                        ListItem(
                            headlineContent = { Text(stringResource(com.fioiu8.dshpocket.R.string.models_deepseek_api)) },
                            supportingContent = {
                                Text(if (configured) stringResource(com.fioiu8.dshpocket.R.string.models_api_key_configured) else stringResource(com.fioiu8.dshpocket.R.string.models_api_key_missing))
                            },
                            trailingContent = {
                                TextButton(onClick = { openOfficialApiEditor() }, enabled = !busy) {
                                    Text(if (configured) stringResource(com.fioiu8.dshpocket.R.string.models_change_api_key) else stringResource(com.fioiu8.dshpocket.R.string.models_set_api_key))
                                }
                            },
                            modifier = Modifier.clickable(enabled = !busy) {
                                openOfficialApiEditor()
                            },
                        )
                    },
                )
            }

            val builtinOnly = modelState.builtinProviders.filter { (id, _) ->
                id != "deepseek-account" && id != "deepseek-official" && modelState.settings.providers.none { it.id == id }
            }
            if (builtinOnly.isNotEmpty()) {
                GroupedList(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DshSpacing.page),
                    items = builtinOnly.map { (id, name) ->
                        {
                            ListItem(
                                headlineContent = { Text(stringResource(com.fioiu8.dshpocket.R.string.models_builtin_prefix, name)) },
                                trailingContent = { Text(id) },
                            )
                        }
                    },
                )
            }

            if (modelState.builtinProviders.any { it.first == "deepseek-account" }) {
                val status = modelState.accountView?.optString("status") ?: "signed-out"
                val phase = modelState.accountView?.optJSONObject("attempt")?.optString("phase")
                val signInRunning = phase in setOf("initializing", "waiting-browser", "exchanging", "committing")
                GroupedList(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page),
                    items = listOf {
                        Column(
                            Modifier.padding(DshSpacing.row),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(com.fioiu8.dshpocket.R.string.models_deepseek_account), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (status == "credential-stored") stringResource(com.fioiu8.dshpocket.R.string.models_authorized) else if (signInRunning) stringResource(com.fioiu8.dshpocket.R.string.models_waiting_login) else stringResource(com.fioiu8.dshpocket.R.string.models_unauthorized),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (status == "credential-stored") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                when (phase) {
                                    "initializing" -> stringResource(com.fioiu8.dshpocket.R.string.models_login_preparing)
                                    "waiting-browser" -> stringResource(com.fioiu8.dshpocket.R.string.models_login_browser)
                                    "exchanging", "committing" -> stringResource(com.fioiu8.dshpocket.R.string.models_login_confirming)
                                    "failed" -> stringResource(com.fioiu8.dshpocket.R.string.models_login_failed, modelState.accountView?.optJSONObject("attempt")?.optString("errorCode") ?: stringResource(com.fioiu8.dshpocket.R.string.error_unknown))
                                    "expired" -> stringResource(com.fioiu8.dshpocket.R.string.models_login_expired)
                                    else -> stringResource(com.fioiu8.dshpocket.R.string.models_login_hint)
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
                                                is RpcResult.Err -> actionError = "${labelStartLoginFailed}: ${result.message}"
                                                is RpcResult.Ok -> {
                                                    val account = result.value as? JSONObject
                                                    modelRepository.publishAccount(account)
                                                    val authorizeUrl = account?.optJSONObject("attempt")?.optString("authorizeUrl")
                                                    if (!authorizeUrl.isNullOrBlank()) {
                                                        runCatching {
                                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorizeUrl)))
                                                        }.onFailure { actionError = "${labelBrowserFailed}: ${it.message}" }
                                                    }
                                                }
                                            }
                                            busy = false
                                        }
                                    },
                                ) { Text(if (signInRunning) stringResource(com.fioiu8.dshpocket.R.string.models_authorizing) else if (status == "credential-stored") stringResource(com.fioiu8.dshpocket.R.string.models_reauthorize) else stringResource(com.fioiu8.dshpocket.R.string.models_web_login)) }
                                if (signInRunning) {
                                    TextButton(
                                        enabled = !busy,
                                        onClick = {
                                            val attemptId = modelState.accountView?.optJSONObject("attempt")?.optString("id")
                                            if (!attemptId.isNullOrBlank()) scope.launch {
                                                busy = true
                                                when (val result = EngineApi.rpc(
                                                    ready.baseUrl,
                                                    "account/cancelSignIn",
                                                    JSONObject().put("attemptId", attemptId),
                                                    ready.token,
                                                )) {
                                                    is RpcResult.Err -> actionError = "${labelCancelLoginFailed}: ${result.message}"
                                                    is RpcResult.Ok -> modelRepository.publishAccount(result.value)
                                                }
                                                busy = false
                                            }
                                        },
                                    ) { Text(stringResource(com.fioiu8.dshpocket.R.string.models_auth_cancel)) }
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
                                                    is RpcResult.Err -> actionError = "${labelSignoutFailed}: ${result.message}"
                                                    is RpcResult.Ok -> modelRepository.publishAccount(result.value)
                                                }
                                                busy = false
                                            }
                                        },
                                    ) { Text(stringResource(com.fioiu8.dshpocket.R.string.models_sign_out)) }
                                }
                            }
                        }
                    },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(
                text = stringResource(com.fioiu8.dshpocket.R.string.models_api_key_storage_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = DshSpacing.page),
            )
        }
    }

    if (pickingProvider) {
        RadioDialog(
            title = stringResource(com.fioiu8.dshpocket.R.string.models_select_service),
            options = modelState.settings.providers.map { it.id to it.displayName },
            selected = modelState.settings.defaultProvider,
            onDismiss = { pickingProvider = false },
            onPick = { picked ->
                pickingProvider = false
                val target = modelState.settings.providers.firstOrNull { it.id == picked }
                // 换 provider 时若原默认模型不在新清单里，回落到该清单第一项。
                val model = target?.models?.firstOrNull { it.first == modelState.settings.defaultModel }?.first
                    ?: target?.models?.firstOrNull()?.first
                if (target != null && model != null) persistDefault(target.id, model)
            },
        )
    }
    if (pickingModel) {
        val current = modelState.settings.providers.firstOrNull { it.id == modelState.settings.defaultProvider }
        if (current != null && current.models.isNotEmpty()) {
            RadioDialog(
                title = stringResource(com.fioiu8.dshpocket.R.string.models_select_model),
                options = current.models.map { it.first to it.second.ifBlank { it.first } },
                selected = modelState.settings.defaultModel,
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
            title = { Text(stringResource(com.fioiu8.dshpocket.R.string.models_set_provider_key, provider.displayName)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(com.fioiu8.dshpocket.R.string.models_provider_key_hint, provider.apiKeyEnv.orEmpty()),
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
                            is RpcResult.Err -> actionError = "${labelSaveApiKey}: ${result.message}"
                            is RpcResult.Ok -> reload()
                        }
                        busy = false
                    }
                }, modifier = Modifier.dshControl()) { Text(stringResource(com.fioiu8.dshpocket.R.string.save)) }
            },
        dismissButton = { TextButton(onClick = { keyDialogFor = null }, modifier = Modifier.dshControl()) { Text(stringResource(com.fioiu8.dshpocket.R.string.cancel)) } },
        )
    }

    deleteProvider?.let { provider ->
        val isDefault = modelState.settings.defaultProvider == provider.id
        AlertDialog(
            onDismissRequest = { if (!busy) deleteProvider = null },
            title = { Text(stringResource(com.fioiu8.dshpocket.R.string.models_delete_service_title)) },
            text = {
                Text(
                    if (isDefault) stringResource(com.fioiu8.dshpocket.R.string.models_delete_default_hint, provider.displayName)
                    else stringResource(com.fioiu8.dshpocket.R.string.models_delete_hint, provider.displayName),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { removeProvider(provider) },
                    enabled = !busy && !isDefault,
                    modifier = Modifier.dshControl(),
                ) { Text(stringResource(com.fioiu8.dshpocket.R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteProvider = null }, modifier = Modifier.dshControl()) { Text(stringResource(com.fioiu8.dshpocket.R.string.cancel)) }
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
        Text(stringResource(com.fioiu8.dshpocket.R.string.models_deepseek_api), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            if (configured) stringResource(com.fioiu8.dshpocket.R.string.models_key_configured_hint) else stringResource(com.fioiu8.dshpocket.R.string.models_key_enter_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            label = { Text(stringResource(com.fioiu8.dshpocket.R.string.models_api_key)) },
            placeholder = { Text("sk-…") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(com.fioiu8.dshpocket.R.string.models_credential_ref, credentialRef), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSave, enabled = !busy && apiKey.isNotBlank()) {
                Text(if (busy) stringResource(com.fioiu8.dshpocket.R.string.models_save_api_key_busy) else if (configured) stringResource(com.fioiu8.dshpocket.R.string.models_change_api_key) else stringResource(com.fioiu8.dshpocket.R.string.models_save_api_key))
            }
            if (configured) {
                TextButton(onClick = onClear, enabled = !busy, modifier = Modifier.dshControl()) { Text(stringResource(com.fioiu8.dshpocket.R.string.models_clear)) }
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
            Text(stringResource(com.fioiu8.dshpocket.R.string.models_headers_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = headers,
                onValueChange = onHeadersChange,
                label = { Text(stringResource(com.fioiu8.dshpocket.R.string.models_headers)) },
                placeholder = { Text("X-Organization: team-a") },
                supportingText = { Text(stringResource(com.fioiu8.dshpocket.R.string.models_headers_not_set)) },
                minLines = 5,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (modelsPage) {
            Text(stringResource(com.fioiu8.dshpocket.R.string.models_add_models_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = onDiscoverModels,
                enabled = !busy && isHttpEndpoint(baseUrl),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) stringResource(com.fioiu8.dshpocket.R.string.models_getting_models) else stringResource(com.fioiu8.dshpocket.R.string.models_get_models))
            }
            if (!isHttpEndpoint(baseUrl)) {
                Text(stringResource(com.fioiu8.dshpocket.R.string.models_fill_address_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            label = { Text(stringResource(com.fioiu8.dshpocket.R.string.models_model_id)) },
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
                            label = { Text(stringResource(com.fioiu8.dshpocket.R.string.models_display_name_optional)) },
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
                    ) { Icon(Icons.Filled.DeleteOutline, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.delete)) }
                }
            }
            OutlinedButton(
                onClick = { modelRows = modelRows + ("" to "") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(com.fioiu8.dshpocket.R.string.models_add_model), modifier = Modifier.padding(start = 8.dp))
            }
            when {
                parsedModels == null -> Text(stringResource(com.fioiu8.dshpocket.R.string.models_model_id_required), color = MaterialTheme.colorScheme.error)
                else -> Text(stringResource(com.fioiu8.dshpocket.R.string.models_model_count, parsedModels.size), style = MaterialTheme.typography.labelMedium)
            }
        } else {
            OutlinedTextField(
                value = id,
                onValueChange = onIdChange,
                label = { Text(stringResource(com.fioiu8.dshpocket.R.string.models_service_id)) },
                placeholder = { Text("my-gateway") },
                supportingText = { Text(if (provider == null) stringResource(com.fioiu8.dshpocket.R.string.models_service_id_hint) else stringResource(com.fioiu8.dshpocket.R.string.models_service_id_fixed)) },
                singleLine = true,
                enabled = !busy && provider == null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = displayName,
                onValueChange = onDisplayNameChange,
                label = { Text(stringResource(com.fioiu8.dshpocket.R.string.models_display_name)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                label = { Text(stringResource(com.fioiu8.dshpocket.R.string.models_service_address)) },
                placeholder = { Text("https://api.example.com/v1") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Column {
                Text(stringResource(com.fioiu8.dshpocket.R.string.models_api_protocol), style = MaterialTheme.typography.labelMedium)
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
                label = { Text(stringResource(com.fioiu8.dshpocket.R.string.models_api_key) + if (provider == null) " (${stringResource(com.fioiu8.dshpocket.R.string.models_optional)})" else " (${stringResource(com.fioiu8.dshpocket.R.string.models_leave_blank_unchanged)})") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            ListItem(
                headlineContent = { Text(stringResource(com.fioiu8.dshpocket.R.string.models_headers)) },
                supportingContent = { Text(headers.lineSequence().count { it.isNotBlank() }.let { if (it == 0) stringResource(com.fioiu8.dshpocket.R.string.models_headers_not_set) else stringResource(com.fioiu8.dshpocket.R.string.models_header_count, it) }) },
                trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable(enabled = !busy) { onHeadersPage(true) },
            )
            ListItem(
                headlineContent = { Text(stringResource(com.fioiu8.dshpocket.R.string.models_models)) },
                supportingContent = { Text(parsedModels?.size?.let { stringResource(com.fioiu8.dshpocket.R.string.models_model_count, it) } ?: stringResource(com.fioiu8.dshpocket.R.string.models_fill_valid_models)) },
                trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable(enabled = !busy) { onModelsPage(true) },
            )
            when {
                id.isNotBlank() && !idValid -> Text(stringResource(com.fioiu8.dshpocket.R.string.models_service_id_error), color = MaterialTheme.colorScheme.error)
                baseUrl.isNotBlank() && !isHttpEndpoint(baseUrl) -> Text(stringResource(com.fioiu8.dshpocket.R.string.models_url_error), color = MaterialTheme.colorScheme.error)
                models.isNotBlank() && parsedModels == null -> Text(stringResource(com.fioiu8.dshpocket.R.string.models_model_id_required), color = MaterialTheme.colorScheme.error)
                parsedModels.isNullOrEmpty() -> Text(stringResource(com.fioiu8.dshpocket.R.string.models_no_valid_models), color = MaterialTheme.colorScheme.error)
                parsedHeaders == null -> Text(stringResource(com.fioiu8.dshpocket.R.string.models_headers_error), color = MaterialTheme.colorScheme.error)
                defaultModelRemoved -> Text(stringResource(com.fioiu8.dshpocket.R.string.models_remove_default_error), color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    onSave(ProviderDraft(id, displayName, baseUrl, api, apiKey, models, headers, modelDetails))
                }, enabled = canSave) {
                    Text(if (busy) stringResource(com.fioiu8.dshpocket.R.string.saving) else stringResource(com.fioiu8.dshpocket.R.string.save))
                }
            }
        }
    }
    discoveredModels?.let { candidates ->
        AlertDialog(
            onDismissRequest = onDismissDiscoveredModels,
            title = { Text(stringResource(com.fioiu8.dshpocket.R.string.models_select_models_to_add)) },
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
                                        "Context ${contextWindow}",
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
                ) { Text(stringResource(com.fioiu8.dshpocket.R.string.models_add_count, selectedDiscoveredModels.size)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissDiscoveredModels, modifier = Modifier.dshControl()) { Text(stringResource(com.fioiu8.dshpocket.R.string.cancel)) }
            },
        )
    }
}

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
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.dshControl()) { Text(stringResource(com.fioiu8.dshpocket.R.string.cancel)) } },
    )
}