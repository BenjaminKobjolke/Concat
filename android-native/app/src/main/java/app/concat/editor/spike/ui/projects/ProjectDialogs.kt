// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.spike.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.concat.editor.spike.ui.components.ChoiceChip
import app.concat.editor.spike.ui.theme.ConcatColors
import app.concat.editor.spike.ui.theme.ConcatType
import app.concat.editor.spike.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String, Aspect) -> Unit) {
    var name by remember { mutableStateOf("Project " + SimpleDateFormat("MMM d HH.mm", Locale.getDefault()).format(Date())) }
    var aspect by remember { mutableStateOf(Aspect.Portrait) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ConcatColors.launchRaised,
        title = { Text("New project", style = ConcatType.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    for (option in Aspect.entries) ChoiceChip(option.label, selected = aspect == option, onClick = { aspect = option })
                }
                Text("30 fps", style = ConcatType.caption, color = ConcatColors.fgDim)
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name.ifBlank { "Untitled" }, aspect) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun NameDialog(title: String, current: String, action: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ConcatColors.launchRaised,
        title = { Text(title, style = ConcatType.title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onDone(name) }, enabled = name.isNotBlank()) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
