package com.fioiu8.dshpocket.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.fioiu8.dshpocket.core.UiText

@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Resource -> stringResource(id, *args.toTypedArray())
    is UiText.Raw -> value
}
