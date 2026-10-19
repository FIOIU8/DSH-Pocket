package com.fioiu8.dshpocket.ui.state

import org.json.JSONArray
import org.json.JSONObject

/**
 * How a plugin list is turned into rows.
 *
 * Localization deliberately does NOT happen here: the parser keeps the raw
 * [LocalizedText], and the title the user sees (including the module-name fallback and
 * the sort order, both of which depend on the current locale) is resolved by
 * [titleFor] / [descriptionFor] / [sortedByTitle] at render time.
 */
object PluginParser {

    fun rows(items: JSONArray): List<PluginRow> = buildList {
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val meta = item.optJSONObject("meta")
            add(
                PluginRow(
                    id = item.optString("entryId"),
                    moduleName = item.optString("moduleName"),
                    // Kept raw; an unresolvable value falls back to the module-name tail
                    // in titleFor, so no parse-time decision is needed here.
                    title = meta?.opt("title")?.let { LocalizedText(it) },
                    description = meta?.opt("description")?.let { LocalizedText(it) },
                    enabled = item.optBoolean("enabled"),
                    readOnlyReason = item.optString("readOnlyReason").takeIf { it.isNotBlank() },
                ),
            )
        }
    }

    /** The localized title if there is one, otherwise the module name's last segment. */
    fun titleFor(row: PluginRow, localeTag: String): String =
        localized(row.title?.value, localeTag) ?: row.moduleName.substringAfterLast('/')

    fun descriptionFor(row: PluginRow, localeTag: String): String =
        localized(row.description?.value, localeTag).orEmpty()

    /** The shipped sort key: the localized title, case-insensitively. */
    fun sortedByTitle(rows: List<PluginRow>, localeTag: String): List<PluginRow> =
        rows.sortedBy { titleFor(it, localeTag).lowercase() }
}

/**
 * `LocalizedText` may be a plain string or a language-keyed object; lenient parse.
 *
 * Moved verbatim from the screen. The default `zh-CN` tag matches the shipped
 * signature; callers that know the locale pass it explicitly.
 */
fun localized(value: Any?, localeTag: String = "zh-CN"): String? = when (value) {
    null -> null
    is String -> value.takeIf { it.isNotBlank() }
    is JSONObject -> {
        val zh = value.optString("zh-CN").ifBlank { value.optString("zh") }
        val en = value.optString("en")
        val preferred = when {
            localeTag.lowercase().startsWith("zh") -> zh
            localeTag.lowercase().startsWith("en") -> en
            else -> ""
        }
        (preferred.ifBlank { zh }.ifBlank { en }).takeIf { it.isNotBlank() }
    }

    else -> value.toString().takeIf { it.isNotBlank() && it != "null" }
}
