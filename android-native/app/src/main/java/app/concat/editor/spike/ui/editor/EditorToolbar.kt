// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.concat.editor.spike.api.Clip
import app.concat.editor.spike.api.ClipKind
import app.concat.editor.spike.api.ProjectView
import app.concat.editor.spike.concat
import app.concat.editor.spike.ui.components.AccentButton
import app.concat.editor.spike.ui.components.ConcatIcon
import app.concat.editor.spike.ui.components.IconAction
import app.concat.editor.spike.ui.components.IconActionDimensions
import app.concat.editor.spike.ui.components.ToolButton
import app.concat.editor.spike.ui.icons.ConcatIcons
import app.concat.editor.spike.ui.shortTimecode
import app.concat.editor.spike.ui.theme.ConcatColors
import app.concat.editor.spike.ui.theme.ConcatType
import app.concat.editor.spike.ui.theme.Dimens
import app.concat.editor.spike.ui.theme.Motion
import app.concat.editor.spike.ui.theme.Radii
import app.concat.editor.spike.ui.theme.Spacing
import kotlinx.coroutines.delay


/** Which row of tools shows when nothing is selected. */
internal enum class Menu { Root, Audio, Text }

/** A tool's options, open above the toolbar. */
internal enum class Panel { Speed, Volume, Opacity, Fade, TextNew, TextEdit, Effects, Filters, Transitions, Aspect, Background, Captions, Speech, Voiceover }

/** The panels that belong to the selected clip, closed when it is let go. */
internal val CLIP_PANELS = setOf(Panel.Speed, Panel.Volume, Panel.Opacity, Panel.Fade, Panel.TextEdit, Panel.Effects, Panel.Filters)

/** One tool of the bottom toolbar. */
internal class Tool(val icon: ImageVector, val label: String, val enabled: Boolean = true, val active: Boolean = false, val onClick: () -> Unit)

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
internal fun Transport(
    position: Double,
    duration: Double,
    playing: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    keyframe: Boolean?,
    fullscreen: Boolean,
    onPlay: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onKeyframe: () -> Unit,
    onFullscreen: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(Dimens.transport).padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = ConcatColors.fg)) { append(shortTimecode(position)) }
                withStyle(SpanStyle(color = ConcatColors.fgDim)) { append(" / " + shortTimecode(duration)) }
            },
            style = ConcatType.timecode,
            modifier = Modifier.weight(1f),
        )
        IconAction(if (playing) ConcatIcons.Pause else ConcatIcons.Play, if (playing) "Pause" else "Play", onPlay, enabled = duration > 0, dimensions = IconActionDimensions(24.dp, 44.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (keyframe != null) {
                IconAction(
                    if (keyframe) ConcatIcons.KeyframeFilled else ConcatIcons.Keyframe,
                    "Keyframe",
                    onKeyframe,
                    tint = if (keyframe) ConcatColors.accent else ConcatColors.fg,
                    dimensions = IconActionDimensions(18.dp, 36.dp),
                )
            }
            IconAction(ConcatIcons.Undo, "Undo", onUndo, enabled = canUndo, dimensions = IconActionDimensions(20.dp, 36.dp))
            IconAction(ConcatIcons.Redo, "Redo", onRedo, enabled = canRedo, dimensions = IconActionDimensions(20.dp, 36.dp))
            IconAction(ConcatIcons.Maximize, if (fullscreen) "Leave full screen" else "Full screen", onFullscreen, dimensions = IconActionDimensions(18.dp, 36.dp),
                tint = if (fullscreen) ConcatColors.accent else ConcatColors.fg)
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

internal class ToolActions(
    val openPanel: (Panel) -> Unit,
    val setMenu: (Menu) -> Unit,
    val deselect: () -> Unit,
    val addMedia: () -> Unit,
    val addOverlay: () -> Unit,
    val addAudio: () -> Unit,
    val selectMain: () -> Unit,
    val split: () -> Unit,
    val catalogue: (Shelf, Boolean) -> Unit,
    val model: EditorViewModel,
)

/** The toolbar for what is selected (or the menu open when nothing is): only what the engine does. */
internal fun toolsFor(selected: Clip?, menu: Menu, mainTrackId: String?, panel: Panel?, actions: ToolActions): List<Tool> {
    val a = actions
    val m = a.model
    fun open(which: Panel) = { a.openPanel(which) }
    if (selected == null) {
        return when (menu) {
            Menu.Root -> listOf(
                Tool(ConcatIcons.Razor, "Edit", onClick = a.selectMain),
                Tool(ConcatIcons.Music, "Audio") { a.setMenu(Menu.Audio) },
                Tool(ConcatIcons.TextMark, "Text") { a.setMenu(Menu.Text) },
                Tool(ConcatIcons.Image, "Overlay", onClick = a.addOverlay),
                Tool(ConcatIcons.Sparkle, "Effects", active = panel == Panel.Effects) {
                    a.selectMain()
                    a.openPanel(Panel.Effects)
                },
                Tool(ConcatIcons.Filter, "Filters", active = panel == Panel.Filters) {
                    a.selectMain()
                    a.openPanel(Panel.Filters)
                },
                Tool(ConcatIcons.Rows, "Captions", active = panel == Panel.Captions, onClick = open(Panel.Captions)),
                Tool(ConcatIcons.Transition, "Transitions", active = panel == Panel.Transitions, onClick = open(Panel.Transitions)),
                Tool(ConcatIcons.Fit, "Aspect", active = panel == Panel.Aspect, onClick = open(Panel.Aspect)),
                Tool(ConcatIcons.Slot, "Background", active = panel == Panel.Background, onClick = open(Panel.Background)),
                Tool(ConcatIcons.Sliders, "Adjust") {
                    a.selectMain()
                    a.catalogue(Shelf.Effects, true)
                },
                Tool(ConcatIcons.Import, "Media", onClick = a.addMedia),
            )
            Menu.Audio -> listOf(
                Tool(ConcatIcons.ChevronLeft, "") { a.setMenu(Menu.Root) },
                Tool(ConcatIcons.Import, "Sounds", onClick = a.addAudio),
                Tool(ConcatIcons.Mic, "Voiceover", active = panel == Panel.Voiceover, onClick = open(Panel.Voiceover)),
                Tool(ConcatIcons.Waveform, "Text to speech", active = panel == Panel.Speech, onClick = open(Panel.Speech)),
                Tool(ConcatIcons.Rows, "Captions", active = panel == Panel.Captions, onClick = open(Panel.Captions)),
            )
            Menu.Text -> listOf(
                Tool(ConcatIcons.ChevronLeft, "") { a.setMenu(Menu.Root) },
                Tool(ConcatIcons.Plus, "Add text", active = panel == Panel.TextNew, onClick = open(Panel.TextNew)),
                Tool(ConcatIcons.Rows, "Auto captions", active = panel == Panel.Captions, onClick = open(Panel.Captions)),
                Tool(ConcatIcons.Waveform, "Text to speech", active = panel == Panel.Speech, onClick = open(Panel.Speech)),
            )
        }
    }
    val back = Tool(ConcatIcons.ChevronLeft, "", onClick = a.deselect)
    if (selected.kind == ClipKind.Transition) {
        return listOf(
            back,
            Tool(ConcatIcons.Transition, "Change", active = panel == Panel.Transitions, onClick = open(Panel.Transitions)),
            Tool(ConcatIcons.Sliders, "Adjust") { a.catalogue(Shelf.Transitions, true) },
            Tool(ConcatIcons.Copy, "Duplicate", onClick = m::duplicate),
            Tool(ConcatIcons.Trash, "Delete", onClick = m::delete),
        )
    }
    val picture = selected.kind == ClipKind.Video || selected.kind == ClipKind.Image
    val sound = selected.kind == ClipKind.Video || selected.kind == ClipKind.Audio
    val moved = selected.scale != 1.0 || selected.offsetX != 0.0 || selected.offsetY != 0.0 || selected.rotation != 0.0
    return buildList {
        add(back)
        if (selected.kind == ClipKind.Text) add(Tool(ConcatIcons.TextMark, "Edit", active = panel == Panel.TextEdit, onClick = open(Panel.TextEdit)))
        add(Tool(ConcatIcons.Split, "Split", onClick = a.split))
        if (selected.kind != ClipKind.Text) add(Tool(ConcatIcons.SpeedConstant, "Speed", active = panel == Panel.Speed, onClick = open(Panel.Speed)))
        if (sound) add(Tool(ConcatIcons.Volume, "Volume", active = panel == Panel.Volume, onClick = open(Panel.Volume)))
        if (sound) add(Tool(ConcatIcons.Curves, "Fade", active = panel == Panel.Fade, onClick = open(Panel.Fade)))
        add(Tool(ConcatIcons.Trash, "Delete", onClick = m::delete))
        add(Tool(ConcatIcons.Copy, "Duplicate", onClick = m::duplicate))
        add(Tool(ConcatIcons.Sparkle, "Effects", active = panel == Panel.Effects, onClick = open(Panel.Effects)))
        if (selected.kind != ClipKind.Audio) add(Tool(ConcatIcons.Filter, "Filters", active = panel == Panel.Filters, onClick = open(Panel.Filters)))
        add(Tool(ConcatIcons.Sliders, "Adjust") { a.catalogue(Shelf.Effects, true) })
        if (selected.kind != ClipKind.Audio) add(Tool(ConcatIcons.Eye, "Opacity", active = panel == Panel.Opacity, onClick = open(Panel.Opacity)))
        if (picture) add(Tool(ConcatIcons.FlipH, "Flip", active = selected.flipH, onClick = m::flip))
        if (selected.kind == ClipKind.Video) add(Tool(if (selected.muted) ConcatIcons.VolumeOff else ConcatIcons.Volume, if (selected.muted) "Unmute" else "Mute", onClick = m::toggleMute))
        if (selected.placeable) add(Tool(ConcatIcons.Reset, "Reset", enabled = moved, onClick = m::resetTransform))
        if (selected.kind.hasSound) add(Tool(ConcatIcons.Rows, "Captions", active = panel == Panel.Captions, onClick = open(Panel.Captions)))
        if (picture && selected.trackId == mainTrackId) add(Tool(ConcatIcons.Transition, "Transition", active = panel == Panel.Transitions, onClick = open(Panel.Transitions)))
    }
}

