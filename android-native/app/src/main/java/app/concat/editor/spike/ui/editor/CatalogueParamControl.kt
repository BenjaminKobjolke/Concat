// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import app.concat.editor.spike.api.ParamInfo
import app.concat.editor.spike.ui.Palette
import kotlin.math.roundToInt

/** One knob: a slider for a number, a switch for a flag, chips for a choice. */
@Composable
internal fun ParamControl(param: ParamInfo, value: Double, onChange: (Double) -> Unit) {
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
