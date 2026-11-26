package app.concat.editor.ui.projects

import android.app.Application
import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.concat.editor.api.ProjectInfo
import app.concat.editor.concat
import app.concat.editor.ui.components.ChoiceChip
import app.concat.editor.ui.components.ConcatIcon
import app.concat.editor.ui.components.IconAction
import app.concat.editor.ui.icons.ConcatIcons
import app.concat.editor.ui.secondsLabel
import app.concat.editor.ui.theme.ConcatColors
import app.concat.editor.ui.theme.ConcatType
import app.concat.editor.ui.theme.Radii
import app.concat.editor.ui.theme.Spacing
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import app.concat.editor.ui.components.IconAppearance
import app.concat.editor.ui.components.IconActionOptions

internal data class ProjectTileActions(
    val onOpen: (ProjectInfo) -> Unit,
    val onRename: (ProjectInfo) -> Unit,
    val onDuplicate: (ProjectInfo) -> Unit,
    val onDelete: (ProjectInfo) -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProjectTile(card: ProjectCard, actions: ProjectTileActions) {
    val menu = remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Column(
        Modifier.clip(RoundedCornerShape(Radii.lg)).combinedClickable(
            onClick = { actions.onOpen(card.info) },
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                menu.value = true
            },
            onLongClickLabel = "More",
        ),
    ) {
        ProjectTileArtwork(card, menu, actions)
        Spacer(Modifier.height(6.dp))
        ProjectTileDetails(card)
    }
}

@Composable
private fun ProjectTileArtwork(
    card: ProjectCard,
    menu: MutableState<Boolean>,
    actions: ProjectTileActions,
) {
    Box(Modifier.fillMaxWidth().aspectRatio(0.92f).clip(RoundedCornerShape(Radii.lg)).background(ConcatColors.launchPanel)) {
        if (card.thumbnail != null) {
            Image(card.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val hue = (card.info.name.hashCode() and 0xff) * 360f / 256f
            Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.hsv(hue, 0.35f, 0.32f), Color.hsv((hue + 40) % 360f, 0.4f, 0.14f)))),
                contentAlignment = Alignment.Center,
            ) { ConcatIcon(ConcatIcons.Film, appearance = IconAppearance(tint = Color.White.copy(alpha = 0.5f), size = 28.dp)) }
        }
        ProjectTileBadges(card)
        Box(Modifier.align(Alignment.TopEnd)) {
            IconAction(ConcatIcons.More, "More", { menu.value = true }, options = IconActionOptions(tint = Color.White, size = 18.dp, box = 36.dp))
            ProjectTileMenu(card, menu, actions)
        }
    }
}

@Composable
private fun BoxScope.ProjectTileBadges(card: ProjectCard) {
    card.duration?.let {
        Text(
            secondsLabel(it),
            style = ConcatType.badge.copy(fontFeatureSettings = "tnum"),
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.sm)
                .clip(RoundedCornerShape(Radii.xs)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
    Text(
        "${card.info.width}x${card.info.height}",
        style = ConcatType.badge,
        color = Color.White.copy(alpha = 0.85f),
        modifier = Modifier.align(Alignment.TopStart).padding(Spacing.sm)
            .clip(RoundedCornerShape(Radii.xs)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Composable
private fun ProjectTileMenu(card: ProjectCard, menu: MutableState<Boolean>, actions: ProjectTileActions) {
    DropdownMenu(expanded = menu.value, onDismissRequest = { menu.value = false }, containerColor = ConcatColors.launchRaised) {
        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { ConcatIcon(ConcatIcons.TextMark, appearance = IconAppearance(size = 18.dp)) }, onClick = {
            menu.value = false
            actions.onRename(card.info)
        })
        DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { ConcatIcon(ConcatIcons.Copy, appearance = IconAppearance(size = 18.dp)) }, onClick = {
            menu.value = false
            actions.onDuplicate(card.info)
        })
        DropdownMenuItem(text = { Text("Delete", color = ConcatColors.danger) }, leadingIcon = { ConcatIcon(ConcatIcons.Trash, appearance = IconAppearance(tint = ConcatColors.danger, size = 18.dp)) }, onClick = {
            menu.value = false
            actions.onDelete(card.info)
        })
    }
}

@Composable
private fun ProjectTileDetails(card: ProjectCard) {
    Text(card.info.name, style = ConcatType.label.copy(fontSize = ConcatType.body.fontSize), color = ConcatColors.fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 2.dp))
    Text(openedLabel(card.info.openedAt), style = ConcatType.caption, color = ConcatColors.fgDim, modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp))
}

/** When it was last opened: recents give milliseconds (the in-memory engine) or seconds (the engine). */
private fun openedLabel(openedAt: Long): String {
    if (openedAt <= 0) return ""
    val ms = if (openedAt < 100_000_000_000L) openedAt * 1000 else openedAt
    return DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}
