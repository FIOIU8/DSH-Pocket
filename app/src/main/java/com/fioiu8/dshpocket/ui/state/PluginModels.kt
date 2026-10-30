package com.fioiu8.dshpocket.ui.state

import com.fioiu8.dshpocket.data.session.SessionRow

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

/**
 * One installable package as `pluginManager/listBundles` reports it.
 *
 * A bundle is the npm package the install/remove RPCs operate on; one bundle can
 * carry several plugin entries. `removable` is the engine's own verdict — the app
 * does not second-guess it.
 */
data class PluginBundle(
    val name: String,
    val version: String?,
    val title: LocalizedText?,
    val description: String?,
    val enabled: Boolean,
    val removable: Boolean,
    val readOnlyReason: String?,
)

/** Outcome of one install/remove operation, in the terms the user sees. */
sealed interface PluginChange {
    /** Files applied and live (or enabled); the engine hot-reloaded the bundle. */
    data object Applied : PluginChange

    /** Applied, but the engine must restart before the change is visible. */
    data object RestartRequired : PluginChange

    /** The caller (or a competing caller) cancelled it; files were restored. */
    data object Cancelled : PluginChange

    /** Refused; [code] is the engine's error code and [detail] its diagnostic. */
    data class Failed(val code: String, val detail: String?) : PluginChange
}

/** Progress of an in-flight install or remove, so the UI can show and cancel it. */
data class PluginAction(
    /** The spec being installed, or the package name being removed. */
    val target: String,
    /** The id the engine's cancel RPC addresses; blank when the action cannot be cancelled. */
    val requestId: String,
)

/** What the plugin screen renders from. */
data class PluginUiState(
    /** Null until the first successful load; retains the last list on a failed refresh. */
    val rows: List<PluginRow>? = null,
    /** Null until the first successful load; retains the last list on a failed refresh. */
    val bundles: List<PluginBundle>? = null,
    val error: String? = null,
    /** Set while an install/remove round-trip is in flight; one at a time. */
    val action: PluginAction? = null,
    /** Outcome of the last install/remove, for the screen to surface once. */
    val lastChange: PluginChange? = null,
)
