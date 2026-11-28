// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.concat.editor.api.AppliedEffect
import app.concat.editor.api.Clip
import app.concat.editor.api.ClipKind
import app.concat.editor.api.PackageInfo
import app.concat.editor.api.ParamInfo
import app.concat.editor.ui.components.ConcatIcon
import app.concat.editor.ui.components.GhostButtonOptions
import app.concat.editor.ui.components.IconAppearance
import app.concat.editor.ui.components.GhostButton
import app.concat.editor.ui.components.PanelLabel
import app.concat.editor.ui.components.ToolPanel
import app.concat.editor.ui.components.ToolPanelOptions
import app.concat.editor.ui.components.ValueSlider
import app.concat.editor.ui.components.ValueSliderOptions
import app.concat.editor.ui.icons.ConcatIcons
import app.concat.editor.ui.theme.ConcatColors
import app.concat.editor.ui.theme.ConcatType
import app.concat.editor.ui.theme.Radii
import app.concat.editor.ui.theme.Spacing

data class PackagePanelActions(val onMore: () -> Unit, val onClose: () -> Unit)

/** Effects, filters or transitions as a row of tiles over the selected clip. */
@Composable
fun PackagePanel(
    shelf: Shelf,
    catalogue: CatalogueViewModel,
    editor: EditorViewModel,
    actions: PackagePanelActions,
) {
    val onMore = actions.onMore
    val onClose = actions.onClose
    val state by catalogue.ui.collectAsStateWithLifecycle()
    val editorUi by editor.ui.collectAsStateWithLifecycle()
    val clip = editorUi.view?.clip(editorUi.selected)
    val kinds = shelf.kinds(clip)
    LaunchedEffect(Unit) { catalogue.load() }
    val offered = state.packages.filter { it.kind in kinds }
    val links = shelfLinks(clip, kinds, catalogue)
    val applied = links.map { it.third.id }.toSet()
    val message = packagePanelMessage(state.loaded, state.error, clip, shelf)

    ToolPanel(
        shelf.title,
        onClose = onClose,
        options = ToolPanelOptions(
            onConfirm = onClose,
            actions = { GhostButton("All", onMore, options = GhostButtonOptions(icon = ConcatIcons.Search, height = 32.dp, modifier = Modifier.padding(end = Spacing.sm))) },
            padding = androidx.compose.foundation.layout.PaddingValues(vertical = Spacing.xs),
        ),
    ) {
        message?.let { PanelLabel(it, Modifier.padding(horizontal = Spacing.lg)) }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.lg),
        ) {
            if (shelf != Shelf.Transitions) {
                item(key = "none") {
                    PackageTileSmall("None", null, links.isEmpty(), clip != null && links.isNotEmpty()) {
                        clip?.let { editor.removeLinks(it.id, links.map { link -> link.first to link.second }) }
                    }
                }
            }
            items(offered, key = { it.id }) { pkg ->
                PackageTileSmall(pkg.name, tileColour(pkg), pkg.id in applied, clip != null || shelf == Shelf.Transitions) {
                    editor.applyPackage(pkg.kind, pkg.id, pkg.name)
                }
            }
        }
        val newest = links.maxByOrNull { if (it.first == "videoEffects") 1000 + it.second else it.second }
        val pkg = newest?.let { catalogue.find(it.third.id) }
        val knob = pkg?.params?.firstOrNull { it.key == pkg.intensity }
            ?: pkg?.params?.firstOrNull { it.type == "float" || it.type == "int" }
        if (clip != null && clip.kind != ClipKind.Transition && newest != null && pkg != null && knob != null) {
            LinkStrength(clip, newest.first, newest.second, newest.third, pkg, knob, editor)
        }
    }
}

private fun packagePanelMessage(loaded: Boolean, error: String?, clip: Clip?, shelf: Shelf): String? = when {
    !loaded -> "Loading..."
    error != null -> error
    clip == null && shelf != Shelf.Transitions -> "Select a clip to apply one."
    else -> null
}

private fun shelfLinks(clip: Clip?, kinds: List<String>, catalogue: CatalogueViewModel) = clip?.let { current ->
    buildList {
        if ("audio" in kinds) current.filters.forEachIndexed { index, link -> add(Triple("filters", index, link)) }
        current.videoEffects.forEachIndexed { index, link ->
            if (belongsToShelf(current, kinds, catalogue.find(link.id)?.kind)) {
                add(Triple("videoEffects", index, link))
            }
        }
    }
}.orEmpty()

private fun belongsToShelf(clip: Clip, kinds: List<String>, kind: String?) =
    clip.kind == ClipKind.Transition || kind in kinds || (kind == null && "effect" in kinds)

@Composable
private fun LinkStrength(clip: Clip, field: String, index: Int, link: AppliedEffect, pkg: PackageInfo, knob: ParamInfo, editor: EditorViewModel) {
    var value by remember(clip.id, field, index, link.id) { mutableFloatStateOf((link.params[knob.key] ?: knob.default).toFloat()) }
    val low = knob.min.toFloat()
    val high = knob.max.toFloat().coerceAtLeast(low + 1e-3f)
    Column(Modifier.padding(horizontal = Spacing.lg)) {
        ValueSlider(
            if (knob.key == pkg.intensity) pkg.name else "${pkg.name} ${knob.label.lowercase()}",
            value,
            low..high,
            ValueSliderOptions(
                { if (knob.type == "int" || knob.step >= 1) "%.0f%s".format(it, knob.unit) else "%.2f%s".format(it, knob.unit) },
                {
                    value = it
                    editor.setLink(clip.id, field, index, link.withParam(knob.key, it.toDouble()))
                },
            ),
        )
    }
}

private fun tileColour(pkg: PackageInfo): Brush {
    val hue = (pkg.id.hashCode() and 0xff) * 360f / 256f
    return Brush.linearGradient(listOf(Color.hsv(hue, 0.5f, 0.7f), Color.hsv((hue + 40f) % 360f, 0.65f, 0.3f)))
}

@Composable
private fun PackageTileSmall(name: String, tint: Brush?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = RoundedCornerShape(Radii.md)
        Box(
            Modifier.size(64.dp).clip(shape)
                .then(if (tint != null) Modifier.background(tint) else Modifier.background(ConcatColors.raised))
                .border(if (selected) 2.dp else 0.dp, if (selected) ConcatColors.accent else Color.Transparent, shape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (tint == null) {
                ConcatIcon(ConcatIcons.EyeOff, appearance = IconAppearance(tint = ConcatColors.fgMuted, size = 22.dp))
            } else if (selected) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(ConcatColors.accent), contentAlignment = Alignment.Center) {
                    ConcatIcon(ConcatIcons.Check, appearance = IconAppearance(tint = ConcatColors.onAccent, size = 14.dp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(name, style = ConcatType.caption, color = if (selected) ConcatColors.accent else if (enabled) ConcatColors.fg else ConcatColors.fgDim, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
