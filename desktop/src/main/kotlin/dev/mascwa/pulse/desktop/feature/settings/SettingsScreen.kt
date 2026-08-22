package dev.mascwa.pulse.desktop.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsBusyBar
import dev.mascwa.pulse.desktop.theme.LcarsButton
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsGhostButton
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.standby.StandbyDiagnostics
import dev.mascwa.pulse.desktop.theme.LcarsSwitch
import dev.mascwa.pulse.desktop.theme.LcarsTextField
import dev.mascwa.pulse.desktop.theme.Pulse

/**
 * Every switch this machine has, in four sections.
 *
 * ⚠️ Nothing here is invented for the desktop. Each of these exists because a screen actually reads
 * it — units are read by the weather and the map, refresh by the live feeds, the location by anything
 * that needs a coordinate. A settings page whose switches do nothing is worse than no settings page,
 * and it is the easiest kind to write by accident.
 */
@Composable
fun SettingsScreen(vm: SettingsViewModel, modifier: Modifier = Modifier) {
    val s by vm.settings.collectAsState()
    val locating by vm.locating.collectAsState()
    val c = Pulse.colors

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LcarsHeaderBar("Settings")

        // ----- Location & units ---------------------------------------------------------------
        LcarsFrame(Modifier.fillMaxWidth()) {
            Column {
                SectionTitle("WHERE YOU ARE")
                Text(
                    // ⚠️ Said plainly rather than left to be discovered. Measured from one machine,
                    // three IP-location services answered Council Bluffs, San Francisco and Brooklyn
                    // — all describing some hop the traffic took, none describing where the machine
                    // was. A VPN does exactly the same. So the guess is offered as a starting point
                    // and the field is the authority.
                    "A desktop has no GPS, so this is either a guess from your internet connection or " +
                        "whatever you type. The guess locates the CONNECTION, not you — on a VPN it will " +
                        "be wrong, sometimes by a continent. What you type here always wins.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, lineHeight = 15.sp, color = c.muted,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                LcarsTextField(
                    label = "Place",
                    value = s.placeLabel,
                    onValueChange = vm::setPlaceLabel,
                    placeholder = "Nowhere set yet",
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NumberField(
                        label = "Latitude",
                        value = s.latitude,
                        onCommit = vm::setLatitude,
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        label = "Longitude",
                        value = s.longitude,
                        onCommit = vm::setLongitude,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LcarsButton("GUESS FROM MY CONNECTION", { vm.locate() }, enabled = !locating)
                    if (s.placeSetByHand) {
                        LcarsGhostButton("FORGET WHAT I TYPED", { vm.clearManualPlace() })
                    }
                }
                LcarsBusyBar(locating, Modifier.fillMaxWidth().padding(top = 6.dp))
                if (s.placeSetByHand) {
                    Text(
                        "Set by hand — nothing will overwrite it.",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.amber,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        LcarsFrame(Modifier.fillMaxWidth()) {
            Column {
                SectionTitle("UNITS")
                // ⚠️ Three switches, not one "imperial" switch. Plenty of people want °C with miles,
                // and a single combined control would simply deny that combination.
                LcarsSwitch(
                    "Fahrenheit", s.fahrenheit, vm::setFahrenheit,
                    subtitle = "Off is Celsius",
                )
                LcarsSwitch(
                    "Miles and feet", s.miles, vm::setMiles,
                    subtitle = "Off is kilometres and metres",
                )
                LcarsSwitch(
                    "12-hour clock", s.twelveHourClock, vm::setTwelveHourClock,
                    subtitle = "Off is 24-hour",
                )
            }
        }

        // ----- Data & refresh -------------------------------------------------------------------
        LcarsFrame(Modifier.fillMaxWidth()) {
            Column {
                SectionTitle("YOUR COUNTRY")
                // ⚠️ Not derived from the coordinate above. Somebody may want the weather where they
                // are and the economy of somewhere else, and inferring one from the other would take
                // that away while looking like a convenience.
                LcarsTextField(
                    label = "Country code",
                    value = s.countryCode,
                    onValueChange = vm::setCountryCode,
                    placeholder = "US",
                )
                Text(
                    "Two letters, ISO-3166 — which country's economic figures and fuel prices to show.",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.faint,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        LcarsFrame(Modifier.fillMaxWidth()) {
            Column {
                SectionTitle("DATA & REFRESH")
                Text(
                    "A live screen re-fetches on this interval WHILE IT IS OPEN. Off it there is no " +
                        "timer at all, which is what keeps this cheap.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, lineHeight = 15.sp, color = c.muted,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 5, 15, 30).forEach { m ->
                        LcarsButton(
                            "$m MIN",
                            { vm.setRefreshMinutes(m) },
                            accent = if (s.refreshMinutes == m) c.accent else c.raise,
                        )
                    }
                }
                LcarsSwitch(
                    "Refresh when a screen opens", s.refreshOnOpen, vm::setRefreshOnOpen,
                    subtitle = "Off shows what it already has until the timer comes round",
                )
            }
        }

        // ⚠️ No APPEARANCE section. It offered "Boot sequence" and "Console sounds", and this machine
        // has neither a boot sequence nor an audio kit — the two switches were written to disk and
        // read by nothing, which is worse than not offering them. They come back with the subsystems,
        // not before.

        StandbySection(s, vm)

        // ----- Library & updates ----------------------------------------------------------------
        LcarsFrame(Modifier.fillMaxWidth()) {
            Column {
                SectionTitle("LIBRARY & UPDATES")
                LcarsSwitch(
                    "Community TV channels", s.communityChannels, vm::setCommunityChannels,
                    subtitle = "A volunteer-maintained public catalogue, of mixed origin — it includes " +
                        "unauthorised restreams of channels that are not free to watch. Off by default; " +
                        "whose call that is, is yours.",
                )
                LcarsSwitch(
                    "Look for a newer build on launch", s.autoCheckUpdates, vm::setAutoCheckUpdates,
                    subtitle = "One request. It never installs anything by itself.",
                )
                Text(
                    // ⚠️ Stated where the token is entered, not buried. The phone puts its copy behind
                    // the Titan M2 secure element; a desktop has no equivalent this module can reach.
                    "The token below is stored in PLAIN TEXT in this app's settings file. The file's own " +
                        "permissions in your user profile are the whole protection. Read-only is all this needs.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, lineHeight = 15.sp, color = c.amber,
                    modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
                )
                LcarsTextField(
                    label = "GitHub token (read-only)",
                    value = s.githubToken,
                    onValueChange = vm::setGithubToken,
                    placeholder = "Not set — updates cannot be checked",
                )
                // The same warning covers this one, which is why it sits under it rather than in a
                // section of its own with the caution repeated.
                LcarsTextField(
                    label = "EIA key (optional)",
                    value = s.eiaKey,
                    onValueChange = vm::setEiaKey,
                    placeholder = "Not set — US pump prices unavailable",
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "A free key from eia.gov unlocks weekly US retail petrol and diesel prices on the " +
                        "fuel screen. Everything else there works without one.",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, lineHeight = 14.sp, color = c.faint,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Box(Modifier.height(24.dp))
    }
}

/**
 * The standby display, and — just as importantly — whether each of its three rungs actually engaged.
 *
 * ⚠️ **The readout is not decoration.** Windows does not let any application draw on the lock screen:
 * Winlogon owns a separate desktop object and only the credential provider renders there. What can
 * be done is set the *picture* behind that screen, and even that call can be refused for an
 * unpackaged program on some builds. So the honest design is three genuinely different mechanisms,
 * each of which says in words whether it worked — otherwise "it is not on my lock screen" and "the
 * feature was never finished" look exactly alike from outside.
 */
@Composable
private fun StandbySection(s: dev.mascwa.pulse.desktop.settings.DesktopSettings, vm: SettingsViewModel) {
    val c = Pulse.colors
    LcarsFrame(Modifier.fillMaxWidth()) {
        Column {
            SectionTitle("STANDBY DISPLAY")
            Text(
                "What the console shows when nobody is at it — the whole ops picture, drawn from the " +
                    "same feeds every screen here reads.",
                fontFamily = JetBrainsMono, fontSize = 10.sp, lineHeight = 15.sp, color = c.faint,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LcarsSwitch(
                "Keep it in front", s.standbyHud, vm::setStandbyHud,
                subtitle = "A panel of its own, always on top. Drag it anywhere; closing it switches this off.",
            )
            LcarsSwitch(
                "Use it as the screensaver", s.standbyScreenSaver, vm::setStandbyScreenSaver,
                subtitle = "Registers with Windows for this user only — no administrator prompt. With " +
                    "\"on resume, display logon screen\" set, this is what fills the display on the way " +
                    "into the lock.",
            )
            LcarsSwitch(
                "Put it on the lock screen", s.standbyLockScreen, vm::setStandbyLockScreen,
                subtitle = "Sets the picture Windows shows behind the lock screen. The clock and the " +
                    "sign-in box stay Windows'; no program is permitted to draw over those.",
            )

            // ⚠️ Read straight off the last refresh rather than kept in this screen's own state: a
            // second copy of the rung outcomes is a second thing that can be out of date, and this
            // panel exists precisely to be trusted about what happened.
            val report = StandbyDiagnostics.last
            Text(
                "WHAT ACTUALLY HAPPENED",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                color = c.amber, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            if (report == null) {
                Text(
                    "Nothing drawn yet. Switch one of the three on and it will report here within a " +
                        "few minutes.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, lineHeight = 15.sp, color = c.faint,
                )
            } else {
                StandbyDiagnostics.rungLines(report).forEach { line ->
                    Text(
                        line,
                        fontFamily = JetBrainsMono, fontSize = 10.sp, lineHeight = 15.sp,
                        // Colour follows the mark the line already carries, so nothing here has to
                        // re-derive a verdict the diagnostics core has already reached.
                        color = when {
                            line.contains("✓") -> c.accent
                            line.contains("✗") -> c.amber
                            else -> c.faint
                        },
                    )
                }
                val degraded = StandbyDiagnostics.degradedLine(report.outcomes)
                if (degraded.isNotBlank()) {
                    Text(
                        degraded,
                        fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.amber,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
        fontSize = 13.sp, letterSpacing = 1.6.sp, color = Pulse.colors.accent,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * A coordinate, edited as text.
 *
 * ⚠️ Kept in local state while being typed and committed on a valid parse, NOT on every keystroke.
 * Typing "-" then "1" then "." produces three intermediate strings that are not numbers, and a field
 * that wrote through on each of them would fight the person using it — clearing itself, or worse,
 * committing a partial value. Blank commits null, which is how a coordinate is unset.
 */
@Composable
private fun NumberField(
    label: String,
    value: Double?,
    onCommit: (Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on the incoming value so an external change (the IP guess landing) refreshes the box,
    // while ordinary typing does not re-initialise it.
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    LcarsTextField(
        label = label,
        value = text,
        onValueChange = { next ->
            text = next
            val trimmed = next.trim()
            when {
                trimmed.isEmpty() -> onCommit(null)
                else -> trimmed.toDoubleOrNull()?.let(onCommit)
            }
        },
        placeholder = "unset",
        modifier = modifier,
    )
}
