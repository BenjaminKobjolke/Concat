// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.concat.editor.concat
import app.concat.editor.storage.ProjectFolders
import app.concat.editor.ui.projects.rememberProjectFolderPicker
import app.concat.editor.ui.theme.ConcatColors
import app.concat.editor.ui.theme.ConcatType
import java.io.File

@Composable
fun ProjectsFolderRow() {
    val context = LocalContext.current
    val app = context.concat
    var folder by remember { mutableStateOf(app.projectsDir) }
    var error by remember { mutableStateOf<String?>(null) }
    val pickFolder = rememberProjectFolderPicker(
        onPicked = { selected ->
            ProjectFolders.saveSelectedProjectDirectory(context, selected)
            folder = selected
        },
        onError = { error = it },
    )

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ConcatColors.launchPanel).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Projects folder", style = ConcatType.label, color = ConcatColors.fg)
        Text(folder.absolutePath, style = ConcatType.caption, color = ConcatColors.fgMuted, maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = pickFolder) { Text("Choose") }
            if (folder.absoluteFile != app.appProjectsDir.absoluteFile) {
                TextButton(onClick = {
                    ProjectFolders.clearSelectedProjectDirectory(context)
                    folder = app.appProjectsDir
                }) { Text("Use the app folder") }
            }
        }
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
            text = { Text(message) },
        )
    }
}
