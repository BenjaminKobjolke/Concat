// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.ui.editor

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.concat.editor.api.AppliedEffect
import app.concat.editor.api.Clip
import app.concat.editor.api.ClipKind
import app.concat.editor.api.PackageInfo
import app.concat.editor.api.ParamInfo
import app.concat.editor.concat
import app.concat.editor.ui.Palette
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Which packages a sheet shows, and what it is called. */
enum class Shelf(val title: String) { Effects("Effects"), Filters("Filters"), Transitions("Transitions") }

data class CatalogueSheetRequest(val shelf: Shelf, val adjustFirst: Boolean)

/** The package kinds a shelf offers for [clip]. */
fun Shelf.kinds(clip: Clip?): List<String> = when (this) {
    Shelf.Effects -> when (clip?.kind) {
        ClipKind.Audio -> listOf("audio")
        ClipKind.Video -> listOf("effect", "audio")
        else -> listOf("effect")
    }
    Shelf.Filters -> listOf("filter")
    Shelf.Transitions -> listOf("transition")
}

/** The selected clip's links of [kinds], newest first, each with its knobs. */
@Composable
internal fun AdjustChain(clip: Clip?, kinds: List<String>, catalogue: CatalogueViewModel, editor: EditorViewModel) {
    if (clip == null) {
        SectionLabel("Select a clip to adjust what is on it.")
        return
    }
    // (field, index in that chain, link): indices are the chain's own, as updateClip replaces it whole.
    val links = buildList {
        if ("audio" in kinds) clip.filters.forEachIndexed { i, link -> add(Triple("filters", i, link)) }
        clip.videoEffects.forEachIndexed { i, link ->
            val kind = catalogue.find(link.id)?.kind
            val fits = when {
                clip.kind == ClipKind.Transition -> "transition" in kinds
                kind == null -> "effect" in kinds
                else -> kind in kinds
            }
            if (fits) add(Triple("videoEffects", i, link))
        }
    }.reversed()
    if (links.isEmpty()) {
        SectionLabel("Nothing applied yet: pick one under Browse.")
        return
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(links, key = { "${clip.id}/${it.first}/${it.second}/${it.third.id}" }) { (field, index, link) ->
            LinkCard(
                CatalogueLinkCard(clip, field, index, link, catalogue.find(link.id), removable = clip.kind != ClipKind.Transition),
                editor,
            )
        }
    }
}

@Composable
private fun LinkCard(content: CatalogueLinkCard, editor: EditorViewModel) {
    val clip = content.clip
    val field = content.field
    val index = content.index
    val link = content.link
    val pkg = content.pkg
    val removable = content.removable
    // The sheet's own copy of the knobs, so a drag is not pulled back by replies still on their way.
    val values = remember(clip.id, field, index, link.id) {
        mutableStateMapOf<String, Double>().apply { putAll(link.params) }
    }
    var enabled by remember(clip.id, field, index, link.id) { mutableStateOf(link.enabled) }
    fun send() {
        val out = JSONObject(link.raw.toString())
        val params = JSONObject()
        values.forEach { (key, value) -> params.put(key, value) }
        out.put("params", params).put("enabled", enabled)
        editor.setLink(clip.id, field, index, out)
    }
    // A transition's package and knobs are the clip itself: it is changed by
    // picking another, not adjusted here.
    val adjustable = clip.kind != ClipKind.Transition
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.raised).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(pkg?.name ?: link.id, color = Palette.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (adjustable) {
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    send()
                })
            }
            if (removable) TextButton(onClick = { editor.removeLink(clip.id, field, index) }) { Text("Remove") }
        }
        val params = pkg?.params?.filter { it.sliderable && adjustable }.orEmpty()
        if (params.isEmpty()) SectionLabel(pkg?.description?.ifBlank { null } ?: "Nothing to adjust.")
        for (param in params) {
            ParamControl(param, values[param.key] ?: param.default) {
                values[param.key] = it
                send()
            }
        }
    }
}

private data class CatalogueLinkCard(
    val clip: Clip,
    val field: String,
    val index: Int,
    val link: AppliedEffect,
    val pkg: PackageInfo?,
    val removable: Boolean,
)

/** One knob: a slider for a number, a switch for a flag, chips for a choice. */
@Composable
private fun ParamControl(param: ParamInfo, value: Double, onChange: (Double) -> Unit) {
    when (param.type) {
        "bool" -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(param.label, color = Palette.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = value != 0.0, onCheckedChange = { onChange(if (it) 1.0 else 0.0) })
        }
        "enum" -> Column {
            SectionLabel(param.label)
            val options = param.values.indices.toList()
            val current = param.values.indexOfFirst { it == value }.coerceAtLeast(0)
            ChoiceChips(options, current, { param.labels.getOrNull(it) ?: param.values[it].toString() }) { onChange(param.values[it]) }
        }
        else -> Column {
            val low = param.min.toFloat()
            val high = param.max.toFloat().coerceAtLeast(low + 1e-3f)
            val step = if (param.type == "int") param.step.coerceAtLeast(1.0) else param.step
            val steps = if (step > 0) (((high - low) / step).roundToInt() - 1).coerceIn(0, 200) else 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(param.label, color = Palette.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                val shown = if (param.type == "int" || step >= 1) "%.0f".format(value) else "%.2f".format(value)
                Text("$shown${param.unit}", color = Palette.accent, fontSize = 12.sp)
            }
            Slider(
                value = value.toFloat().coerceIn(low, high),
                onValueChange = { raw ->
                    var next = raw.toDouble()
                    if (step > 0) next = param.min + ((next - param.min) / step).roundToInt() * step
                    onChange(next.coerceIn(param.min, param.max))
                },
                valueRange = low..high,
                steps = steps,
            )
        }
    }
}
