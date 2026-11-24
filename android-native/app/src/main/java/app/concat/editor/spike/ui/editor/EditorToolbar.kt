// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.concat.editor.spike.api.Clip
import app.concat.editor.spike.api.ClipKind
import app.concat.editor.spike.ui.components.IconAction
import app.concat.editor.spike.ui.components.IconActionDimensions
import app.concat.editor.spike.ui.icons.ConcatIcons
import app.concat.editor.spike.ui.shortTimecode
import app.concat.editor.spike.ui.theme.ConcatColors
import app.concat.editor.spike.ui.theme.ConcatType
import app.concat.editor.spike.ui.theme.Dimens
import app.concat.editor.spike.ui.theme.Spacing
/** Which row of tools shows when nothing is selected. */
internal enum class Menu { Root, Audio, Text }

/** A tool's options, open above the toolbar. */
internal enum class Panel { Speed, Volume, Opacity, Fade, Crop, Mask, Animation, TextNew, TextEdit, Effects, Filters, Transitions, Aspect, Background, Captions, Speech, Voiceover, Stickers, Cover }

/** The panels that belong to the selected clip, closed when it is let go. */
internal val CLIP_PANELS = setOf(Panel.Speed, Panel.Volume, Panel.Opacity, Panel.Fade, Panel.Crop, Panel.Mask, Panel.Animation, Panel.TextEdit, Panel.Effects, Panel.Filters)

/** One tool of the bottom toolbar. */
internal class Tool(val icon: ImageVector, val label: String, val enabled: Boolean = true, val active: Boolean = false, val onClick: () -> Unit)

internal data class TransportState(
    val position: Double,
    val duration: Double,
    val playing: Boolean,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val keyframe: Boolean?,
    val fullscreen: Boolean,
)

internal data class TransportActions(
    val onPlay: () -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onKeyframe: () -> Unit,
    val onFullscreen: () -> Unit,
)

@Composable
internal fun Transport(state: TransportState, actions: TransportActions) {
    Row(
        Modifier.fillMaxWidth().height(Dimens.transport).padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = ConcatColors.fg)) { append(shortTimecode(state.position)) }
                withStyle(SpanStyle(color = ConcatColors.fgDim)) { append(" / " + shortTimecode(state.duration)) }
            },
            style = ConcatType.timecode,
            modifier = Modifier.weight(1f),
        )
        IconAction(if (state.playing) ConcatIcons.Pause else ConcatIcons.Play, if (state.playing) "Pause" else "Play", actions.onPlay, enabled = state.duration > 0, dimensions = IconActionDimensions(24.dp, 44.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (state.keyframe != null) {
                IconAction(
                    if (state.keyframe) ConcatIcons.KeyframeFilled else ConcatIcons.Keyframe,
                    "Keyframe",
                    actions.onKeyframe,
                    tint = if (state.keyframe) ConcatColors.accent else ConcatColors.fg,
                    dimensions = IconActionDimensions(18.dp, 36.dp),
                )
            }
            IconAction(ConcatIcons.Undo, "Undo", actions.onUndo, enabled = state.canUndo, dimensions = IconActionDimensions(20.dp, 36.dp))
            IconAction(ConcatIcons.Redo, "Redo", actions.onRedo, enabled = state.canRedo, dimensions = IconActionDimensions(20.dp, 36.dp))
            IconAction(ConcatIcons.Maximize, if (state.fullscreen) "Leave full screen" else "Full screen", actions.onFullscreen, dimensions = IconActionDimensions(18.dp, 36.dp),
                tint = if (state.fullscreen) ConcatColors.accent else ConcatColors.fg)
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
    val replace: (Boolean) -> Unit,
    val reversing: Pair<String, Float>?,
    val selectMain: () -> Unit,
    val split: () -> Unit,
    val catalogue: (Shelf, Boolean) -> Unit,
    val model: EditorViewModel,
)

/** The toolbar for what is selected (or the menu open when nothing is): only what the engine does. */
internal fun toolsFor(selected: Clip?, menu: Menu, mainTrackId: String?, panel: Panel?, actions: ToolActions): List<Tool> {
    return if (selected == null) menuTools(menu, panel, actions) else clipTools(selected, mainTrackId, panel, actions)
}

private fun menuTools(menu: Menu, panel: Panel?, actions: ToolActions): List<Tool> {
    fun open(which: Panel) = { actions.openPanel(which) }
    return when (menu) {
            Menu.Root -> listOf(
                Tool(ConcatIcons.Razor, "Edit", onClick = actions.selectMain),
                Tool(ConcatIcons.Music, "Audio") { actions.setMenu(Menu.Audio) },
                Tool(ConcatIcons.TextMark, "Text") { actions.setMenu(Menu.Text) },
                Tool(ConcatIcons.Sticker, "Stickers", active = panel == Panel.Stickers, onClick = open(Panel.Stickers)),
                Tool(ConcatIcons.Frame, "Cover", active = panel == Panel.Cover, onClick = open(Panel.Cover)),
                Tool(ConcatIcons.Image, "Overlay", onClick = actions.addOverlay),
                Tool(ConcatIcons.Sparkle, "Effects", active = panel == Panel.Effects) {
                    actions.selectMain()
                    actions.openPanel(Panel.Effects)
                },
                Tool(ConcatIcons.Filter, "Filters", active = panel == Panel.Filters) {
                    actions.selectMain()
                    actions.openPanel(Panel.Filters)
                },
                Tool(ConcatIcons.Rows, "Captions", active = panel == Panel.Captions, onClick = open(Panel.Captions)),
                Tool(ConcatIcons.Transition, "Transitions", active = panel == Panel.Transitions, onClick = open(Panel.Transitions)),
                Tool(ConcatIcons.Fit, "Aspect", active = panel == Panel.Aspect, onClick = open(Panel.Aspect)),
                Tool(ConcatIcons.Slot, "Background", active = panel == Panel.Background, onClick = open(Panel.Background)),
                Tool(ConcatIcons.Sliders, "Adjust") {
                    actions.selectMain()
                    actions.catalogue(Shelf.Effects, true)
                },
                Tool(ConcatIcons.Import, "Media", onClick = actions.addMedia),
            )
            Menu.Audio -> listOf(
                Tool(ConcatIcons.ChevronLeft, "") { actions.setMenu(Menu.Root) },
                Tool(ConcatIcons.Import, "Sounds", onClick = actions.addAudio),
                Tool(ConcatIcons.Mic, "Voiceover", active = panel == Panel.Voiceover, onClick = open(Panel.Voiceover)),
                Tool(ConcatIcons.Waveform, "Text to speech", active = panel == Panel.Speech, onClick = open(Panel.Speech)),
                Tool(ConcatIcons.Rows, "Captions", active = panel == Panel.Captions, onClick = open(Panel.Captions)),
            )
            Menu.Text -> listOf(
                Tool(ConcatIcons.ChevronLeft, "") { actions.setMenu(Menu.Root) },
                Tool(ConcatIcons.Plus, "Add text", active = panel == Panel.TextNew, onClick = open(Panel.TextNew)),
                Tool(ConcatIcons.Rows, "Auto captions", active = panel == Panel.Captions, onClick = open(Panel.Captions)),
                Tool(ConcatIcons.Waveform, "Text to speech", active = panel == Panel.Speech, onClick = open(Panel.Speech)),
            )
    }
}

private fun clipTools(selected: Clip, mainTrackId: String?, panel: Panel?, actions: ToolActions): List<Tool> {
    val m = actions.model
    fun open(which: Panel) = { actions.openPanel(which) }
    val back = Tool(ConcatIcons.ChevronLeft, "", onClick = actions.deselect)
    if (selected.kind == ClipKind.Transition) {
        return listOf(
            back,
            Tool(ConcatIcons.Transition, "Change", active = panel == Panel.Transitions, onClick = open(Panel.Transitions)),
            Tool(ConcatIcons.Sliders, "Adjust") { actions.catalogue(Shelf.Transitions, true) },
            Tool(ConcatIcons.Copy, "Duplicate", onClick = m::duplicate),
            Tool(ConcatIcons.Trash, "Delete", onClick = m::delete),
        )
    }
    return buildList {
        add(back)
        addSelectedClipTools(selected, panel, actions, ::open, this)
        addSelectedClipStateTools(selected, mainTrackId, panel, actions, ::open, this)
    }
}

private fun addSelectedClipTools(
    selected: Clip,
    panel: Panel?,
    actions: ToolActions,
    open: (Panel) -> () -> Unit,
    tools: MutableList<Tool>,
) {
    val model = actions.model
    with(tools) {
        if (selected.kind == ClipKind.Text) add(Tool(ConcatIcons.TextMark, "Edit", active = panel == Panel.TextEdit, onClick = open(Panel.TextEdit)))
        add(Tool(ConcatIcons.Split, "Split", onClick = actions.split))
        if (selected.kind != ClipKind.Text) add(Tool(ConcatIcons.SpeedConstant, "Speed", active = panel == Panel.Speed, onClick = open(Panel.Speed)))
        if (selected.kind == ClipKind.Video || selected.kind == ClipKind.Audio) {
            add(Tool(ConcatIcons.Volume, "Volume", active = panel == Panel.Volume, onClick = open(Panel.Volume)))
            add(Tool(ConcatIcons.Curves, "Fade", active = panel == Panel.Fade, onClick = open(Panel.Fade)))
        }
        add(Tool(ConcatIcons.Trash, "Delete", onClick = model::delete))
        add(Tool(ConcatIcons.Copy, "Duplicate", onClick = model::duplicate))
        add(Tool(ConcatIcons.Sparkle, "Effects", active = panel == Panel.Effects, onClick = open(Panel.Effects)))
        if (selected.kind != ClipKind.Audio) add(Tool(ConcatIcons.Filter, "Filters", active = panel == Panel.Filters, onClick = open(Panel.Filters)))
        add(Tool(ConcatIcons.Sliders, "Adjust") { actions.catalogue(Shelf.Effects, true) })
        if (selected.kind != ClipKind.Audio) add(Tool(ConcatIcons.Eye, "Opacity", active = panel == Panel.Opacity, onClick = open(Panel.Opacity)))
    }
}

private fun addSelectedClipStateTools(
    selected: Clip,
    mainTrackId: String?,
    panel: Panel?,
    actions: ToolActions,
    open: (Panel) -> () -> Unit,
    tools: MutableList<Tool>,
) {
    val picture = selected.kind == ClipKind.Video || selected.kind == ClipKind.Image
    val moved = selected.scale != 1.0 || selected.offsetX != 0.0 || selected.offsetY != 0.0 || selected.rotation != 0.0
    with(tools) {
        addSelectedClipPictureTools(selected, picture, panel, actions, open)
        addSelectedClipReverseTool(selected, actions)
        addSelectedClipStateActions(selected, mainTrackId, panel, actions, open, moved, picture)
    }
}

private fun MutableList<Tool>.addSelectedClipPictureTools(
    selected: Clip,
    picture: Boolean,
    panel: Panel?,
    actions: ToolActions,
    open: (Panel) -> () -> Unit,
) {
    if (picture) add(Tool(ConcatIcons.FlipH, "Flip", active = selected.flipH, onClick = actions.model::flip))
    if (selected.placeable) add(Tool(ConcatIcons.Wand, "Animation", active = panel == Panel.Animation, onClick = open(Panel.Animation)))
    if (picture) add(Tool(ConcatIcons.Frame, "Crop", active = panel == Panel.Crop, onClick = open(Panel.Crop)))
    if (picture) add(Tool(ConcatIcons.MaskCircle, "Mask", active = panel == Panel.Mask, onClick = open(Panel.Mask)))
    if (picture) add(Tool(ConcatIcons.Pause, "Freeze", onClick = actions.model::freeze))
}

private fun MutableList<Tool>.addSelectedClipReverseTool(selected: Clip, actions: ToolActions) {
    if (selected.kind != ClipKind.Video && selected.kind != ClipKind.Audio) return
    val running = actions.reversing
    add(
        if (running?.first == selected.id) {
            Tool(ConcatIcons.Reverse, "Reversing ${(running.second * 100).toInt()}%", active = true, onClick = actions.model::cancelReverse)
        } else {
            Tool(ConcatIcons.Reverse, "Reverse", enabled = running == null, onClick = actions.model::reverse)
        },
    )
}

private fun MutableList<Tool>.addSelectedClipStateActions(
    selected: Clip,
    mainTrackId: String?,
    panel: Panel?,
    actions: ToolActions,
    open: (Panel) -> () -> Unit,
    moved: Boolean,
    picture: Boolean,
) {
    if (picture || selected.kind == ClipKind.Audio) add(Tool(ConcatIcons.Import, "Replace") { actions.replace(selected.kind == ClipKind.Audio) })
    if (selected.kind == ClipKind.Video) {
        add(Tool(if (selected.muted) ConcatIcons.VolumeOff else ConcatIcons.Volume, if (selected.muted) "Unmute" else "Mute", onClick = actions.model::toggleMute))
    }
    if (selected.placeable) add(Tool(ConcatIcons.Reset, "Reset", enabled = moved, onClick = actions.model::resetTransform))
    if (selected.kind.hasSound) add(Tool(ConcatIcons.Rows, "Captions", active = panel == Panel.Captions, onClick = open(Panel.Captions)))
    if (picture && selected.trackId == mainTrackId) add(Tool(ConcatIcons.Transition, "Transition", active = panel == Panel.Transitions, onClick = open(Panel.Transitions)))
}

