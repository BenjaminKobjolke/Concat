// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import app.concat.editor.api.Clip
import app.concat.editor.api.ClipKind
import app.concat.editor.ui.icons.ConcatIcons

internal data class EditorToolRowContext(
    val selected: Clip?,
    val menu: Menu,
    val mainTrackId: String?,
    val panel: Panel?,
    val ui: EditorUi,
    val model: EditorViewModel,
    val haptics: HapticFeedback,
    val openPanel: (Panel) -> Unit,
    val setMenu: (Menu) -> Unit,
    val deselect: () -> Unit,
    val addMedia: () -> Unit,
    val addOverlay: () -> Unit,
    val addAudio: () -> Unit,
    val replace: (Boolean) -> Unit,
    val selectMain: () -> Unit,
    val split: () -> Unit,
    val catalogue: (Shelf, Boolean) -> Unit,
)

@Composable
internal fun EditorToolRow(context: EditorToolRowContext) {
    val actions = ToolActions(
        openPanel = context.openPanel,
        setMenu = context.setMenu,
        deselect = context.deselect,
        addMedia = context.addMedia,
        addOverlay = context.addOverlay,
        addAudio = context.addAudio,
        replace = context.replace,
        reversing = context.ui.reversing,
        selectMain = context.selectMain,
        split = {
            context.haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            context.split()
        },
        catalogue = context.catalogue,
        model = context.model,
    )
    Toolbar(
        toolsFor(ToolSelection(context.selected, context.menu, context.mainTrackId, context.panel), actions),
        key = "${context.selected?.kind}-${context.selected != null}-${context.menu}",
    )
}

/** The toolbar for what is selected (or the menu open when nothing is): only what the engine does. */
internal data class ToolSelection(val selected: Clip?, val menu: Menu, val mainTrackId: String?, val panel: Panel?)

internal fun toolsFor(selection: ToolSelection, actions: ToolActions): List<Tool> {
    val (selected, menu, mainTrackId, panel) = selection
    return if (selected == null) menuTools(menu, panel, actions)
    else selectedTools(selected, mainTrackId, panel, actions)
}

private fun menuTools(menu: Menu, panel: Panel?, actions: ToolActions): List<Tool> {
    val open: (Panel) -> () -> Unit = { which -> { actions.openPanel(which) } }
    return when (menu) {
            Menu.Root -> listOf(
                Tool(ConcatIcons.Razor, "Edit", onClick = actions.selectMain),
                Tool(ConcatIcons.Music, "Audio") { actions.setMenu(Menu.Audio) },
                Tool(ConcatIcons.TextMark, "Text") { actions.setMenu(Menu.Text) },
                Tool(ConcatIcons.Sticker, "Stickers", active = panel == Panel.Stickers, onClick = open(Panel.Stickers)),
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

private fun selectedTools(selected: Clip, mainTrackId: String?, panel: Panel?, actions: ToolActions): List<Tool> {
    val m = actions.model
    val open: (Panel) -> () -> Unit = { which -> { actions.openPanel(which) } }
    if (selected.kind == ClipKind.Transition) {
        return listOf(
            Tool(ConcatIcons.ChevronLeft, "", onClick = actions.deselect),
            Tool(ConcatIcons.Transition, "Change", active = panel == Panel.Transitions, onClick = open(Panel.Transitions)),
            Tool(ConcatIcons.Sliders, "Adjust") { actions.catalogue(Shelf.Transitions, true) },
            Tool(ConcatIcons.Copy, "Duplicate", onClick = m::duplicate),
            Tool(ConcatIcons.Trash, "Delete", onClick = m::delete),
        )
    }
    return selectedStartTools(selected, panel, actions, open) + selectedSoundStartTools(selected, panel, open) +
        selectedBaseTools(selected, panel, actions, open) + selectedPictureTools(selected, panel, actions, open) +
        selectedSoundEndTools(selected, panel, open, actions) + selectedSoundTailTools(selected, panel, open, actions) +
        selectedTransitionTool(selected, mainTrackId, panel, open)
}

private fun selectedBaseTools(selected: Clip, panel: Panel?, actions: ToolActions, open: (Panel) -> () -> Unit): List<Tool> = buildList {
        val model = actions.model
        add(Tool(ConcatIcons.Trash, "Delete", onClick = model::delete))
        add(Tool(ConcatIcons.Copy, "Duplicate", onClick = model::duplicate))
        add(Tool(ConcatIcons.Sparkle, "Effects", active = panel == Panel.Effects, onClick = open(Panel.Effects)))
        if (selected.kind != ClipKind.Audio) add(Tool(ConcatIcons.Filter, "Filters", active = panel == Panel.Filters, onClick = open(Panel.Filters)))
        add(Tool(ConcatIcons.Sliders, "Adjust") { actions.catalogue(Shelf.Effects, true) })
        if (selected.kind != ClipKind.Audio) add(Tool(ConcatIcons.Eye, "Opacity", active = panel == Panel.Opacity, onClick = open(Panel.Opacity)))
    }

private fun selectedStartTools(selected: Clip, panel: Panel?, actions: ToolActions, open: (Panel) -> () -> Unit) = buildList {
    add(Tool(ConcatIcons.ChevronLeft, "", onClick = actions.deselect))
    if (selected.kind == ClipKind.Text) add(Tool(ConcatIcons.TextMark, "Edit", active = panel == Panel.TextEdit, onClick = open(Panel.TextEdit)))
    add(Tool(ConcatIcons.Split, "Split", onClick = actions.split))
    if (selected.kind != ClipKind.Text) add(Tool(ConcatIcons.SpeedConstant, "Speed", active = panel == Panel.Speed, onClick = open(Panel.Speed)))
}

private fun selectedSoundStartTools(selected: Clip, panel: Panel?, open: (Panel) -> () -> Unit) = buildList {
    if (selected.kind == ClipKind.Video || selected.kind == ClipKind.Audio) {
        add(Tool(ConcatIcons.Volume, "Volume", active = panel == Panel.Volume, onClick = open(Panel.Volume)))
        add(Tool(ConcatIcons.Curves, "Fade", active = panel == Panel.Fade, onClick = open(Panel.Fade)))
    }
}

private fun selectedPictureTools(selected: Clip, panel: Panel?, actions: ToolActions, open: (Panel) -> () -> Unit): List<Tool> {
    val picture = selected.kind == ClipKind.Video || selected.kind == ClipKind.Image
    val model = actions.model
    return buildList {
        if (picture) add(Tool(ConcatIcons.FlipH, "Flip", active = selected.flipH, onClick = model::flip))
        if (selected.placeable) add(Tool(ConcatIcons.Wand, "Animation", active = panel == Panel.Animation, onClick = open(Panel.Animation)))
        if (picture) add(Tool(ConcatIcons.Frame, "Crop", active = panel == Panel.Crop, onClick = open(Panel.Crop)))
        if (picture) add(Tool(ConcatIcons.MaskCircle, "Mask", active = panel == Panel.Mask, onClick = open(Panel.Mask)))
        if (picture) add(Tool(ConcatIcons.Pause, "Freeze", onClick = model::freeze))
    }
}

private fun selectedSoundEndTools(selected: Clip, panel: Panel?, open: (Panel) -> () -> Unit, actions: ToolActions): List<Tool> {
    val model = actions.model
    return buildList {
        if (selected.kind == ClipKind.Video || selected.kind == ClipKind.Audio) {
            val running = actions.reversing
            add(if (running?.first == selected.id) Tool(ConcatIcons.Reverse, "Reversing ${(running.second * 100).toInt()}%", active = true, onClick = model::cancelReverse)
            else Tool(ConcatIcons.Reverse, "Reverse", enabled = running == null, onClick = model::reverse))
        }
        if (selected.kind == ClipKind.Video || selected.kind == ClipKind.Audio) add(Tool(ConcatIcons.Import, "Replace") { actions.replace(selected.kind == ClipKind.Audio) })
        if (selected.kind == ClipKind.Video) add(Tool(if (selected.muted) ConcatIcons.VolumeOff else ConcatIcons.Volume, if (selected.muted) "Unmute" else "Mute", onClick = model::toggleMute))
    }
}

private fun selectedSoundTailTools(selected: Clip, panel: Panel?, open: (Panel) -> () -> Unit, actions: ToolActions) = buildList {
    val model = actions.model
    val moved = selected.scale != 1.0 || selected.offsetX != 0.0 || selected.offsetY != 0.0 || selected.rotation != 0.0
    if (selected.placeable && moved) add(Tool(ConcatIcons.Reset, "Reset", onClick = model::resetTransform))
    if (selected.kind.hasSound) add(Tool(ConcatIcons.Rows, "Captions", active = panel == Panel.Captions, onClick = open(Panel.Captions)))
}

private fun selectedTransitionTool(selected: Clip, mainTrackId: String?, panel: Panel?, open: (Panel) -> () -> Unit) =
    if ((selected.kind == ClipKind.Video || selected.kind == ClipKind.Image) && selected.trackId == mainTrackId) {
        listOf(Tool(ConcatIcons.Transition, "Transition", active = panel == Panel.Transitions, onClick = open(Panel.Transitions)))
    } else emptyList()

