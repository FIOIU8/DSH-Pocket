package com.fioiu8.dshpocket.data.session

/**
 * A session as the engine reports it.
 *
 * [title] is null when the engine has no title yet. It is deliberately *not* filled in
 * with a rendered placeholder: a model that carries a finished `String` freezes the
 * language at parse time, which is the defect both `LocalContextConfigurationRead` and
 * `LocalContextGetResourceValueCall` report. The UI substitutes a `@StringRes` fallback
 * at draw time instead.
 */
data class SessionRow(
    val id: String,
    val title: String?,
    val updatedAt: Long,
    val running: Boolean,
    val provider: String?,
    val model: String?,
    val steps: Int,
    val cwd: String?,
)

/** Whether the engine has answered yet. Mirrors the three states the screen has always had. */
sealed interface SessionLoadState {
    data object Loading : SessionLoadState
    data class Ready(val rows: List<SessionRow>) : SessionLoadState
    data class Failed(val message: String) : SessionLoadState
}

/** Workspace key used for grouping; null means the row has no `cwd` and the UI labels it. */
fun SessionRow.workspaceKey(fallback: String): String = cwd?.substringAfterLast('/') ?: fallback
