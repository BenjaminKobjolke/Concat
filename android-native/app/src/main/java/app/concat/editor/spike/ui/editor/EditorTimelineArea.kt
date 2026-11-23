// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import app.concat.editor.spike.api.ProjectView
import app.concat.editor.spike.media.MediaCache

internal data class EditorTimelineState(
    val view: ProjectView?,
    val projectPath: String,
    val media: MediaCache,
    val position: Double,
    val selected: String?,
    val panel: Panel?,
    val model: EditorViewModel,
    val panelContext: EditorPanelContext,
)

internal data class EditorTimelineActions(
    val onScrub: (Double) -> Unit,
    val onSelect: (String?) -> Unit,
    val onMove: (String, Double, LaneTarget) -> Unit,
    val onTrim: (String, String, Double) -> Unit,
    val onAddMedia: () -> Unit,
    val onTransition: (String?) -> Unit,
    val onMuteTrack: (String, Boolean) -> Unit,
)

@Composable
internal fun EditorTimelineArea(state: EditorTimelineState, actions: EditorTimelineActions, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val room = maxHeight
        state.view?.let { view ->
            Timeline(
                presentation = TimelinePresentation(
                    view, state.projectPath, state.media, state.position, state.selected,
                ),
                actions = TimelineCallbacks(
                    actions.onScrub, actions.onSelect, actions.onMove, actions.onTrim,
                    actions.onAddMedia, actions.onTransition, actions.onMuteTrack,
                ),
            )
        }
        PanelSlot(visible = state.panel != null, modifier = Modifier.align(Alignment.BottomCenter)) {
            Box(Modifier.fillMaxWidth().heightIn(max = room)) {
                EditorPanelContent(state.panel, state.panelContext)
            }
        }
    }
}
