package com.fioiu8.dshpocket.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fioiu8.dshpocket.engine.EngineApi
import com.fioiu8.dshpocket.engine.EngineApi.RpcResult
import com.fioiu8.dshpocket.engine.EngineState
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模型配置（m3e-canvas.json 的 models 帧）。
 *
 * 数据全部来自引擎 RPC：`settings/describe` 读 `agent-default-model`（当前默认模型，
 * 带 revision 做乐观锁）与 `llm-pi-ai`（自定义 OpenAI 兼容 provider 清单及其模型），
 * `credentials/describe|set|unset` 管理 API key（值只写不读）。
 * 设计稿里的思考强度滑条与流式开关暂缺对应的已验证 RPC，留待后续。
 * 内置 DeepSeek 官方/账号 provider 没有本地模型清单，仅展示。
 */
private data class ProviderInfo(
    val id: String,
    val displayName: String,
    val baseURL: String?,
    val apiKeyEnv: String?,
    val models: List<Pair<String, String>>,
)

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
    var busy by remember { mutableStateOf(false) }

    var defaultProvider by remember { mutableStateOf<String?>(null) }
    var defaultModel by remember { mutableStateOf<String?>(null) }
    var modelRevision by remember { mutableStateOf<Long?>(null) }
    var providers by remember { mutableStateOf<List<ProviderInfo>>(emptyList()) }
    var builtinProviders by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var keyStates by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    var pickingProvider by remember { mutableStateOf(false) }
    var pickingModel by remember { mutableStateOf(false) }
    var keyDialogFor by remember { mutableStateOf<ProviderInfo?>(null) }

    fun reload() {
        error = null
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

    LaunchedEffect(tick) {
        busy = true
        error = null
        val describe = EngineApi.rpc(ready.baseUrl, "settings/describe", EngineApi.emptyArgs(), ready.token)
        if (describe !is RpcResult.Ok) {
            busy = false
            error = (describe as? RpcResult.Err)?.let { "${it.code}: ${it.message}" } ?: "未知错误"
            return@LaunchedEffect
        }
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
                    val configs = ns.optJSONObject("value")?.optJSONObject("providers")
                    providers = buildList {
                        configs?.keys()?.forEach { id ->
                            val p = configs.optJSONObject(id) ?: return@forEach
                            add(
                                ProviderInfo(
                                    id = id,
                                    displayName = p.optString("displayName").ifBlank { id },
                                    baseURL = p.optString("baseURL").takeIf { it.isNotBlank() },
                                    apiKeyEnv = p.optString("apiKeyEnv").takeIf { it.isNotBlank() },
                                    models = p.optJSONArray("models")?.let { models ->
                                        (0 until models.length()).mapNotNull { k ->
                                            val m = models.optJSONObject(k) ?: return@mapNotNull null
                                            m.optString("id").takeIf { it.isNotBlank() }
                                                ?.let { it to m.optString("name") }
                                        }
                                    }.orEmpty(),
                                ),
                            )
                        }
                    }
                }
            }
        }
        val listProviders = EngineApi.rpc(ready.baseUrl, "llm/listProviders", EngineApi.emptyArgs(), ready.token)
        if (listProviders is RpcResult.Ok) {
            builtinProviders = EngineApi.itemsOf(listProviders.value).let { items ->
                (0 until items.length()).mapNotNull { i ->
                    val p = items.optJSONObject(i) ?: return@mapNotNull null
                    p.optString("id").takeIf { it.isNotBlank() }?.let { it to p.optString("name") }
                }
            }
        }
        val keyRefs = providers.mapNotNull { it.apiKeyEnv }.distinct()
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

    Scaffold(
        topBar = { DshTopAppBar(title = "模型配置", onMenu = onMenu) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let {
                CenteredMessage("加载失败", it, busy = false)
                return@Column
            }

            SectionHeader("当前模型")
            Card(
                onClick = { pickingProvider = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
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
                )
            }

            SectionHeader("模型服务")
            providers.forEach { provider ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(provider.displayName, style = MaterialTheme.typography.titleSmall)
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
                                TextButton(onClick = { keyDialogFor = provider }) {
                                    Text(if (configured) "更换" else "设置")
                                }
                                if (configured) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            EngineApi.rpc(
                                                ready.baseUrl,
                                                "credentials/unset",
                                                JSONObject().put("ref", env),
                                                ready.token,
                                            )
                                            reload()
                                        }
                                    }) { Text("清除") }
                                }
                            }
                        }
                    }
                }
            }

            builtinProviders.forEach { (id, name) ->
                if (providers.none { it.id == id }) {
                    ListItem(headlineContent = { Text("内置：$name") }, trailingContent = { Text(id) })
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(
                text = "API Key 只写入本机引擎的凭据存储，读取接口不会返回明文。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
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
                        EngineApi.rpc(
                            ready.baseUrl,
                            "credentials/set",
                            JSONObject().put("ref", provider.apiKeyEnv).put("value", value),
                            ready.token,
                        )
                        reload()
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { keyDialogFor = null }) { Text("取消") } },
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
