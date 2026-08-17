package dev.mascwa.pulse.feature.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.common.LcarsCorner
import dev.mascwa.pulse.feature.common.LcarsHeaderBar
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.lcarsBlockShape
import dev.mascwa.pulse.navigation.GROUPS
import dev.mascwa.pulse.navigation.MenuEntry
import dev.mascwa.pulse.navigation.Routes
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwirePalette
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * THE flat directory, rendered — the fix for "hidden as sub tabs, hard to find". Every feature is a
 * big, plain-English, always-in-the-same-place block, grouped, ONE tap from here.
 *
 * The data itself lives in `navigation/Directory.kt`, because the console header reads it too.
 */
@Composable
fun MenuScreen(onOpen: (String) -> Unit) {
    val c = Pulse.colors
    PulseScaffold(title = "MENU") { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GROUPS.forEach { group ->
                item(key = "hdr_${group.label}") {
                    LcarsHeaderBar(group.label, Modifier.padding(top = 12.dp, bottom = 2.dp))
                }
                items(group.entries, key = { it.route }) { entry ->
                    MenuRow(entry, group.accent(c), onOpen)
                }
            }
            item(key = "footer_pad") { Box(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun MenuRow(entry: MenuEntry, accent: Color, onOpen: (String) -> Unit) {
    val c = Pulse.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(lcarsBlockShape(14.dp, LcarsCorner.TopStart))
            .background(c.raise)
            .clickable { onOpen(entry.route) }
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(12.dp)
                .height(52.dp)
                .background(accent, lcarsBlockShape(10.dp, LcarsCorner.TopStart)),
        )
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(
                entry.label.uppercase(),
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 1.2.sp,
                color = c.ink,
            )
            Text(
                entry.description,
                fontFamily = JetBrainsMono,
                fontSize = 10.sp,
                color = c.muted,
            )
        }
    }
}
