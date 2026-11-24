// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import androidx.compose.runtime.Composable
import android.graphics.Bitmap
import app.concat.editor.spike.api.ShapeCard
import app.concat.editor.spike.api.Clip
import app.concat.editor.spike.api.ProjectView

internal data class EditorPanelActions(
    val onClose: () -> Unit,
    val onOpenCatalogue: (Shelf) -> Unit,
    val onPickCover: () -> Unit,
)

internal data class EditorPanelContext(
    val view: ProjectView?,
    val selected: Clip?,
    val model: EditorViewModel,
    val captions: CaptionsViewModel,
    val speech: SpeechViewModel,
    val voiceover: VoiceoverViewModel,
    val catalogue: CatalogueViewModel,
    val stickers: List<ShapeCard>,
    val cover: Bitmap?,
    val coverNamed: Boolean,
    val actions: EditorPanelActions,
)

@Composable
internal fun EditorPanelContent(panel: Panel?, context: EditorPanelContext) {
    val close = context.actions.onClose
    val model = context.model
    val selected = context.selected
    when (panel) {
        Panel.Speed -> selected?.let { SpeedPanel(it, model::setSpeed, close) }
        Panel.Volume -> selected?.let { VolumePanel(it, model::setVolume, model::toggleMute, close) }
        Panel.Opacity -> selected?.let { OpacityPanel(it, model::setOpacity, close) }
        Panel.Fade -> selected?.let { FadePanel(it, model::setFades, close) }
        Panel.TextNew -> TextPanel(null, { c, s, col, wt -> model.saveText(null, c, s, col, wt) }, close)
        Panel.TextEdit -> selected?.let { clip ->
            TextPanel(clip, { c, s, col, wt -> model.saveText(clip.id, c, s, col, wt) }, close)
        }
        Panel.Effects -> PackagePanel(Shelf.Effects, context.catalogue, model, onMore = {
            context.actions.onOpenCatalogue(Shelf.Effects)
        }, onClose = close)
        Panel.Filters -> PackagePanel(Shelf.Filters, context.catalogue, model, onMore = {
            context.actions.onOpenCatalogue(Shelf.Filters)
        }, onClose = close)
        Panel.Transitions -> PackagePanel(Shelf.Transitions, context.catalogue, model, onMore = {
            context.actions.onOpenCatalogue(Shelf.Transitions)
        }, onClose = close)
        Panel.Aspect -> context.view?.let { view -> AspectPanel(view, { model.setAspect(it.width, it.height) }, close) }
        Panel.Background -> BackgroundPanel(model::setBackground, close)
        Panel.Crop -> selected?.let { CropPanel(it, { model.setLook("Crop", it) }, close) }
        Panel.Mask -> selected?.let { MaskPanel(it, { model.setLook("Mask", it) }, close) }
        Panel.Animation -> selected?.let { AnimationPanel(it, { model.setLook("Animation", it) }, close) }
        Panel.Stickers -> StickersPanel(context.stickers, { card -> model.addSticker(card, stickerName(card)) }, close)
        Panel.Cover -> CoverPanel(
            context.cover,
            named = context.coverNamed,
            actions = CoverActions(model::setCoverFrame, context.actions.onPickCover, model::resetCover),
            onClose = close,
        )
        Panel.Captions -> CaptionsSheet(context.captions, model, preferClip = selected?.id, onDismiss = close)
        Panel.Speech -> SpeechSheet(context.speech, model, onDismiss = close)
        Panel.Voiceover -> VoiceoverSheet(context.voiceover, model, onDismiss = close)
        null -> Unit
    }
}
