// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.concat.editor.spike.ui.components.AccentButton
import app.concat.editor.spike.ui.components.ConcatIcon
import app.concat.editor.spike.ui.components.IconAction
import app.concat.editor.spike.ui.components.ToolButton
import app.concat.editor.spike.ui.icons.ConcatIcons
import app.concat.editor.spike.ui.theme.ConcatColors
import app.concat.editor.spike.ui.theme.ConcatType
import app.concat.editor.spike.ui.theme.Dimens
import app.concat.editor.spike.ui.theme.Motion
import app.concat.editor.spike.ui.theme.Radii
import app.concat.editor.spike.ui.theme.Spacing

@Composable
internal fun TopBar(
    name: String,
    resolution: String,
    canExport: Boolean,
    onClose: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(Dimens.topBar).padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(ConcatIcons.Close, "Save and close", onClose)
        Box(Modifier.weight(1f).fillMaxHeight().clickable(onClickLabel = "Rename", onClick = onRename), contentAlignment = Alignment.Center) {
            Text(name, style = ConcatType.heading, color = ConcatColors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = Spacing.sm))
        }
        if (resolution.isNotEmpty()) {
            Box(
                Modifier.height(28.dp).clip(RoundedCornerShape(Radii.sm)).border(1.dp, ConcatColors.lineStrong, RoundedCornerShape(Radii.sm))
                    .clickable(onClickLabel = "Export settings", onClick = onExport).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(resolution, style = ConcatType.badge.copy(fontSize = 11.sp), color = ConcatColors.fg)
                    ConcatIcon(ConcatIcons.ChevronDown, tint = ConcatColors.fgMuted, size = 14.dp)
                }
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        AccentButton("Export", onExport, enabled = canExport, height = 32.dp, modifier = Modifier.padding(end = Spacing.xs))
    }
}

@Composable
internal fun Toolbar(tools: List<Tool>, key: String) {
    Box(Modifier.fillMaxWidth().height(Dimens.toolbar).background(ConcatColors.launchPanel)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(ConcatColors.line))
        // Keyed by what is selected, not by the tools' states, so a tool
        // lighting up does not swap the row (and lose its scroll).
        Crossfade(targetState = key, animationSpec = Motion.tweenShort(), label = "toolbar") { _ ->
            Row(
                Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.xs, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for ((i, tool) in tools.withIndex()) {
                    if (i == 0 && tool.label.isEmpty()) {
                        Box(
                            Modifier.width(44.dp).fillMaxHeight().clip(RoundedCornerShape(Radii.md)).clickable(onClickLabel = "Back", onClick = tool.onClick),
                            contentAlignment = Alignment.Center,
                        ) { ConcatIcon(tool.icon, size = 22.dp, label = "Back") }
                        Box(Modifier.width(1.dp).height(32.dp).background(ConcatColors.lineStrong))
                        Spacer(Modifier.width(Spacing.xs))
                    } else {
                        ToolButton(tool.icon, tool.label, tool.onClick, active = tool.active, enabled = tool.enabled)
                    }
                }
            }
        }
    }
}
