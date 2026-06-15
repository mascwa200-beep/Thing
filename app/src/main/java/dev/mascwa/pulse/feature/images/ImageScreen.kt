package dev.mascwa.pulse.feature.images

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.mascwa.pulse.core.util.openUrl
import dev.mascwa.pulse.data.images.ImageResult
import dev.mascwa.pulse.feature.common.EmptyState
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.NeonChip
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun ImageScreen(vm: ImageViewModel, onBack: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = Pulse.colors
    var showAdd by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<ImageResult?>(null) }

    Box(Modifier.fillMaxSize()) {
        PulseScaffold(
            title = "Images",
            navigationIcon = {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            },
        ) { innerPadding ->
            Column(Modifier.padding(innerPadding)) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { vm.setQuery(it) },
                    label = { Text("Search images") },
                    singleLine = true,
                    trailingIcon = { IconButton(onClick = { vm.search() }) { Icon(Icons.Filled.Search, "Search") } },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { vm.search() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.chips.forEachIndexed { i, label ->
                        NeonChip(label, selected = i == state.selected, onClick = { vm.selectSource(i) })
                    }
                    IconButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, "Add site", tint = c.accent) }
                }

                val res = state.results
                when {
                    res.isInitialLoading -> LoadingState()
                    res.isError -> ErrorState(res.error ?: "Error", onRetry = { vm.search() })
                    res.data == null -> EmptyState(
                        if (state.selected == 0) "Search the web for images, or add a site (＋)."
                        else "Type a keyword to search this site for images.",
                    )
                    res.data!!.isEmpty() -> EmptyState("No images found.")
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(res.data!!, key = { it.thumbUrl }) { img ->
                            AsyncImage(
                                model = img.thumbUrl,
                                contentDescription = img.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(c.panel)
                                    .clickable { viewer = img },
                            )
                        }
                    }
                }
            }
        }

        // In-app full-screen zoomable viewer.
        viewer?.let { img ->
            BackHandler { viewer = null }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                var scale by remember(img) { mutableStateOf(1f) }
                var offset by remember(img) { mutableStateOf(Offset.Zero) }
                AsyncImage(
                    model = img.fullUrl,
                    contentDescription = img.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(img) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                offset = if (scale > 1f) offset + pan else Offset.Zero
                            }
                        }
                        .graphicsLayer {
                            scaleX = scale; scaleY = scale
                            translationX = offset.x; translationY = offset.y
                        },
                )
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.systemBars)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { viewer = null }) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
                    TextButton(onClick = { openUrl(context, img.sourceUrl) }) {
                        Text("Open source", color = c.accent)
                    }
                }
            }
        }
    }

    if (showAdd) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add image site") },
            text = {
                Column {
                    OutlinedTextField(url, { url = it }, label = { Text("Site URL") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Text(
                        "Add a site, then it becomes a tab — type a keyword and it searches that site " +
                            "for images. For exact control, put %s where the keyword goes, e.g. " +
                            "https://example.com/search?q=%s",
                        style = MaterialTheme.typography.labelSmall, color = Pulse.colors.muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = url.startsWith("http"), onClick = { vm.addSite(url); showAdd = false }) {
                    Text("Add")
                }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }
}
