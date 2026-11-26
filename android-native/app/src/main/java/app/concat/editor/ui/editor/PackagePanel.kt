// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.concat.editor.api.AppliedEffect
import app.concat.editor.api.Clip
import app.concat.editor.api.ClipKind
import app.concat.editor.api.PackageInfo
import app.concat.editor.api.ProjectView
import app.concat.editor.ui.components.ChoiceChip
import app.concat.editor.ui.components.ConcatIcon
import app.concat.editor.ui.components.IconAppearance
import app.concat.editor.ui.components.ToolPanelOptions
import app.concat.editor.ui.components.ValueSliderOptions
import app.concat.editor.ui.components.GhostButton
import app.concat.editor.ui.components.GhostButtonOptions
import app.concat.editor.ui.components.PanelLabel
import app.concat.editor.ui.components.ToolPanel
import app.concat.editor.ui.components.ValueSlider
import app.concat.editor.ui.icons.ConcatIcons
import app.concat.editor.ui.theme.ConcatColors
import app.concat.editor.ui.theme.ConcatType
import app.concat.editor.ui.theme.Radii
import app.concat.editor.ui.theme.Spacing
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import org.json.JSONObject

/**
 * Effects, filters or transitions as a row of tiles over the clip:
 * "None" first, a tap applies, and the newest of this shelf's links
 * gets its strength slider beneath. "All" opens the full catalogue sheet
 * with search, categories and every knob.
 */
@Composable
fun PackagePanel(
    shelf: Shelf,
    catalogue: CatalogueViewModel,
    editor: EditorViewModel,
    actions: PackagePanelActions,
) {
    val state by catalogue.ui.collectAsStateWithLifecycle()
    val editorUi by editor.ui.collectAsStateWithLifecycle()
    val clip = editorUi.view?.clip(editorUi.selected)
    val kinds = shelf.kinds(clip)
    LaunchedEffect(Unit) { catalogue.load() }
    val panel = PackagePanelData(state.packages.filter { it.kind in kinds }, shelfLinks(clip, kinds, catalogue))

    ToolPanel(
        shelf.title,
        onClose = actions.onClose,
        options = ToolPanelOptions(
            onConfirm = actions.onClose,
            actions = { GhostButton("All", actions.onMore, options = GhostButtonOptions(icon = ConcatIcons.Search, height = 32.dp, modifier = Modifier.padding(end = Spacing.sm))) },
            padding = androidx.compose.foundation.layout.PaddingValues(vertical = Spacing.xs),
        ),
    ) {
        ShelfStatus(state, clip, shelf)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.lg),
        ) {
            if (shelf != Shelf.Transitions) {
                item(key = "none") {
                    PackageTileSmall(
                        name = "None",
                        options = PackageTileOptions(
                            tint = null,
                            selected = panel.links.isEmpty(),
                            enabled = clip != null && panel.links.isNotEmpty(),
                        ),
                    ) {
                        clip?.let { c -> editor.removeLinks(c.id, panel.links.map { it.first to it.second }) }
                    }
                }
            }
            items(panel.offered, key = { it.id }) { pkg ->
                PackageTileSmall(
                    name = pkg.name,
                    options = PackageTileOptions(
                        tint = tileColour(pkg),
                        selected = pkg.id in panel.applied,
                        enabled = clip != null || shelf == Shelf.Transitions,
                    ),
                ) { editor.applyPackage(pkg.kind, pkg.id, pkg.name) }
            }
        }
        // The newest link's strength, live as the finger moves.
        val newest = newestPackageLink(panel.links)
        val pkg = newest?.let { catalogue.find(it.third.id) }
        val knob = pkg?.params?.firstOrNull { it.key == pkg.intensity } ?: pkg?.params?.firstOrNull { it.type == "float" || it.type == "int" }
        if (clip != null && newest != null && pkg != null && knob != null) {
            LinkStrength(LinkStrengthData(clip, newest.first, newest.second, newest.third, pkg, knob, editor))
        }
    }
}

data class PackagePanelActions(val onMore: () -> Unit, val onClose: () -> Unit)

private data class PackagePanelData(val offered: List<PackageInfo>, val links: List<Triple<String, Int, AppliedEffect>>) {
    val applied: Set<String> get() = links.map { it.third.id }.toSet()
}

private fun newestPackageLink(links: List<Triple<String, Int, AppliedEffect>>) =
    links.maxByOrNull { if (it.first == "videoEffects") 1000 + it.second else it.second }

private fun shelfLinks(clip: Clip?, kinds: List<String>, catalogue: CatalogueViewModel): List<Triple<String, Int, AppliedEffect>> = clip?.let { c ->
        buildList {
            if ("audio" in kinds) c.filters.forEachIndexed { i, link -> add(Triple("filters", i, link)) }
            c.videoEffects.forEachIndexed { i, link ->
                val kind = catalogue.find(link.id)?.kind
                if (c.kind == ClipKind.Transition || kind in kinds || (kind == null && "effect" in kinds)) add(Triple("videoEffects", i, link))
            }
        }
    }.orEmpty()

@Composable
private fun ShelfStatus(state: CatalogueUi, clip: Clip?, shelf: Shelf) {
    val message = when {
        !state.loaded -> "Loading..."
        state.error != null -> state.error
        clip == null && shelf != Shelf.Transitions -> "Select a clip to apply one."
        else -> null
    }
    message?.let { PanelLabel(it, Modifier.padding(horizontal = Spacing.lg)) }
}

private data class LinkStrengthData(
    val clip: Clip,
    val field: String,
    val index: Int,
    val link: AppliedEffect,
    val pkg: PackageInfo,
    val knob: app.concat.editor.api.ParamInfo,
    val editor: EditorViewModel,
)

@Composable
private fun LinkStrength(data: LinkStrengthData) {
    val (clip, field, index, link, pkg, knob, editor) = data
    var value by remember(clip.id, field, index, link.id) { mutableFloatStateOf((link.params[knob.key] ?: knob.default).toFloat()) }
    val low = knob.min.toFloat()
    val high = knob.max.toFloat().coerceAtLeast(low + 1e-3f)
    Column(Modifier.padding(horizontal = Spacing.lg)) {
        ValueSlider(
            // The effect's name says what its strength is; another knob is named after it.
            if (knob.key == pkg.intensity) pkg.name else "${pkg.name} ${knob.label.lowercase()}",
            value,
            low..high,
            options = ValueSliderOptions({ if (knob.type == "int" || knob.step >= 1) "%.0f%s".format(it, knob.unit) else "%.2f%s".format(it, knob.unit) }, {
                value = it
                editor.setLink(clip.id, field, index, link.withParam(knob.key, it.toDouble()))
            }),
        )
    }
}

private fun tileColour(pkg: PackageInfo): Brush {
    val hue = (pkg.id.hashCode() and 0xff) * 360f / 256f
    return Brush.linearGradient(listOf(Color.hsv(hue, 0.5f, 0.7f), Color.hsv((hue + 40f) % 360f, 0.65f, 0.3f)))
}

private data class PackageTileOptions(val tint: Brush?, val selected: Boolean, val enabled: Boolean)

@Composable
private fun PackageTileSmall(name: String, options: PackageTileOptions, onClick: () -> Unit) {
    Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = RoundedCornerShape(Radii.md)
        Box(
            Modifier.size(64.dp).clip(shape)
                .then(if (options.tint != null) Modifier.background(options.tint) else Modifier.background(ConcatColors.raised))
                .border(if (options.selected) 2.dp else 0.dp, if (options.selected) ConcatColors.accent else Color.Transparent, shape)
                .clickable(enabled = options.enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (options.tint == null) {
                ConcatIcon(ConcatIcons.EyeOff, appearance = IconAppearance(tint = ConcatColors.fgMuted, size = 22.dp))
            } else if (options.selected) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(ConcatColors.accent), contentAlignment = Alignment.Center) {
                    ConcatIcon(ConcatIcons.Check, appearance = IconAppearance(tint = ConcatColors.onAccent, size = 14.dp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            name,
            style = ConcatType.caption,
            color = if (options.selected) ConcatColors.accent else if (options.enabled) ConcatColors.fg else ConcatColors.fgDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

