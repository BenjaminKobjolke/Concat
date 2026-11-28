// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah

package app.concat.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.concat.editor.api.Clip
import app.concat.editor.api.PackageInfo
import app.concat.editor.concat
import app.concat.editor.ui.Palette
import app.concat.editor.ui.icons.ConcatIcons

/**
 * A shelf of the catalogue: Browse, a searchable grid grouped by
 * category, where a tap applies the package to the selected clip; and
 * Adjust, the selected clip's chain links of this shelf's kinds, each
 * with sliders from its parameters that change the picture as they move.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogueSheet(request: CatalogueSheetRequest, catalogue: CatalogueViewModel, editor: EditorViewModel, onDismiss: () -> Unit) {
    val shelf = request.shelf
    val catalogueState by catalogue.ui.collectAsStateWithLifecycle()
    val editorUi by editor.ui.collectAsStateWithLifecycle()
    val clip = editorUi.view?.clip(editorUi.selected)
    val kinds = shelf.kinds(clip)
    var tab by remember { mutableStateOf(if (request.adjustFirst) 1 else 0) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { catalogue.load() }

    val offered = catalogueState.packages.filter { it.kind in kinds }
    val shown = offered.filter { pkg ->
        (category == null || pkg.category == category) &&
            (query.isBlank() || listOf(pkg.name, pkg.category, pkg.description).any { it.contains(query.trim(), ignoreCase = true) })
    }
    val categories = offered.map { it.category }.distinct().sorted()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetTitle(shelf.title)
            ShelfTabs(tab, clip != null) { tab = it }
            if (tab == 0) {
                CatalogueBrowse(
                    CatalogueBrowseState(shelf, shown, categories, query, category, catalogueState.loaded, catalogueState.error, clip),
                    CatalogueBrowseActions(
                        onQueryChange = { query = it },
                        onCategoryChange = { category = it },
                        onApply = { pkg ->
                            editor.applyPackage(pkg.kind, pkg.id, pkg.name)
                            if (pkg.params.any { it.sliderable }) tab = 1
                        },
                    ),
                )
            } else {
                AdjustChain(clip, kinds, catalogue, editor)
            }
            Spacer(Modifier.navigationBarsPadding().padding(bottom = 8.dp))
        }
    }
}

private data class CatalogueBrowseState(
    val shelf: Shelf,
    val shown: List<PackageInfo>,
    val categories: List<String>,
    val query: String,
    val category: String?,
    val loaded: Boolean,
    val error: String?,
    val clip: Clip?,
)

private data class CatalogueBrowseActions(
    val onQueryChange: (String) -> Unit,
    val onCategoryChange: (String?) -> Unit,
    val onApply: (PackageInfo) -> Unit,
)

@Composable
private fun ShelfTabs(tab: Int, hasClip: Boolean, onSelect: (Int) -> Unit) {
    TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
        Tab(selected = tab == 0, onClick = { onSelect(0) }, text = { Text("Browse") })
        Tab(selected = tab == 1, onClick = { onSelect(1) }, text = { Text("Adjust") }, enabled = hasClip)
    }
}

@Composable
private fun CatalogueBrowse(state: CatalogueBrowseState, actions: CatalogueBrowseActions) {
    OutlinedTextField(
        value = state.query,
        onValueChange = actions.onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(ConcatIcons.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
        placeholder = { Text("Search ${state.shelf.title.lowercase()}") },
    )
    ChoiceChips(listOf<String?>(null) + state.categories, state.category, { it ?: "All" }) {
        actions.onCategoryChange(it)
    }
    when {
        !state.loaded -> SectionLabel("Loading...")
        state.error != null -> SectionLabel(state.error ?: "")
        state.shown.isEmpty() -> SectionLabel("Nothing matches.")
    }
    if (state.shelf != Shelf.Transitions && state.clip == null) SectionLabel("Select a clip to apply one.")
    LazyVerticalGrid(
        columns = GridCells.Adaptive(92.dp),
        modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((group, packages) in state.shown.groupBy { it.category }.toSortedMap()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header-$group") {
                Text(group, color = Palette.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            }
            items(packages, key = { it.id }) { pkg ->
                PackageTile(pkg, enabled = state.clip != null || state.shelf == Shelf.Transitions) { actions.onApply(pkg) }
            }
        }
    }
}

@Composable
private fun PackageTile(pkg: PackageInfo, enabled: Boolean, onClick: () -> Unit) {
    val hue = (pkg.id.hashCode() and 0xff) * 360f / 256f
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                .background(Color.hsv(hue, 0.45f, if (enabled) 0.55f else 0.3f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(pkg.name.take(2), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text(pkg.name, color = Palette.text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
