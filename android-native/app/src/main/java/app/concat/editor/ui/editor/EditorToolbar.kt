// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.ui.editor

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.concat.editor.api.Clip
import app.concat.editor.api.ClipKind
import app.concat.editor.api.ProjectView
import app.concat.editor.ui.components.AccentButton
import app.concat.editor.ui.components.ConcatIcon
import app.concat.editor.ui.components.IconAction
import app.concat.editor.ui.components.ToolButton
import app.concat.editor.ui.components.ToolButtonOptions
import app.concat.editor.ui.icons.ConcatIcons
import app.concat.editor.ui.shortTimecode
import app.concat.editor.ui.theme.ConcatColors
import app.concat.editor.ui.theme.ConcatType
import app.concat.editor.ui.theme.Dimens
import app.concat.editor.ui.theme.Motion
import app.concat.editor.ui.theme.Radii
import app.concat.editor.ui.theme.Spacing
import app.concat.editor.ui.components.IconAppearance
import app.concat.editor.ui.components.IconActionOptions
import app.concat.editor.ui.components.AccentButtonOptions
internal enum class Menu { Root, Audio, Text }

/** A tool's options, open above the toolbar. */
internal enum class Panel { Speed, Volume, Opacity, Fade, Crop, Mask, Animation, TextNew, TextEdit, Effects, Filters, Transitions, Aspect, Background, Captions, Speech, Voiceover, Stickers, Cover }

/** The panels that belong to the selected clip, closed when it is let go. */
internal val CLIP_PANELS = setOf(Panel.Speed, Panel.Volume, Panel.Opacity, Panel.Fade, Panel.Crop, Panel.Mask, Panel.Animation, Panel.TextEdit, Panel.Effects, Panel.Filters)

/** One tool of the bottom toolbar. */
internal class Tool(val icon: ImageVector, val label: String, val enabled: Boolean = true, val active: Boolean = false, val onClick: () -> Unit)

/**
 * The editor: a top bar, the preview (at most about 42% of
 * the height: the timeline is the product), the transport, the timeline,
 * and a scrollable toolbar whose tools open their options in a panel
 * above it. [startTool] opens a panel at once ("captions", "speech").
 */
internal fun resolutionLabel(view: ProjectView): String = when (minOf(view.width, view.height)) {
    in 2000..Int.MAX_VALUE -> "4K"
    in 1300..1999 -> "2K"
    in 1000..1299 -> "1080P"
    in 700..999 -> "720P"
    else -> "${minOf(view.width, view.height)}P"
}

@Composable
internal fun TopBar(context: TopBarContext) {
    Row(
        Modifier.fillMaxWidth().height(Dimens.topBar).padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(ConcatIcons.Close, "Save and close", context.actions.onClose)
        Box(Modifier.weight(1f).fillMaxHeight().clickable(onClickLabel = "Rename", onClick = context.actions.onRename), contentAlignment = Alignment.Center) {
            Text(context.content.name, style = ConcatType.heading, color = ConcatColors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = Spacing.sm))
        }
        if (context.content.resolution.isNotEmpty()) {
            Box(
                Modifier.height(28.dp).clip(RoundedCornerShape(Radii.sm)).border(1.dp, ConcatColors.lineStrong, RoundedCornerShape(Radii.sm))
                    .clickable(onClickLabel = "Export settings", onClick = context.actions.onExport).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(context.content.resolution, style = ConcatType.badge.copy(fontSize = 11.sp), color = ConcatColors.fg)
                    ConcatIcon(ConcatIcons.ChevronDown, appearance = IconAppearance(tint = ConcatColors.fgMuted, size = 14.dp))
                }
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        AccentButton("Export", context.actions.onExport, options = AccentButtonOptions(enabled = context.content.canExport, height = 32.dp, modifier = Modifier.padding(end = Spacing.xs)))
    }
}

internal data class TopBarContent(val name: String, val resolution: String, val canExport: Boolean)
internal data class TopBarActions(val onClose: () -> Unit, val onRename: () -> Unit, val onExport: () -> Unit)
internal data class TopBarContext(val content: TopBarContent, val actions: TopBarActions)

@Composable
internal fun Transport(context: EditorTransportContext, keyframe: Boolean?) {
    val ui = context.ui
    val duration = context.view?.duration ?: 0.0
    val canUndo = context.view?.canUndo == true
    val canRedo = context.view?.canRedo == true
    val fullscreen = context.fullscreen
    val model = context.model
    Row(
        Modifier.fillMaxWidth().height(Dimens.transport).padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = ConcatColors.fg)) { append(shortTimecode(ui.position)) }
                withStyle(SpanStyle(color = ConcatColors.fgDim)) { append(" / " + shortTimecode(duration)) }
            },
            style = ConcatType.timecode,
            modifier = Modifier.weight(1f),
        )
        IconAction(if (ui.playing) ConcatIcons.Pause else ConcatIcons.Play, if (ui.playing) "Pause" else "Play", model::togglePlay, options = IconActionOptions(enabled = duration > 0, size = 24.dp, box = 44.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (keyframe != null) {
                IconAction(if (keyframe) ConcatIcons.KeyframeFilled else ConcatIcons.Keyframe, "Keyframe", model::toggleKeyframe, options = IconActionOptions(tint = if (keyframe) ConcatColors.accent else ConcatColors.fg, size = 18.dp, box = 36.dp))
            }
            IconAction(ConcatIcons.Undo, "Undo", model::undo, options = IconActionOptions(enabled = canUndo, size = 20.dp, box = 36.dp))
            IconAction(ConcatIcons.Redo, "Redo", model::redo, options = IconActionOptions(enabled = canRedo, size = 20.dp, box = 36.dp))
            IconAction(ConcatIcons.Maximize, if (fullscreen) "Leave full screen" else "Full screen", context.onFullscreen, options = IconActionOptions(size = 18.dp, box = 36.dp, tint = if (fullscreen) ConcatColors.accent else ConcatColors.fg))
        }
    }
}

@Composable
internal fun Toolbar(tools: List<Tool>, key: String) {
    Box(Modifier.fillMaxWidth().height(Dimens.toolbar).background(ConcatColors.launchPanel)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(ConcatColors.line))
        // Keyed by what is selected, not by the tools' states, so a tool
        // lighting up does not swap the row (and lose its scroll).
        Crossfade(targetState = key, animationSpec = Motion.tweenShort(), label = "toolbar") { _ ->
            val shown = tools
            Row(
                Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.xs, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for ((i, tool) in shown.withIndex()) {
                    if (i == 0 && tool.label.isEmpty()) {
                        // The way back: a narrow chevron and a rule after it.
                        Box(
                            Modifier.width(44.dp).fillMaxHeight().clip(RoundedCornerShape(Radii.md)).clickable(onClickLabel = "Back", onClick = tool.onClick),
                            contentAlignment = Alignment.Center,
                        ) { ConcatIcon(tool.icon, appearance = IconAppearance(size = 22.dp, label = "Back")) }
                        Box(Modifier.width(1.dp).height(32.dp).background(ConcatColors.lineStrong))
                        Spacer(Modifier.width(Spacing.xs))
                    } else {
                        ToolButton(tool.icon, tool.label, tool.onClick, options = ToolButtonOptions(active = tool.active, enabled = tool.enabled))
                    }
                }
            }
        }
    }
}

internal class ToolActions(
    val openPanel: (Panel) -> Unit,
    val setMenu: (Menu) -> Unit,
    val deselect: () -> Unit,
    val addMedia: () -> Unit,
    val addOverlay: () -> Unit,
    val addAudio: () -> Unit,
    /** A file picked to stand in for the selected clip's: a sound file when true. */
    val replace: (Boolean) -> Unit,
    /** The clip a reverse is being written for, and how far it is. */
    val reversing: Pair<String, Float>?,
    val selectMain: () -> Unit,
    val split: () -> Unit,
    val catalogue: (Shelf, Boolean) -> Unit,
    val model: EditorViewModel,
)

/** A title's words edited where they sit: the keyboard up, the words over a dimmed frame. */
