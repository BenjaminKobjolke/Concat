// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.concat.editor.ui.theme.ConcatColors
import app.concat.editor.ui.theme.Spacing

data class IconAppearance(
    val tint: Color = ConcatColors.fg,
    val size: Dp = 22.dp,
    val label: String? = null,
)

data class IconActionOptions(
    val modifier: Modifier = Modifier,
    val enabled: Boolean = true,
    val tint: Color = ConcatColors.fg,
    val size: Dp = 22.dp,
    val box: Dp = 40.dp,
)

data class AccentButtonOptions(
    val modifier: Modifier = Modifier,
    val enabled: Boolean = true,
    val icon: ImageVector? = null,
    val height: Dp = 40.dp,
)

data class GhostButtonOptions(
    val modifier: Modifier = Modifier,
    val enabled: Boolean = true,
    val icon: ImageVector? = null,
    val height: Dp = 40.dp,
)

data class ChoiceChipOptions(val modifier: Modifier = Modifier, val enabled: Boolean = true)

data class ValueSliderOptions(
    val describe: (Float) -> String,
    val onChange: (Float) -> Unit,
    val modifier: Modifier = Modifier,
    val steps: Int = 0,
    val onDone: () -> Unit = {},
)

data class ToolPanelOptions(
    val modifier: Modifier = Modifier,
    val onConfirm: (() -> Unit)? = null,
    val actions: @Composable () -> Unit = {},
    val scroll: Boolean = true,
    val padding: PaddingValues = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.xs),
)

data class ToolButtonOptions(
    val modifier: Modifier = Modifier,
    val active: Boolean = false,
    val enabled: Boolean = true,
    val width: Dp = 64.dp,
)
