// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.concat.editor.api.Clip
import app.concat.editor.api.ProjectView

internal data class EditorPanelContext(
    val view: ProjectView?,
    val selected: Clip?,
    val ui: EditorUi,
    val model: EditorViewModel,
    val catalogue: CatalogueViewModel,
    val captions: CaptionsViewModel,
    val speech: SpeechViewModel,
    val voiceover: VoiceoverViewModel,
    val onClose: () -> Unit,
    val onCoverPicture: () -> Unit,
    val onCatalogue: (Shelf, Boolean) -> Unit,
)

@Composable
internal fun EditorPanelContent(panel: Panel?, room: Dp, context: EditorPanelContext) {
    val (view, selected, ui, model, catalogue, captions, speech, voiceover) = context
    val close = context.onClose
    Box(Modifier.fillMaxWidth().heightIn(max = room)) {
        when (panel) {
            Panel.Speed -> selected?.let { SpeedPanel(it, model::setSpeed, close) }
            Panel.Volume -> selected?.let { VolumePanel(it, model::setVolume, model::toggleMute, close) }
            Panel.Opacity -> selected?.let { OpacityPanel(it, model::setOpacity, close) }
            Panel.Fade -> selected?.let { FadePanel(it, model::setFades, close) }
            Panel.Crop -> selected?.let { CropPanel(it, { patch -> model.setLook("Crop", patch) }, close) }
            Panel.Mask -> selected?.let { MaskPanel(it, { patch -> model.setLook("Mask", patch) }, close) }
            Panel.Animation -> selected?.let { AnimationPanel(it, { patch -> model.setLook("Animation", patch) }, close) }
            Panel.TextNew -> TextPanel(null, { c, s, col, wt -> model.saveText(null, c, s, col, wt) }, close)
            Panel.TextEdit -> selected?.let { clip ->
                TextPanel(
                    clip,
                    { c, s, col, wt -> model.saveText(clip.id, c, s, col, wt) },
                    close,
                    caption = view?.tracks?.firstOrNull { it.id == clip.trackId }?.captions != null,
                )
            }
            Panel.Effects -> PackagePanel(Shelf.Effects, catalogue, model, onMore = { context.onCatalogue(Shelf.Effects, false) }, onClose = close)
            Panel.Filters -> PackagePanel(Shelf.Filters, catalogue, model, onMore = { context.onCatalogue(Shelf.Filters, false) }, onClose = close)
            Panel.Transitions -> PackagePanel(Shelf.Transitions, catalogue, model, onMore = { context.onCatalogue(Shelf.Transitions, false) }, onClose = close)
            Panel.Aspect -> view?.let { v -> AspectPanel(v, { model.setAspect(it.width, it.height) }, close) }
            Panel.Background -> BackgroundPanel(model::setBackground, close)
            Panel.Cover -> CoverPanel(
                ui.cover,
                named = view?.cover != null,
                onFrame = model::setCoverFrame,
                onPicture = context.onCoverPicture,
                onReset = model::resetCover,
                onClose = close,
            )
            Panel.Stickers -> StickersPanel(ui.stickers, { card -> model.addSticker(card, stickerName(card)) }, close)
            Panel.Captions -> CaptionsSheet(captions, model, preferClip = selected?.id, onDismiss = close)
            Panel.Speech -> SpeechSheet(speech, model, onDismiss = close)
            Panel.Voiceover -> VoiceoverSheet(voiceover, model, onDismiss = close)
            null -> Unit
        }
    }
}
