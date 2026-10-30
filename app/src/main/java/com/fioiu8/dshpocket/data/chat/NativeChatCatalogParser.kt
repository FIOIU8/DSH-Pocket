package com.fioiu8.dshpocket.data.chat

import org.json.JSONArray
import org.json.JSONObject

internal fun parseNativeChatSessions(items: JSONArray, untitledTitle: String): List<NativeChatSession> = buildList {
    for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        if (item.optBoolean("blank")) continue
        val values = item.optJSONObject("projections")?.optJSONObject("values")
        val title = values?.optString("title")?.takeIf { it.isNotBlank() && it != "null" } ?: untitledTitle
        add(NativeChatSession(item.optString("sessionId"), title, item.optBoolean("running")))
    }
}

internal fun parseNativeModelCatalog(catalog: JSONObject?, defaultEffortLabel: String): List<NativeModelOption> = buildList {
    val groups = catalog?.optJSONArray("groups") ?: return@buildList
    for (groupIndex in 0 until groups.length()) {
        val group = groups.optJSONObject(groupIndex) ?: continue
        val models = group.optJSONArray("models") ?: continue
        for (modelIndex in 0 until models.length()) {
            val model = models.optJSONObject(modelIndex) ?: continue
            val modelId = model.optString("id").takeIf(String::isNotBlank) ?: continue
            val effortValues = model.optJSONObject("reasoning")?.optJSONArray("efforts")
            val efforts = buildList {
                add("default" to defaultEffortLabel)
                if (effortValues != null) for (effortIndex in 0 until effortValues.length()) {
                    val effort = effortValues.optJSONObject(effortIndex) ?: continue
                    val id = effort.optString("id").takeIf(String::isNotBlank) ?: continue
                    add(id to effort.optString("name").ifBlank { id })
                }
            }
            add(
                NativeModelOption(
                    group.optString("id"),
                    group.optString("name").ifBlank { group.optString("id") },
                    modelId,
                    model.optString("name").ifBlank { modelId },
                    efforts,
                ),
            )
        }
    }
}
