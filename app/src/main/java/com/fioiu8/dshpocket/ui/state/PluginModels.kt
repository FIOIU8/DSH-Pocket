package com.fioiu8.dshpocket.ui.state

import org.json.JSONObject

/**
 * A localized payload straight from the engine: either a plain string or a
 * language-keyed object. The model keeps it raw — resolving it to display text needs
 * a locale, and only the UI knows the current one.
 *
 * Same principle line as `SessionRow.title`: models hold raw values, never rendered
 * strings.
 */
@JvmInline
value class LocalizedText(val value: Any?)

/**
 * One plugin as `pluginManager/listPlugins` reports it.
 *
 * `title` null means the engine gave no localized title at all; the screen renders
 * the module name's tail in that case, as the shipped parse did.
 */
data class PluginRow(
    val id: String,
    val moduleName: String,
    val title: LocalizedText?,
    val description: LocalizedText?,
    val enabled: Boolean,
    val readOnlyReason: String?,
)

/** What the plugin screen renders from. */
data class PluginUiState(
    /** Null until the first successful load; retains the last list on a failed refresh. */
    val rows: List<PluginRow>? = null,
    val error: String? = null,
)
