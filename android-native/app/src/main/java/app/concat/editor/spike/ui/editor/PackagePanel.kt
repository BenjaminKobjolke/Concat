// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

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
import app.concat.editor.spike.api.AppliedEffect
import app.concat.editor.spike.api.Clip
import app.concat.editor.spike.api.ClipKind
import app.concat.editor.spike.api.PackageInfo
import app.concat.editor.spike.api.ProjectView
import app.concat.editor.spike.ui.components.ChoiceChip
import app.concat.editor.spike.ui.components.ConcatIcon
import app.concat.editor.spike.ui.components.GhostButton
import app.concat.editor.spike.ui.components.PanelLabel
import app.concat.editor.spike.ui.components.ToolPanel
import app.concat.editor.spike.ui.components.ToolPanelLayout
import app.concat.editor.spike.ui.components.ValueSlider
import app.concat.editor.spike.ui.icons.ConcatIcons
import app.concat.editor.spike.ui.theme.ConcatColors
import app.concat.editor.spike.ui.theme.ConcatType
import app.concat.editor.spike.ui.theme.Radii
import app.concat.editor.spike.ui.theme.Spacing
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import org.json.JSONObject

/**
 * Effects, filters or transitions as a row of tiles over the clip, CapCut's
 * way: "None" first, a tap applies, and the newest of this shelf's links
 * gets its strength slider beneath. "All" opens the full catalogue sheet
 * with search, categories and every knob.
 */
@Composable
fun PackagePanel(
    shelf: Shelf,
    catalogue: CatalogueViewModel,
    editor: EditorViewModel,
    onMore: () -> Unit,
    onClose: () -> Unit,
) {
    val state by catalogue.ui.collectAsStateWithLifecycle()
    val editorUi by editor.ui.collectAsStateWithLifecycle()
    val clip = editorUi.view?.clip(editorUi.selected)
    val kinds = shelf.kinds(clip)
    LaunchedEffect(Unit) { catalogue.load() }
    val offered = state.packages.filter { it.kind in kinds }
    // The chain's links this shelf owns, as (field, index, link).
    val links = clip?.let { c ->
        buildList {
            if ("audio" in kinds) c.filters.forEachIndexed { i, link -> add(Triple("filters", i, link)) }
            c.videoEffects.forEachIndexed { i, link ->
                val kind = catalogue.find(link.id)?.kind
                if (c.kind == ClipKind.Transition || kind in kinds || (kind == null && "effect" in kinds)) add(Triple("videoEffects", i, link))
            }
        }
    }.orEmpty()
    val applied = links.map { it.third.id }.toSet()

    ToolPanel(
        shelf.title,
        onClose = onClose,
        onConfirm = onClose,
        actions = { GhostButton("All", onMore, icon = ConcatIcons.Search, height = 32.dp, modifier = Modifier.padding(end = Spacing.sm)) },
        layout = ToolPanelLayout(padding = androidx.compose.foundation.layout.PaddingValues(vertical = Spacing.xs)),
    ) {
        when {
            !state.loaded -> PanelLabel("Loading...", Modifier.padding(horizontal = Spacing.lg))
            state.error != null -> PanelLabel(state.error ?: "", Modifier.padding(horizontal = Spacing.lg))
            clip == null && shelf != Shelf.Transitions -> PanelLabel("Select a clip to apply one.", Modifier.padding(horizontal = Spacing.lg))
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.lg),
        ) {
            if (shelf != Shelf.Transitions) {
                item(key = "none") {
                    PackageTileSmall(
                        name = "None",
                        tint = null,
                        selected = links.isEmpty(),
                        enabled = clip != null && links.isNotEmpty(),
                    ) {
                        clip?.let { c -> editor.removeLinks(c.id, links.map { it.first to it.second }) }
                    }
                }
            }
            items(offered, key = { it.id }) { pkg ->
                PackageTileSmall(
                    name = pkg.name,
                    tint = tileColour(pkg),
                    selected = pkg.id in applied,
                    enabled = clip != null || shelf == Shelf.Transitions,
                ) { editor.applyPackage(pkg.kind, pkg.id, pkg.name) }
            }
        }
        // The newest link's strength, live as the finger moves.
        val newest = links.maxByOrNull { if (it.first == "videoEffects") 1000 + it.second else it.second }
        val pkg = newest?.let { catalogue.find(it.third.id) }
        val knob = pkg?.params?.firstOrNull { it.key == pkg.intensity } ?: pkg?.params?.firstOrNull { it.type == "float" || it.type == "int" }
        if (clip != null && newest != null && pkg != null && knob != null) {
            LinkStrength(clip, newest.first, newest.second, newest.third, pkg, knob, editor)
        }
    }
}

@Composable
private fun LinkStrength(
    clip: Clip,
    field: String,
    index: Int,
    link: AppliedEffect,
    pkg: PackageInfo,
    knob: app.concat.editor.spike.api.ParamInfo,
    editor: EditorViewModel,
) {
    var value by remember(clip.id, field, index, link.id) { mutableFloatStateOf((link.params[knob.key] ?: knob.default).toFloat()) }
    val low = knob.min.toFloat()
    val high = knob.max.toFloat().coerceAtLeast(low + 1e-3f)
    Column(Modifier.padding(horizontal = Spacing.lg)) {
        ValueSlider(
            "${pkg.name} · ${knob.label}",
            value,
            low..high,
            { if (knob.type == "int" || knob.step >= 1) "%.0f%s".format(it, knob.unit) else "%.2f%s".format(it, knob.unit) },
            onChange = {
                value = it
                editor.setLink(clip.id, field, index, link.withParam(knob.key, it.toDouble()))
            },
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
                ConcatIcon(ConcatIcons.EyeOff, tint = ConcatColors.fgMuted, size = 22.dp)
            } else if (selected) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(ConcatColors.accent), contentAlignment = Alignment.Center) {
                    ConcatIcon(ConcatIcons.Check, tint = ConcatColors.onAccent, size = 14.dp)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            name,
            style = ConcatType.caption,
            color = if (selected) ConcatColors.accent else if (enabled) ConcatColors.fg else ConcatColors.fgDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

