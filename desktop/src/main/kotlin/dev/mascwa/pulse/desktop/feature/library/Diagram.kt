package dev.mascwa.pulse.desktop.feature.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.desktop.library.LibraryRepository
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.Pulse
import java.io.ByteArrayInputStream

/**
 * A bundled guide diagram.
 *
 * ⚠️ **Every path here fails soft.** The corpus carries png, jpg, svg and one gif; Compose Desktop
 * documents loaders for the first three and says nothing about the fourth, and a decoder that throws
 * inside a composable takes the whole reader down with it. A diagram that cannot be drawn is worth a
 * line of text saying so; it is not worth a crash in the middle of a first-aid page.
 *
 * Drawn on a light card because the figures are scanned engravings and line art on white — inverted
 * onto the LCARS black they are close to unreadable.
 */
@Composable
fun Diagram(
    repository: LibraryRepository,
    name: String,
    modifier: Modifier = Modifier,
) {
    val c = Pulse.colors
    val density = LocalDensity.current
    var painter by remember(name) { mutableStateOf<Painter?>(null) }
    var failed by remember(name) { mutableStateOf(false) }

    LaunchedEffect(name) {
        val bytes = runCatching { repository.imageBytes(name) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            failed = true
            return@LaunchedEffect
        }
        painter = runCatching {
            if (name.endsWith(".svg", ignoreCase = true)) {
                loadSvgPainter(ByteArrayInputStream(bytes), density)
            } else {
                // Skia decodes png/jpg here, and a gif's first frame with it. If it cannot, the catch
                // below turns that into a caption rather than an exception.
                BitmapPainter(loadImageBitmap(ByteArrayInputStream(bytes)))
            }
        }.getOrNull()
        if (painter == null) failed = true
    }

    val p = painter
    Column(modifier.fillMaxWidth()) {
        when {
            p != null -> Image(
                painter = p,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .widthIn(max = DIAGRAM_MAX_WIDTH)
                    .heightIn(max = DIAGRAM_MAX_HEIGHT)
                    .background(DIAGRAM_CARD)
                    .padding(10.dp),
            )
            failed -> Text(
                "[ diagram \"$name\" could not be displayed ]",
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
            )
            else -> Spacer(Modifier.height(1.dp))
        }
    }
}

/** Wide enough for a labelled anatomical plate, short enough that prose stays on the page around it. */
private val DIAGRAM_MAX_WIDTH = 620.dp
private val DIAGRAM_MAX_HEIGHT = 520.dp

/** Line art scanned off white paper needs white behind it, whatever the rest of the console does. */
private val DIAGRAM_CARD = Color(0xFFF3F1EC)
