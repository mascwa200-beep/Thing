package dev.mascwa.pulse.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.core.util.openUrl
import dev.mascwa.pulse.data.markets.Quote
import dev.mascwa.pulse.data.news.Article
import dev.mascwa.pulse.data.settings.HomeSection
import dev.mascwa.pulse.data.weather.WeatherCode
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.SectionBar
import dev.mascwa.pulse.feature.common.StaleBanner
import dev.mascwa.pulse.feature.common.Ticker
import dev.mascwa.pulse.feature.common.TickerItem
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.news.ArticleRowCompact
import dev.mascwa.pulse.ui.effects.DecryptText
import dev.mascwa.pulse.ui.effects.GlitchText
import dev.mascwa.pulse.ui.effects.LocalGlitchEnabled
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import dev.mascwa.pulse.ui.theme.trendColor
import java.util.Calendar

class HomeNav(
    val openNews: () -> Unit,
    val openMarkets: () -> Unit,
    val openWeather: () -> Unit,
    val openEconomy: () -> Unit,
    val openInflation: () -> Unit,
    val openFuel: () -> Unit,
    val openSettings: () -> Unit,
    val openAssistant: () -> Unit = {},
    val openRadar: () -> Unit = {},
)

private data class FeedChip(val label: String, val key: String)

@Composable
fun HomeScreen(vm: HomeViewModel, nav: HomeNav) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = Pulse.colors
    val glitch = LocalGlitchEnabled.current
    var chip by remember { mutableStateOf("all") }

    val chips = listOf(
        FeedChip("All", "all"),
        FeedChip("Tech", "tech"),
        FeedChip("Politics", "pol"),
        FeedChip("Culture", "cult"),
    )

    PulseScaffold(
        title = "Pulse",
        topBarOverride = {
            Column(Modifier.background(c.void).windowInsetsPadding(WindowInsets.statusBars)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        GlitchText(
                            text = "NIGHTWIRE",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                            ),
                            glitch = glitch, baseColor = c.ink, accent = c.accent, magenta = c.magenta,
                        )
                        Text(
                            "OS 2.7", fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.5.sp,
                            color = c.muted, modifier = Modifier.padding(start = 8.dp, bottom = 3.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconChip(Icons.Filled.Search, "Search") { nav.openNews() }
                        IconChip(Icons.Filled.Notifications, "Alerts") { nav.openNews() }
                        IconChip(Icons.Filled.Settings, "Settings") { nav.openSettings() }
                    }
                }
                // sysline
                Row(
                    Modifier.padding(start = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(c.positive))
                    val feeds = state.headlines.data?.size ?: 0
                    SysText("SECURE", c.accent)
                    SysText("·", c.faint)
                    SysText("$feeds FEEDS", c.muted)
                    SysText("·", c.faint)
                    SysText(
                        "SYNC ${if (state.headlines.lastUpdatedEpochMs > 0)
                            Formatters.relativeTime(state.headlines.lastUpdatedEpochMs) else "—"}",
                        c.muted,
                    )
                }
                // ticker from live markets — tap to open the full Markets screen
                Ticker(tickerItems(state.markets.data), onClick = nav.openMarkets)
            }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.headlines.loading && state.headlines.data != null,
            onRefresh = { vm.refresh() },
            modifier = Modifier.padding(innerPadding),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val anyStale = listOf(state.headlines, state.markets, state.weather, state.economy, state.fuel)
                    .any { it.stale }
                if (anyStale) item { StaleBanner(true) }

                // Greeting
                item { Greeting() }

                // J.A.R.V.I.S. quick card — assistant status + tap to open the console.
                item { AssistantCard(state.jarvisStatus, state.pendingCode, nav.openAssistant) }

                // Compact weather (temp + rain) above the sky digest.
                if (state.weather.data?.current != null) {
                    item { WeatherMiniWidget(state.weather, nav.openWeather) }
                }

                // Today in the sky
                if (state.skyLines.isNotEmpty()) {
                    item { SkyDigestCard(state.skyLines) }
                }

                // Live aircraft overhead (only when there are any)
                state.radar.data?.takeIf { it.aircraftCount() > 0 }?.let { rd ->
                    item { FlightCard(rd, nav.openRadar) }
                }

                // Breaking hero
                state.headlines.data?.firstOrNull()?.let { lead ->
                    item { BreakingHero(lead) { openUrl(context, lead.url) } }
                }

                // Category chips
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        chips.forEach { fc ->
                            dev.mascwa.pulse.feature.common.NeonChip(
                                text = fc.label, selected = chip == fc.key, onClick = { chip = fc.key },
                            )
                        }
                    }
                }

                // Feed (filtered)
                val feed = when (chip) {
                    "tech" -> state.tech.data
                    "pol" -> state.politics.data
                    "cult" -> state.popculture.data
                    else -> state.headlines.data
                }.orEmpty().distinctBy { it.url }.take(12)
                if (feed.isEmpty()) {
                    item {
                        Text(
                            "Loading feed…", fontFamily = JetBrainsMono, fontSize = 11.sp,
                            color = c.muted, modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    items(count = feed.size, key = { "feed_${chip}_${feed[it].url}" }) { i ->
                        ArticleRowCompact(feed[i], onClick = { openUrl(context, feed[i].url) })
                    }
                }

                // Section snapshots
                val sections = state.sections
                if (HomeSection.MARKETS in sections) {
                    item { SectionBar("Markets", trailing = "VIEW ▸", onTrailing = nav.openMarkets) }
                    item { MarketsSnapshot(state.markets, nav.openMarkets) }
                }
                // Weather already shown by the hero WeatherMiniWidget above — no duplicate section here.
                if (HomeSection.INFLATION in sections || HomeSection.ECONOMY in sections) {
                    item { SectionBar("Economy", trailing = "VIEW ▸", onTrailing = nav.openEconomy) }
                    item { EconomySnapshot(state, nav.openEconomy) }
                }
                if (HomeSection.FUEL in sections) {
                    item { SectionBar("Fuel & Energy", trailing = "VIEW ▸", onTrailing = nav.openFuel) }
                    item { FuelSnapshot(state, nav.openFuel) }
                }
            }
        }
    }
}

/** Build the home market tape: one entry per instrument carrying NAME · price · ±change%, so the stream
 *  reads as dense, live market data. Every quote with a price is shown (an instrument is never dropped
 *  just because the % is briefly unavailable), keeping the tape full; the Ticker loops it endlessly. */
private fun tickerItems(quotes: List<Quote>?): List<TickerItem> =
    quotes.orEmpty().mapNotNull { q ->
        val price = q.price ?: return@mapNotNull null
        val pct = q.changePercent
        val priceStr = Formatters.number(price, tickerPriceDigits(price))
        TickerItem(
            symbol = q.label.uppercase().take(14),
            value = if (pct != null) "$priceStr  ${Formatters.signedPercent(pct)}" else priceStr,
            color = when {
                pct == null -> dev.mascwa.pulse.ui.theme.Ink2
                pct >= 0 -> dev.mascwa.pulse.ui.theme.Positive
                else -> dev.mascwa.pulse.ui.theme.Negative
            },
        )
    }

/** Compact price precision for the tape: whole numbers for large values (indices, BTC), cents for most
 *  stocks/commodities, more decimals for sub-1 values (some FX / low-priced coins). */
private fun tickerPriceDigits(price: Double): Int = when {
    kotlin.math.abs(price) >= 1000 -> 0
    kotlin.math.abs(price) >= 1 -> 2
    else -> 4
}

@Composable
private fun SysText(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 0.5.sp, color = color)
}

@Composable
private fun IconChip(icon: androidx.compose.ui.graphics.vector.ImageVector, cd: String, onClick: () -> Unit) {
    val c = Pulse.colors
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.panel)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, cd, tint = c.ink2, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Greeting() {
    val c = Pulse.colors
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val part = when (hour) { in 5..11 -> "MORNING"; in 12..16 -> "AFTERNOON"; in 17..21 -> "EVENING"; else -> "NIGHT" }
    Column(Modifier.padding(top = 8.dp, bottom = 2.dp)) {
        Text("GOOD $part · OPERATOR", fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 2.sp, color = c.muted)
        Row {
            Text("Here's your ", fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 23.sp, color = c.ink)
            Text("world", fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 23.sp, color = c.accent)
            Text(".", fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 23.sp, color = c.ink)
        }
    }
}

@Composable
private fun AssistantCard(status: String, pendingCode: Int, onOpen: () -> Unit) {
    val c = Pulse.colors
    NeonPanel(
        Modifier.fillMaxWidth().padding(top = 12.dp).clickable { onOpen() },
        corners = true,
        borderColor = if (pendingCode > 0) c.amber.copy(alpha = 0.6f) else c.accent.copy(alpha = 0.5f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(c.positive))
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(
                    "J.A.R.V.I.S.",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold, color = c.accent,
                )
                Text(
                    status.ifBlank { "Tap to talk" },
                    fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.5.sp, color = c.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (pendingCode > 0) {
                    Text(
                        "◆ $pendingCode change${if (pendingCode == 1) "" else "s"} awaiting your approval",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.amber,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            Text(if (pendingCode > 0) "REVIEW ▸" else "TALK ▸", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.sky)
        }
    }
}

@Composable
private fun FlightCard(data: dev.mascwa.pulse.data.radar.RadarData, onClick: () -> Unit) {
    val c = Pulse.colors
    val count = data.aircraftCount()
    val nearest = data.contacts
        .filter { it.kind == dev.mascwa.pulse.data.radar.ContactKind.AIRCRAFT.name }
        .minByOrNull { it.distanceMeters }
    NeonPanel(
        Modifier.fillMaxWidth().padding(top = 12.dp).clickable { onClick() },
        corners = true,
        borderColor = c.sky.copy(alpha = 0.4f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✈", fontSize = 18.sp)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(
                    "$count AIRCRAFT NEARBY",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = c.sky,
                )
                if (nearest != null) {
                    Text(
                        "Nearest: ${nearest.label.ifBlank { "—" }} · ${Formatters.number(nearest.distanceMeters / 1000.0, 1)} km",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Text("SCOPE ▸", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.sky)
        }
    }
}

@Composable
private fun SkyDigestCard(lines: List<String>) {
    val c = Pulse.colors
    NeonPanel(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        corners = true,
        borderColor = c.violet.copy(alpha = 0.5f),
    ) {
        Column {
            DecryptText(
                text = "TODAY IN THE SKY",
                fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 2.sp, color = c.violet,
            )
            lines.forEach { ln ->
                Text(
                    ln, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun BreakingHero(article: Article, onClick: () -> Unit) {
    val c = Pulse.colors
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.linearGradient(listOf(c.magenta.copy(alpha = 0.18f), c.accent.copy(alpha = 0.12f), c.panel)),
            )
            .clickable { onClick() }
            .padding(15.dp),
    ) {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag("● LIVE", c.magenta, filled = true)
                Tag("TOP", c.accent, filled = false)
            }
            Text(
                article.title,
                fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 23.sp,
                color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
            Row(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (article.source.isNotBlank())
                    Text(article.source.uppercase(), fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.accent)
                Text(
                    Formatters.relativeTime(article.publishedEpochMs),
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.ink2,
                )
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: androidx.compose.ui.graphics.Color, filled: Boolean) {
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (filled) color else color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text, fontFamily = JetBrainsMono, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
            color = if (filled) Pulse.colors.void else color,
        )
    }
}

@Composable
private fun MarketsSnapshot(async: Async<List<Quote>>, onClick: () -> Unit) {
    val c = Pulse.colors
    NeonPanel(Modifier.fillMaxWidth().clickable { onClick() }, corners = true) {
        Column {
            val quotes = async.data.orEmpty().take(4)
            if (quotes.isEmpty()) {
                Text(if (async.loading) "Loading…" else "—", fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted)
            } else quotes.forEach { q ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(q.label, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                    Text(Formatters.signedPercent(q.changePercent), fontFamily = JetBrainsMono, fontSize = 12.sp,
                        fontWeight = FontWeight.Medium, color = trendColor((q.changePercent ?: 0.0) >= 0))
                }
            }
        }
    }
}

@Composable
private fun WeatherMiniWidget(async: Async<dev.mascwa.pulse.data.weather.WeatherData>, onClick: () -> Unit) {
    val c = Pulse.colors
    val wd = async.data ?: return
    val cur = wd.current ?: return
    val rain = wd.hourly.take(6).mapNotNull { it.precipProbability }.maxOrNull()
    NeonPanel(Modifier.fillMaxWidth().padding(top = 12.dp).clickable { onClick() }, corners = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(WeatherCode.emoji(cur.weatherCode, cur.isDay), fontSize = 22.sp)
            Text(
                "${Formatters.number(cur.temperature, 0)}${wd.tempUnitSymbol}",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.ink,
                modifier = Modifier.padding(start = 10.dp),
            )
            Text(
                WeatherCode.describe(cur.weatherCode),
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2,
                modifier = Modifier.padding(start = 10.dp).weight(1f),
            )
            if (rain != null) {
                Text(
                    "☂ $rain%",
                    fontFamily = JetBrainsMono, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = if (rain >= 50) c.sky else c.muted,
                )
            }
        }
    }
}

@Composable
private fun EconomySnapshot(state: HomeUiState, onClick: () -> Unit) {
    val c = Pulse.colors
    NeonPanel(Modifier.fillMaxWidth().clickable { onClick() }, corners = true) {
        val dash = state.economy.data
        if (dash == null) {
            Text(if (state.economy.loading) "Loading…" else "—", fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted)
        } else {
            Column {
                val keys = listOf("FP.CPI.TOTL.ZG", "NY.GDP.MKTP.KD.ZG", "SL.UEM.TOTL.ZS")
                dash.series.filter { it.indicatorId in keys && it.latest != null }.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(s.indicatorTitle, style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                        Text(Formatters.percent(s.latest?.value), fontFamily = JetBrainsMono, fontSize = 12.sp,
                            fontWeight = FontWeight.Medium, color = c.ink)
                    }
                }
            }
        }
    }
}

@Composable
private fun FuelSnapshot(state: HomeUiState, onClick: () -> Unit) {
    val c = Pulse.colors
    NeonPanel(Modifier.fillMaxWidth().clickable { onClick() }, corners = true) {
        val benches = state.fuel.data?.benchmarks?.take(3).orEmpty()
        if (benches.isEmpty()) {
            Text(if (state.fuel.loading) "Loading…" else "—", fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted)
        } else Column {
            benches.forEach { q ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(q.label, style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(Formatters.number(q.price, 2), fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink)
                        Text(Formatters.signedPercent(q.changePercent), fontFamily = JetBrainsMono, fontSize = 12.sp,
                            fontWeight = FontWeight.Medium, color = trendColor((q.changePercent ?: 0.0) >= 0))
                    }
                }
            }
        }
    }
}
