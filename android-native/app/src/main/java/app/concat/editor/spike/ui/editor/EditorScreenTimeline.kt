// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import app.concat.editor.spike.concat
import app.concat.editor.spike.api.Clip
import app.concat.editor.spike.api.ProjectView
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds

@Composable
internal fun ColumnScope.EditorScreenTimelineAndToolbar(
    state: EditorScreenContentContext,
    view: ProjectView?,
    selected: Clip?,
    mainTrackId: String?,
    imeVisible: Boolean,
) {
    val application = state.models.application
    val path = state.models.path
    val model = state.models.model
    val captions = state.models.captions
    val speech = state.models.speech
    val voiceover = state.models.voiceover
    val catalogue = state.models.catalogue
    val ui = state.ui
    val actions = state.actions
    val addMedia = actions.addMedia
    var panel by state.transient.panel
    var menu by state.transient.menu
    var catalogueShelf by state.transient.catalogueShelf
    var catalogueAdjust by state.transient.catalogueAdjust
    val fullscreen by state.transient.fullscreen
    val haptics = LocalHapticFeedback.current
    if (!fullscreen) {
                // Clipped, so a panel sliding up never draws over the toolbar.
                EditorTimelineArea(
                    state = EditorTimelineState(
                        view = view,
                        projectPath = path,
                        media = application.concat.media,
                        position = ui.position,
                        selected = ui.selected,
                        panel = panel,
                        model = model,
                        panelContext = EditorPanelContext(
                            view, selected, model, captions, speech, voiceover, catalogue,
                            ui.stickers, ui.cover, view?.cover != null,
                            EditorPanelActions(
                                onClose = { panel = null },
                                onOpenCatalogue = { shelf ->
                                    catalogueAdjust = false
                                    catalogueShelf = shelf
                                },
                                onPickCover = actions.onPickCover,
                            ),
                        ),
                    ),
                    actions = EditorTimelineActions(
                        onScrub = model::scrubTo,
                        onSelect = {
                            model.select(it)
                            if (it != null) menu = Menu.Root
                        },
                        onMove = model::move,
                        onTrim = model::trim,
                        onAddMedia = addMedia,
                        onTransition = { next ->
                            model.select(next)
                            panel = Panel.Transitions
                        },
                        onMuteTrack = model::setTrackSoundMuted,
                    ),
                    modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(),
                )

                if (!imeVisible) {
                    val tools = toolsFor(
                        selected = selected,
                        menu = menu,
                        mainTrackId = mainTrackId,
                        panel = panel,
                        actions = ToolActions(
                            openPanel = { panel = if (panel == it) null else it },
                            setMenu = { menu = it },
                            deselect = {
                                model.select(null)
                                panel = null
                            },
                            addMedia = addMedia,
                            addOverlay = { actions.addOverlay() },
                            addAudio = { actions.addAudio() },
                            replace = actions.replace,
                            reversing = ui.reversing,
                            selectMain = { model.selectAtPlayhead(mainTrackId) },
                            split = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                model.split()
                            },
                            catalogue = { shelf, adjust ->
                                catalogueAdjust = adjust
                                catalogueShelf = shelf
                            },
                            model = model,
                        ),
                    )
                    Toolbar(tools, key = "${selected?.kind}-${selected != null}-$menu")
                }
            }
}
