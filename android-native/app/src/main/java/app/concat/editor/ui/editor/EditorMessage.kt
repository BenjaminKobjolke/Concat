// SPDX-License-Identifier: AGPL-3.0-or-later

package app.concat.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.concat.editor.ui.theme.ConcatColors
import app.concat.editor.ui.theme.ConcatType
import app.concat.editor.ui.theme.Dimens
import app.concat.editor.ui.theme.Radii
import app.concat.editor.ui.theme.Spacing
import kotlinx.coroutines.delay

@Composable
internal fun BoxScope.EditorMessage(message: String?, previewHeight: Dp, onDismiss: () -> Unit) {
    message?.let {
        LaunchedEffect(it) {
            delay(3500)
            onDismiss()
        }
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = Dimens.topBar + previewHeight - 52.dp).padding(horizontal = Spacing.xl)
                .clip(RoundedCornerShape(Radii.lg)).background(ConcatColors.raised).clickable(onClick = onDismiss)
                .padding(horizontal = Spacing.lg, vertical = 10.dp),
        ) { Text(it, style = ConcatType.label, color = ConcatColors.fg, textAlign = TextAlign.Center) }
    }
}
