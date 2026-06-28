package dev.mascwa.pulse.feature.dial

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/** One node on the dial ring — an assigned app's icon, or a "+" when empty. Tap launches / long-press assigns. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DialNode(
    packageName: String,
    size: Dp,
    modifier: Modifier,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val c = Pulse.colors
    val icon = if (packageName.isNotEmpty()) rememberAppIcon(packageName) else null
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(c.panel)
            .border(1.dp, c.sky.copy(alpha = 0.45f), CircleShape)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(size * 0.52f))
        } else {
            Text("+", fontFamily = JetBrainsMono, fontSize = (size.value * 0.4f).sp, color = c.sky)
        }
    }
}

/** Decodes an app's launcher icon to an [ImageBitmap], cached per package; null if it can't be loaded. */
@Composable
internal fun rememberAppIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
    }
}

/** Picks an app to pin to a slot (or clears it). A simple scrollable list of the device's launchable apps. */
@Composable
internal fun AppPickerDialog(
    apps: List<ReactorDialViewModel.AppEntry>,
    hasCurrent: Boolean,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Pulse.colors
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, apps) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = c.panel, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(
                    "ASSIGN APP",
                    fontFamily = JetBrainsMono, fontSize = 12.sp, letterSpacing = 1.5.sp, color = c.sky,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                // Search — long app lists are painful to scroll.
                Box(
                    Modifier.fillMaxWidth()
                        .border(1.dp, c.sky.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = c.ink, fontFamily = JetBrainsMono, fontSize = 13.sp),
                        cursorBrush = SolidColor(c.sky),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (query.isEmpty()) {
                        Text("Search apps…", fontFamily = JetBrainsMono, fontSize = 13.sp, color = c.muted)
                    }
                }
                if (hasCurrent) {
                    Text(
                        "✕  Clear this slot",
                        fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.magenta,
                        modifier = Modifier.fillMaxWidth().clickable { onClear() }.padding(vertical = 10.dp),
                    )
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(filtered, key = { it.packageName }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(app.packageName) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val icon = rememberAppIcon(app.packageName)
                            if (icon != null) {
                                Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(34.dp))
                            } else {
                                Box(Modifier.size(34.dp))
                            }
                            Text(
                                app.label,
                                fontFamily = JetBrainsMono, fontSize = 13.sp, color = c.ink,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
