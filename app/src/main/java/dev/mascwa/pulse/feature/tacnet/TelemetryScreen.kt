package dev.mascwa.pulse.feature.tacnet

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.mascwa.pulse.ui.theme.NightwirePalette
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import dev.mascwa.pulse.core.telemetry.Achievement
import dev.mascwa.pulse.core.telemetry.Achievements
import dev.mascwa.pulse.core.telemetry.AgeBand
import dev.mascwa.pulse.core.telemetry.Build
import dev.mascwa.pulse.core.telemetry.Character
import dev.mascwa.pulse.core.telemetry.Choice
import dev.mascwa.pulse.core.telemetry.Companion
import dev.mascwa.pulse.core.telemetry.Companions
import dev.mascwa.pulse.core.telemetry.DailyObjective
import dev.mascwa.pulse.core.telemetry.DailyObjectives
import dev.mascwa.pulse.core.telemetry.TodayMetrics
import dev.mascwa.pulse.core.telemetry.Encounter
import dev.mascwa.pulse.core.telemetry.EnvContext
import dev.mascwa.pulse.core.telemetry.GameLocation
import dev.mascwa.pulse.core.telemetry.GameLocations
import dev.mascwa.pulse.core.telemetry.GestureType
import dev.mascwa.pulse.core.telemetry.Gestures
import dev.mascwa.pulse.core.telemetry.GameMetrics
import dev.mascwa.pulse.core.telemetry.GameClock
import dev.mascwa.pulse.core.telemetry.LocationGate
import dev.mascwa.pulse.core.telemetry.LocationKind
import dev.mascwa.pulse.core.telemetry.RepTier
import dev.mascwa.pulse.core.telemetry.Reputation
import dev.mascwa.pulse.core.telemetry.Environment
import dev.mascwa.pulse.core.util.Geo
import dev.mascwa.pulse.data.game.DailyState
import dev.mascwa.pulse.data.game.TravelStats
import dev.mascwa.pulse.data.weather.DeviceLocation
import dev.mascwa.pulse.core.telemetry.Item
import dev.mascwa.pulse.core.telemetry.ItemKind
import dev.mascwa.pulse.core.telemetry.Items
import dev.mascwa.pulse.core.telemetry.LifeProfile
import dev.mascwa.pulse.core.telemetry.LifeStats
import dev.mascwa.pulse.core.telemetry.Perk
import dev.mascwa.pulse.core.telemetry.Perks
import dev.mascwa.pulse.core.telemetry.Quest
import dev.mascwa.pulse.core.telemetry.QuestGoal
import dev.mascwa.pulse.core.telemetry.QuestKind
import dev.mascwa.pulse.core.telemetry.QuestView
import dev.mascwa.pulse.core.telemetry.Recipe
import dev.mascwa.pulse.core.telemetry.Recipes
import dev.mascwa.pulse.core.telemetry.Resolution
import dev.mascwa.pulse.core.telemetry.Special
import dev.mascwa.pulse.core.telemetry.SpecialGame
import dev.mascwa.pulse.data.sensors.Telemetry
import dev.mascwa.pulse.feature.common.PipFrame
import dev.mascwa.pulse.feature.common.PipHeader
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun TelemetryScreen(vm: TelemetryViewModel, onBack: (() -> Unit)? = null) {
    PulseScaffold(
        title = "Telemetry",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
    ) { innerPadding ->
        TelemetryBody(vm, Modifier.padding(innerPadding))
    }
}

/**
 * Shared game lifecycle: starts/stops the telemetry + ambient samplers with the composition, and requests
 * the camera permission once (perception's "seeing" half is a no-op until granted, then vm.start() picks
 * it up). Every game body hosts this, so sensors run on any game tab (STATUS / S.P.E.C.I.A.L. / GEAR /
 * WASTELAND) but stop when a non-game feed (RADIO / MAP / …) is open — keeping the camera indicator off
 * except while actually playing.
 */
@Composable
private fun GameSensors(vm: TelemetryViewModel) {
    val context = LocalContext.current
    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.start()
    }
    LaunchedEffect(Unit) {
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runCatching { requestCamera.launch(android.Manifest.permission.CAMERA) }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.start()
                Lifecycle.Event.ON_PAUSE -> vm.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        vm.start()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.stop()
        }
    }
}

/**
 * The STATS ▸ STATUS body — the pure device readout (operator portrait, condition, radiation, effects, and
 * the raw vitals / sensors / system / position / data-stream panels). The S.P.E.C.I.A.L. character, the
 * wasteland gear and the quests/map/achievements now live on their own PIP-BOY tabs. Scaffold-free.
 */
@Composable
fun TelemetryBody(vm: TelemetryViewModel, modifier: Modifier = Modifier) {
    GameSensors(vm)
    val t by vm.telemetry.collectAsStateWithLifecycle()
    val gps by vm.gps.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    val portraitUri by vm.portraitUri.collectAsStateWithLifecycle()
    val character by vm.character.collectAsStateWithLifecycle()
    val c = Pulse.colors
    val context = LocalContext.current

    val pickPortrait = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.setPortrait(uri.toString())
        }
    }

    Column(
        modifier.padding(horizontal = 16.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        PipHeader("Operator")
        OperatorPortrait(portraitUri, character.level, c) {
            runCatching { pickPortrait.launch(arrayOf("image/*")) }
        }

        PipHeader("Condition")
        ConditionPanel(t, c)

        PipHeader("Radiation")
        RadiationPanel(t, c)

        PipHeader("Effects")
        EffectsPanel(t, gps != null, c)

        PipHeader("Vitals")
        PipFrame(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FalloutGauge("Battery", batteryText(t), (t.batteryPct ?: 0) / 100f, if (t.charging) c.positive else c.accent)
                FalloutGauge("Memory", "${t.memUsedMb} / ${t.memTotalMb} MB",
                    if (t.memTotalMb > 0) t.memUsedMb.toFloat() / t.memTotalMb else 0f, c.violet)
                FalloutGauge("Ambient light", t.lightLux?.let { "${it.roundToInt()} lx" } ?: "—",
                    min(1f, (t.lightLux ?: 0f) / 2000f), c.amber)
                FalloutGauge("Magnetic field", t.magneticUt?.let { "${it.roundToInt()} µT" } ?: "—",
                    min(1f, (t.magneticUt ?: 0f) / 100f), c.sky)
                FalloutGauge("G-force", t.accelG?.let { "%.2f g".format(it) } ?: "—",
                    min(1f, (t.accelG ?: 0f) / 2f), c.magenta)
            }
        }

        PipHeader("Sensors")
        PipFrame(Modifier.fillMaxWidth()) {
            Column {
                FalloutStatRow("Pressure", t.pressureHpa?.let { "%.1f hPa".format(it) } ?: if (t.hasBarometer) "…" else "no sensor")
                FalloutStatRow("Baro altitude", t.pressureAltitudeM?.let { "${it.roundToInt()} m" } ?: "—")
                FalloutStatRow("Tilt (pitch)", t.tiltPitchDeg?.let { "${it.roundToInt()}°" } ?: "—")
                FalloutStatRow("Tilt (roll)", t.tiltRollDeg?.let { "${it.roundToInt()}°" } ?: "—")
                FalloutStatRow("Rotation rate", t.gyroDps?.let { "${it.roundToInt()} °/s" } ?: "—")
            }
        }

        PipHeader("System")
        PipFrame(Modifier.fillMaxWidth()) {
            Column {
                FalloutStatRow("Battery temp", t.batteryTempC?.let { "%.1f °C".format(it) } ?: "—")
                FalloutStatRow("Power", if (t.charging) "Charging" else "On battery")
                FalloutStatRow("Network", t.netType)
                FalloutStatRow("Signal", t.netSignal)
                FalloutStatRow("Memory used", "${t.memUsedMb} MB")
            }
        }

        PipHeader("Position")
        PipFrame(Modifier.fillMaxWidth()) {
            val loc = gps
            if (loc != null) {
                Column {
                    FalloutStatRow("Latitude", "%.5f".format(loc.latitude))
                    FalloutStatRow("Longitude", "%.5f".format(loc.longitude))
                    FalloutStatRow("Place", loc.name)
                }
            } else {
                Text(
                    "No GPS fix yet — grant location and ensure GPS is on. Works without internet.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                )
            }
        }

        PipHeader("Data stream")
        PipFrame(Modifier.fillMaxWidth()) {
            Column {
                if (log.isEmpty()) {
                    Text("// initialising sensors…", fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted)
                } else {
                    log.forEach { line ->
                        Text(line, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.positive, maxLines = 1)
                    }
                }
            }
        }

        Text(
            "All values read directly from on-device sensors and the OS — no network required.",
            fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
            modifier = Modifier.padding(top = 14.dp, bottom = 24.dp),
        )
    }
}

/**
 * The STATS ▸ S.P.E.C.I.A.L. body — the character sheet (stats, XP, perks) and the live encounter / idle /
 * downed play loop, plus the day banner, the perceived-scene readout, and the transient level-up / quest-
 * complete / achievement-unlock banners (surfaced where you actually play). Scaffold-free.
 */
@Composable
fun SpecialGameBody(vm: TelemetryViewModel, modifier: Modifier = Modifier) {
    GameSensors(vm)
    val character by vm.character.collectAsStateWithLifecycle()
    val encounter by vm.currentEncounter.collectAsStateWithLifecycle()
    val resolution by vm.gameResolution.collectAsStateWithLifecycle()
    val env by vm.env.collectAsStateWithLifecycle()
    val lastUnlock by vm.lastUnlock.collectAsStateWithLifecycle()
    val dayBanner by vm.dayBanner.collectAsStateWithLifecycle()
    val questDone by vm.questCompleted.collectAsStateWithLifecycle()
    val scene by vm.sceneContext.collectAsStateWithLifecycle()
    val life by vm.life.collectAsStateWithLifecycle()
    val c = Pulse.colors

    Column(
        modifier.padding(horizontal = 16.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        PipHeader("Life")
        LifePanel(
            life, c,
            onSetHeight = { vm.setHeight(it) }, onSetWeight = { vm.setWeight(it) },
            onSetAge = { vm.setAge(it) }, onSetMoney = { vm.setRealMoney(it) },
            onSetMood = { vm.setMood(it) },
            onDrink = { vm.drink() }, onWash = { vm.wash() }, onRest = { vm.rest() },
        )
        Spacer(Modifier.height(8.dp))

        PipHeader("S.P.E.C.I.A.L.")
        if (dayBanner.isNotEmpty()) {
            Text(
                dayBanner,
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                color = c.amber, letterSpacing = 2.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            "PERCEIVES · ${scene.describe()}",
            fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.sky,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )
        questDone?.let { q ->
            QuestCompleteBanner(q, c) { vm.dismissQuestComplete() }
            Spacer(Modifier.height(8.dp))
        }
        lastUnlock?.let { a ->
            UnlockBanner(a, c) { vm.dismissUnlock() }
            Spacer(Modifier.height(8.dp))
        }
        CharacterSheet(character, c) { vm.allocate(it) }
        Spacer(Modifier.height(8.dp))
        ConditionsPanel(env, c)
        if (character.perkPicks > 0) {
            Spacer(Modifier.height(8.dp))
            PerkChoicePanel(character, c) { vm.choosePerk(it) }
        }
        Spacer(Modifier.height(8.dp))
        when {
            character.down -> DownedPanel(c) { vm.revive() }
            encounter != null -> EncounterPanel(encounter!!, character, vm.telemetryFlow, c) { i, chem, roll -> vm.choose(i, chem, roll) }
            else -> IdlePanel(resolution, c) { vm.venture() }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The ITEMS ▸ GEAR body — the wasteland loadout: inventory (use / sell), the workbench (craft), companions
 * (hire / dismiss) and faction reputation. Scaffold-free.
 */
@Composable
fun ItemsGameBody(vm: TelemetryViewModel, modifier: Modifier = Modifier) {
    GameSensors(vm)
    val character by vm.character.collectAsStateWithLifecycle()
    val c = Pulse.colors
    Column(
        modifier.padding(horizontal = 16.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        PipHeader("Inventory")
        InventoryPanel(character, c, onUseItem = { vm.useItem(it) }, onSellItem = { vm.sellItem(it) })
        Spacer(Modifier.height(8.dp))
        PipHeader("Workbench")
        WorkbenchPanel(character, c) { vm.craft(it) }
        Spacer(Modifier.height(8.dp))
        PipHeader("Companions")
        CompanionPanel(character, c, onHire = { vm.hireCompanion(it) }, onDismiss = { vm.dismissCompanion() })
        Spacer(Modifier.height(8.dp))
        PipHeader("Reputation")
        ReputationPanel(character, c)
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The DATA ▸ WASTELAND body — the game's data log: daily objectives, personalised quests, the real-world
 * wasteland map (scan / shop / talk) and achievements. Scaffold-free.
 */
@Composable
fun WastelandDataBody(vm: TelemetryViewModel, modifier: Modifier = Modifier) {
    GameSensors(vm)
    val character by vm.character.collectAsStateWithLifecycle()
    val gps by vm.gps.collectAsStateWithLifecycle()
    val unlocked by vm.unlockedAchievements.collectAsStateWithLifecycle()
    val gameMetrics by vm.gameMetrics.collectAsStateWithLifecycle()
    val locations by vm.locations.collectAsStateWithLifecycle()
    val travel by vm.travel.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val daily by vm.daily.collectAsStateWithLifecycle()
    val quests by vm.quests.collectAsStateWithLifecycle()
    val c = Pulse.colors
    Column(
        modifier.padding(horizontal = 16.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        if (daily.objectives.isNotEmpty()) {
            PipHeader("Daily")
            DailyPanel(daily, c) { vm.claimDaily(it) }
            Spacer(Modifier.height(8.dp))
        }
        if (quests.isNotEmpty()) {
            PipHeader("Quests")
            QuestsPanel(quests, c)
            Spacer(Modifier.height(8.dp))
        }
        PipHeader("Wasteland")
        WastelandPanel(locations, travel, scanning, gps, character, c,
            onScan = { vm.scanArea() }, onBuy = { id, kind -> vm.buy(id, kind) }, onTalk = { enc, kind -> vm.talk(enc, kind) })
        Spacer(Modifier.height(8.dp))
        PipHeader("Achievements")
        AchievementsPanel(unlocked, gameMetrics, c)
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The LIFE panel — the operator's real self, fed into the wasteland. Editable body metrics + a
 * self-reported real-money figure (which buffs the game), two Sims-style needs (hydration / hygiene) you
 * keep topped up, a build / age / wealth readout, and a live list of how it all bends your checks. Every
 * value is on-device only and never leaves the phone.
 */
@Composable
private fun LifePanel(
    life: LifeProfile,
    c: NightwirePalette,
    onSetHeight: (Int) -> Unit,
    onSetWeight: (Int) -> Unit,
    onSetAge: (Int) -> Unit,
    onSetMoney: (Double) -> Unit,
    onSetMood: (Int) -> Unit,
    onDrink: () -> Unit,
    onWash: () -> Unit,
    onRest: () -> Unit,
) {
    PipFrame(Modifier.fillMaxWidth(), accent = c.sky) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("VITALS OF THE OPERATOR", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                fontSize = 12.sp, color = c.sky, letterSpacing = 1.sp)
            Text("Your real life bleeds into the wasteland. Stays on this device — never leaves.",
                fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted)

            LifeNumberField("Height", if (life.heightCm > 0) life.heightCm.toString() else "", "cm", c) {
                onSetHeight(it.toIntOrNull() ?: 0)
            }
            LifeNumberField("Weight", if (life.weightKg > 0) life.weightKg.toString() else "", "kg", c) {
                onSetWeight(it.toIntOrNull() ?: 0)
            }
            LifeNumberField("Age", if (life.ageYears > 0) life.ageYears.toString() else "", "yr", c) {
                onSetAge(it.toIntOrNull() ?: 0)
            }
            LifeNumberField("Real money", if (life.realMoney > 0) life.realMoney.roundToInt().toString() else "",
                life.currency, c) {
                onSetMoney(it.toDoubleOrNull() ?: 0.0)
            }
            LifeNumberField("Mood", life.mood.toString(), "/100", c) { onSetMood(it.toIntOrNull() ?: 50) }

            // Build / age / wealth summary.
            val bits = buildList {
                LifeStats.build(life).takeIf { it != Build.UNSET }?.let { add(it.label) }
                LifeStats.ageBand(life).takeIf { it != AgeBand.UNSET }?.let { add(it.label) }
                add(LifeStats.moneyTier(life).label)
            }
            Text(bits.joinToString("  ·  ").uppercase(), fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                fontSize = 10.sp, color = c.amber, letterSpacing = 1.sp)

            // Needs meters + top-up actions.
            FalloutGauge("Hydration", "${life.hydration}%", life.hydration / 100f, c.sky)
            FalloutGauge("Hygiene", "${life.hygiene}%", life.hygiene / 100f, c.positive)
            FalloutGauge("Energy", "${life.energy}%", life.energy / 100f, c.amber)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { GameButton("DRINK", c.sky, onDrink) }
                Box(Modifier.weight(1f)) { GameButton("WASH", c.positive, onWash) }
                Box(Modifier.weight(1f)) { GameButton("REST", c.amber, onRest) }
            }

            // How the profile bends your checks right now (mirrors the CONDITIONS readout).
            val fx = LifeStats.describe(life)
            if (fx.isNotEmpty()) {
                Text("HOW YOUR LIFE BENDS THE WASTELAND", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                    fontSize = 10.sp, color = c.ink, letterSpacing = 1.sp, modifier = Modifier.padding(top = 2.dp))
                fx.forEach { Text(it, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.ink2) }
            }
        }
    }
}

/**
 * One labelled numeric input for [LifePanel]. Commits on focus-loss / IME-done (not per keystroke), so the
 * decayed-needs flow re-emitting each tick can never clobber what you're mid-typing. Digits only.
 */
@Composable
private fun LifeNumberField(
    label: String,
    value: String,
    unit: String,
    c: NightwirePalette,
    onCommit: (String) -> Unit,
) {
    // Re-seed only when the committed [value] changes externally (load / reset / clamp) — not while typing.
    var text by remember(value) { mutableStateOf(value) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted, modifier = Modifier.weight(1f))
        BasicTextField(
            value = text,
            onValueChange = { text = it.filter { ch -> ch.isDigit() }.take(7) },
            singleLine = true,
            textStyle = TextStyle(color = c.ink, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                fontSize = 14.sp, textAlign = TextAlign.End),
            cursorBrush = SolidColor(c.sky),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommit(text) }),
            modifier = Modifier.width(96.dp).background(c.void).border(1.dp, c.line)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .onFocusChanged { if (!it.isFocused) onCommit(text) },
            decorationBox = { inner ->
                if (text.isEmpty()) {
                    Text("—", fontFamily = ChakraPetch, fontSize = 14.sp, color = c.muted,
                        textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                }
                inner()
            },
        )
        Text(" $unit", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
    }
}

private fun batteryText(t: Telemetry): String {
    val pct = t.batteryPct?.let { "$it%" } ?: "—"
    return if (t.charging) "$pct ⚡" else pct
}

// ---- CONDITION: an original Pip-Boy-style figure whose body regions tint by live device health. ----

private fun powerScore(t: Telemetry): Float = if (t.charging) 1f else (t.batteryPct ?: 100) / 100f
private fun memoryScore(t: Telemetry): Float =
    if (t.memTotalMb > 0) (1f - t.memUsedMb.toFloat() / t.memTotalMb).coerceIn(0f, 1f) else 1f
private fun thermalScore(t: Telemetry): Float =
    t.batteryTempC?.let { (1f - (it - 25f) / 25f).coerceIn(0f, 1f) } ?: 1f

private fun condColor(c: NightwirePalette, score: Float): Color = when {
    score >= 0.66f -> c.positive
    score >= 0.33f -> c.amber
    else -> c.negative
}

/** The STATUS condition readout: a tinted humanoid figure + a CND score and per-system breakdown. */
@Composable
private fun ConditionPanel(t: Telemetry, c: NightwirePalette) {
    val power = powerScore(t)
    val memory = memoryScore(t)
    val thermal = thermalScore(t)
    val overall = (power + memory + thermal) / 3f
    val overallColor = condColor(c, overall)
    val label = when {
        overall >= 0.66f -> "OPTIMAL"
        overall >= 0.33f -> "FAIR"
        else -> "CRITICAL"
    }
    PipFrame(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConditionFigure(
                Modifier.size(width = 78.dp, height = 116.dp),
                head = condColor(c, thermal),
                torso = condColor(c, memory),
                limbs = overallColor,
                legs = condColor(c, power),
            )
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("CND ${(overall * 100).roundToInt()}%", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                    fontSize = 22.sp, color = overallColor)
                Text(label, fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.5.sp, color = c.muted,
                    modifier = Modifier.padding(bottom = 6.dp))
                CondRow("PWR", batteryText(t), condColor(c, power), c)
                CondRow("MEM", "${(memory * 100).roundToInt()}% free", condColor(c, memory), c)
                CondRow("THRM", t.batteryTempC?.let { "%.1f °C".format(it) } ?: "—", condColor(c, thermal), c)
            }
        }
    }
}

/** A per-system condition row: a status dot + label + value. */
@Composable
private fun CondRow(label: String, value: String, dot: Color, c: NightwirePalette) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(dot))
        Text(label, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.8.sp, color = c.muted,
            modifier = Modifier.padding(start = 7.dp))
        Spacer(Modifier.weight(1f))
        Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.ink)
    }
}

/** An original, generic operator silhouette (NOT a trademarked character) — head/torso/arms/legs drawn
 *  procedurally, each region tinted by its subsystem's health. Filled at low alpha + a bright outline. */
@Composable
private fun ConditionFigure(modifier: Modifier, head: Color, torso: Color, limbs: Color, legs: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val sw = 3.dp.toPx()
        val a = 0.22f

        // Head.
        val headR = h * 0.095f
        val headC = Offset(cx, h * 0.12f)
        drawCircle(head.copy(alpha = a), headR, headC)
        drawCircle(head, headR, headC, style = Stroke(sw))

        // Torso (a tapered trunk).
        val tTop = h * 0.25f
        val tBot = h * 0.58f
        val half = w * 0.17f
        val torsoPath = Path().apply {
            moveTo(cx - half, tTop)
            lineTo(cx + half, tTop)
            lineTo(cx + half * 0.78f, tBot)
            lineTo(cx - half * 0.78f, tBot)
            close()
        }
        drawPath(torsoPath, torso.copy(alpha = a))
        drawPath(torsoPath, torso, style = Stroke(sw))

        // Arms from the shoulders.
        val shoulderY = tTop + h * 0.015f
        drawLine(limbs, Offset(cx - half, shoulderY), Offset(cx - half * 1.85f, h * 0.52f), sw, StrokeCap.Round)
        drawLine(limbs, Offset(cx + half, shoulderY), Offset(cx + half * 1.85f, h * 0.52f), sw, StrokeCap.Round)

        // Legs from the hips.
        drawLine(legs, Offset(cx - half * 0.55f, tBot), Offset(cx - half * 0.75f, h * 0.96f), sw, StrokeCap.Round)
        drawLine(legs, Offset(cx + half * 0.55f, tBot), Offset(cx + half * 0.75f, h * 0.96f), sw, StrokeCap.Round)
    }
}

/** A VITALS gauge in the Fallout HP/AP idiom: a green-banded label/value header over a notched,
 *  segment-lit bar (Pip-Boy SPECIAL/condition bars are segmented, not smooth). */
@Composable
private fun FalloutGauge(label: String, value: String, fraction: Float, color: Color) {
    val c = Pulse.colors
    val frac = fraction.coerceIn(0f, 1f)
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .background(c.accent.copy(alpha = 0.07f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(label.uppercase(), fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.8.sp, color = c.ink2)
                Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = color)
            }
            Canvas(Modifier.fillMaxWidth().height(8.dp).padding(top = 4.dp)) {
                val segs = 24
                val gap = 2f
                val segW = (size.width - gap * (segs - 1)) / segs
                val lit = (frac * segs).roundToInt()
                val dim = c.lineSoft.copy(alpha = 0.35f)
                for (i in 0 until segs) {
                    drawRect(
                        if (i < lit) color else dim,
                        topLeft = Offset(i * (segW + gap), 0f),
                        size = Size(segW, size.height),
                    )
                }
            }
        }
    }
}

/** A SENSORS/SYSTEM/POSITION readout — the canonical Fallout DATA>STATS banded row (shared [PipDataRow]). */
@Composable
private fun FalloutStatRow(label: String, value: String) {
    dev.mascwa.pulse.feature.common.PipDataRow(label, value)
}

/** Green-phosphor duotone (luminance → green) so a portrait reads as part of the Pip-Boy CRT. */
private val PortraitDuotone = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.030f, 0.059f, 0.011f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.090f, 0.176f, 0.034f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

/** The operator portrait (Fallout STATUS character image): tap to pick an image — it persists and renders
 *  in the green CRT duotone with an OPERATIVE · LEVEL line; empty shows the upload prompt. */
@Composable
private fun OperatorPortrait(uri: String, level: Int, c: NightwirePalette, onPick: () -> Unit) {
    PipFrame(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (uri.isBlank()) {
                Box(
                    Modifier.fillMaxWidth().height(150.dp)
                        .border(1.dp, c.line, RoundedCornerShape(4.dp))
                        .clickable { onPick() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("CLICK TO UPLOAD AN IMAGE", fontFamily = JetBrainsMono, fontSize = 11.sp,
                        letterSpacing = 1.sp, color = c.muted)
                }
            } else {
                AsyncImage(
                    model = uri,
                    contentDescription = "Operator portrait",
                    modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(4.dp)).clickable { onPick() },
                    contentScale = ContentScale.Crop,
                    colorFilter = PortraitDuotone,
                )
            }
            Text(
                "OPERATIVE · LEVEL $level", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                fontSize = 14.sp, letterSpacing = 1.5.sp, color = c.ink, modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** The RADIATION readout (Fallout STATUS>RAD): "rads" = live system stress — memory pressure plus
 *  thermal load above nominal — on the 0–1000 RADS ruler; RAD RESIST = free headroom. */
@Composable
private fun RadiationPanel(t: Telemetry, c: NightwirePalette) {
    val memPct = if (t.memTotalMb > 0) ((t.memUsedMb * 100) / t.memTotalMb).toInt() else 0
    val temp = t.batteryTempC ?: 25f
    val rads = (memPct * 5 + (maxOf(0f, temp - 25f) * 20f).toInt()).coerceIn(0, 1000)
    val resist = (100 - memPct).coerceIn(0, 100)
    PipFrame(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("RAD RESIST $resist%", fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.accent)
                Text("$rads / 1000 RADS", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.ink)
            }
            RadRuler(rads, c, Modifier.fillMaxWidth().padding(top = 12.dp))
        }
    }
}

/** The Fallout RADS ruler: a tick scale 0…1000 with an arrow pointer at the current reading. */
@Composable
private fun RadRuler(rads: Int, c: NightwirePalette, modifier: Modifier) {
    Canvas(modifier.height(34.dp)) {
        val w = size.width
        val y = size.height * 0.62f
        drawLine(c.accent.copy(alpha = 0.5f), Offset(0f, y), Offset(w, y), 1.5.dp.toPx())
        for (i in 0..10) {
            val x = (w * i / 10f).coerceIn(0.5f, w - 0.5f)
            val tall = i % 5 == 0
            drawLine(c.accent.copy(alpha = if (tall) 0.7f else 0.4f), Offset(x, y), Offset(x, y - if (tall) 11f else 6f), 1.dp.toPx())
        }
        val px = (w * (rads / 1000f)).coerceIn(0f, w)
        val arrow = Path().apply {
            moveTo(px, y - 3f)
            lineTo(px - 7f, y + 11f)
            lineTo(px + 7f, y + 11f)
            close()
        }
        drawPath(arrow, c.accent)
    }
}

/** A Fallout-style status effect derived from live device state. */
private data class StatusEffect(val tag: String, val name: String, val desc: String, val good: Boolean)

/** Active "effects" — real device/app state surfaced as Fallout status effects (the STATUS>EFF panel):
 *  charging, low power, thermal, data link, GPS. */
private fun activeEffects(t: Telemetry, hasGps: Boolean): List<StatusEffect> {
    val out = ArrayList<StatusEffect>()
    val bat = t.batteryPct ?: 0
    if (t.charging) out += StatusEffect("⚡", "CHARGING", "Power cell recharging", true)
    if (!t.charging && bat in 1..20) out += StatusEffect("▼", "LOW POWER", "Reserves critical — conserve energy", false)
    val temp = t.batteryTempC
    if (temp != null && temp >= 40f) out += StatusEffect("△", "OVERHEATING", "Core temperature elevated", false)
    if (t.netType.isNotBlank()) out += StatusEffect("≋", "${t.netType.uppercase()} LINK", "Data uplink established", true)
    else out += StatusEffect("⊘", "OFFLINE", "No uplink — running on local cache", false)
    if (hasGps) out += StatusEffect("◎", "GPS LOCK", "Position fix acquired", true)
    return out
}

/** The EFFECTS readout (Fallout STATUS>EFF): active device state as status effects. */
@Composable
private fun EffectsPanel(t: Telemetry, hasGps: Boolean, c: NightwirePalette) {
    val effects = activeEffects(t, hasGps)
    PipFrame(Modifier.fillMaxWidth()) {
        if (effects.isEmpty()) {
            Text("No active effects.", fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted)
        } else {
            Column { effects.forEach { EffectRow(it, c) } }
        }
    }
}

// ---- S.P.E.C.I.A.L. — a playable wasteland RPG. The seven stats gate encounter choices; play to
//      earn XP/caps, level up, and allocate points. The character persists (SpecialGameStore). ----

/** The S.P.E.C.I.A.L. character sheet: the LVL/CAPS/HP vitals + XP bar, a level-up prompt, the seven
 *  allocatable stat rows and the owned-perks line. The inventory / workbench / companions / reputation
 *  now live on the ITEMS ▸ GEAR tab; encounters/conditions/perk-picks live on the S.P.E.C.I.A.L. body. */
@Composable
private fun CharacterSheet(ch: Character, c: NightwirePalette, onAllocate: (Special) -> Unit) {
    PipFrame(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            GameVitals(ch, c)
            Spacer(Modifier.height(4.dp))
            if (ch.unspent > 0) {
                Text(
                    "LEVEL UP — ${ch.unspent} point${if (ch.unspent > 1) "s" else ""} to spend. Tap ＋ to raise a stat.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber,
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
            Special.entries.forEach { s ->
                SpecialGameRow(s, ch.stat(s), ch.unspent > 0 && ch.stat(s) < 10, c) { onAllocate(s) }
            }
            if (ch.perks.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "PERKS · " + ch.perks.mapNotNull { Perks.byId(it)?.name }.joinToString(" · "),
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.positive,
                )
            }
        }
    }
}

/** The real world, made visible: how the current conditions bend your checks right now. */
@Composable
private fun ConditionsPanel(env: EnvContext, c: NightwirePalette) {
    val effects = Environment.effects(env)
    PipFrame(Modifier.fillMaxWidth(), accent = c.sky) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("CONDITIONS", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                color = c.sky, letterSpacing = 1.sp)
            if (effects.isEmpty()) {
                Text("Conditions nominal — the wasteland isn't tipping the scales.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
            } else {
                effects.forEach { e ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(e.reason, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.ink)
                        Text(
                            "${e.stat.display.take(3)} ${if (e.delta >= 0) "+" else "−"}${kotlin.math.abs(e.delta)}",
                            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                            color = if (e.delta >= 0) c.positive else c.negative,
                        )
                    }
                }
            }
        }
    }
}

/** The player's pack: AID to heal, GEAR/CHEM held, junk to sell. USE (aid) + SELL per row. */
@Composable
private fun InventoryPanel(ch: Character, c: NightwirePalette, onUseItem: (String) -> Unit, onSellItem: (String) -> Unit) {
    PipFrame(Modifier.fillMaxWidth(), accent = c.amber) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("PACK", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                color = c.amber, letterSpacing = 1.sp)
            val items = Items.ALL.mapNotNull { item ->
                val qty = ch.inventory[item.id] ?: 0
                if (qty > 0) item to qty else null
            }
            if (items.isEmpty()) {
                Text("Empty. Venture out and loot the wasteland.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
            } else {
                items.forEach { (item, qty) -> InventoryRow(item, qty, c, onUseItem, onSellItem) }
            }
        }
    }
}

@Composable
private fun InventoryRow(item: Item, qty: Int, c: NightwirePalette, onUseItem: (String) -> Unit, onSellItem: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .border(1.dp, c.line, RoundedCornerShape(4.dp)).background(c.amber.copy(alpha = 0.05f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${item.name}${if (qty > 1) "  ×$qty" else ""}", fontFamily = ChakraPetch,
                fontWeight = FontWeight.Bold, fontSize = 11.sp, color = c.ink)
            Text(item.desc, fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted, modifier = Modifier.padding(top = 1.dp))
        }
        if (item.kind == ItemKind.AID) {
            InvAction("USE", c.positive) { onUseItem(item.id) }
            Spacer(Modifier.width(8.dp))
        }
        InvAction("SELL ${(item.value / 2).coerceAtLeast(1)}", c.amber) { onSellItem(item.id) }
    }
}

@Composable
private fun InvAction(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(3.dp)).border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.1f)).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(label, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = color, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun PerkChoicePanel(ch: Character, c: NightwirePalette, onChoose: (String) -> Unit) {
    val available = Perks.ALL.filter { it.id !in ch.perks }
    PipFrame(Modifier.fillMaxWidth(), accent = c.amber) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "CHOOSE A PERK · ${ch.perkPicks} available", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                fontSize = 13.sp, color = c.amber, letterSpacing = 1.sp,
            )
            if (available.isEmpty()) {
                Text("You've earned every perk. Legend.", fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted)
            } else {
                available.forEach { perk -> PerkRow(perk, c) { onChoose(perk.id) } }
            }
        }
    }
}

@Composable
private fun PerkRow(perk: Perk, c: NightwirePalette, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .border(1.dp, c.amber.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).background(c.amber.copy(alpha = 0.06f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(perk.name, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = c.ink)
            Text(perk.desc, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted, modifier = Modifier.padding(top = 1.dp))
        }
        Text("TAKE", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = c.amber, letterSpacing = 1.sp)
    }
}

@Composable
private fun GameVitals(ch: Character, c: NightwirePalette) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("LVL ${ch.level}", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.accent)
        Text("◆ ${ch.caps} CAPS", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = c.amber)
        Text(
            "HP ${ch.hp}/${ch.maxHp}", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp,
            color = if (ch.hp <= ch.maxHp / 4) c.negative else c.positive,
        )
    }
    SegBar((ch.xp.toFloat() / ch.xpToNext).coerceIn(0f, 1f), c.accent, c)
    Text("XP ${ch.xp} / ${ch.xpToNext}", fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted)
}

@Composable
private fun SegBar(frac: Float, color: Color, c: NightwirePalette) {
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        val segs = 24
        val gap = 2f
        val segW = (size.width - gap * (segs - 1)) / segs
        val lit = (frac.coerceIn(0f, 1f) * segs).roundToInt()
        val dim = c.lineSoft.copy(alpha = 0.35f)
        for (i in 0 until segs) {
            drawRect(if (i < lit) color else dim, topLeft = Offset(i * (segW + gap), 0f), size = Size(segW, size.height))
        }
    }
}

@Composable
private fun SpecialGameRow(s: Special, value: Int, canAllocate: Boolean, c: NightwirePalette, onAllocate: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .drawBehind {
                drawRect(c.accent.copy(alpha = 0.08f))
                drawLine(c.accent.copy(alpha = 0.35f), Offset(0f, size.height), Offset(size.width, size.height), 1.2.dp.toPx())
            }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${s.letter}", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            color = c.accent, modifier = Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(s.display, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                color = c.ink, letterSpacing = 0.8.sp)
            Text(s.blurb, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted, modifier = Modifier.padding(top = 1.dp))
        }
        Text("$value", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            color = c.accent, modifier = Modifier.padding(start = 10.dp))
        if (canAllocate) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(26.dp).clip(RoundedCornerShape(4.dp)).background(c.amber.copy(alpha = 0.18f))
                    .border(1.dp, c.amber.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).clickable(onClick = onAllocate),
                contentAlignment = Alignment.Center,
            ) { Text("＋", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.amber) }
        }
    }
}

// Gesture-detection tunables (owner-tuned on the Pixel; motion is accelerometer g-force / gyro dps).
private const val POLL_MS = 50L            // sensor sampling cadence for gesture detection
private const val FLICK_MIN_G = 1.9f       // a sharp flick spikes |accel| this high…
private const val FLICK_GREAT_G = 3.2f     // …and this high is a flawless one
private const val SHAKE_FIRE = 6.0f        // integrated shake energy needed to commit a shake
private const val SHAKE_GREAT_PEAK = 1.4f  // peak jolt (|g−1|) that counts as a flawless shake
private const val SHAKE_DECAY = 0.85f      // per-sample decay of the shake integrator
private const val STILL_GYRO = 14f         // below this rotation (dps)…
private const val STILL_ACC = 0.07f        // …and |accel−1g| within this counts as "dead still"
private const val STILL_HOLD_MS = 1200L    // hold still this long to commit
private const val STILL_GREAT_MS = 2200L   // …this long is flawless
private const val PERF_FLOOR = 0.5f        // completing a gesture at all is never graded below this

/**
 * The encounter — CP2077-on-mobile. Each approach is a stat-gated action (`[STR 12] …`) you commit to by
 * PERFORMING it: shake for brute stats, flick for AGILITY, hold dead still for mind/nerve. The phone's
 * accelerometer/gyro grade the performance (0..1) → the check's die. No buttons.
 */
@Composable
private fun EncounterPanel(
    e: Encounter,
    ch: Character,
    telemetry: StateFlow<dev.mascwa.pulse.data.sensors.Telemetry>,
    c: NightwirePalette,
    onResolve: (Int, String?, Int) -> Unit,
) {
    val chems = Items.ALL.filter { it.kind == ItemKind.CHEM && (ch.inventory[it.id] ?: 0) > 0 }
    var selectedChem by remember(e.id) { mutableStateOf<String?>(null) }
    var resolved by remember(e.id) { mutableStateOf(false) }
    var meter by remember(e.id) { mutableStateOf(0f) }
    // HOLD STILL must be armed by a tap first — else a phone resting on a table would auto-commit the scan.
    var holdArmed by remember(e.id) { mutableStateOf(false) }

    // Which motions this scene accepts, from its choices' stats.
    val wanted: Set<GestureType> = remember(e.id) {
        e.choices.mapNotNull { it.stat?.let { s -> Gestures.forStat(s) } }.toSet()
    }
    val holdNeedsArm = GestureType.HOLD in wanted

    // Live sensor detection → resolve the matching approach with a performance-graded roll. Polls at a
    // fixed cadence (StateFlow is conflated, so a "hold still" wouldn't re-emit) — robust for all gestures.
    LaunchedEffect(e.id) {
        // No gesture-gated approach (all-safe scene, if content ever adds one) → resolve the first, automatically.
        if (wanted.isEmpty()) {
            kotlinx.coroutines.delay(500)
            resolved = true
            onResolve(0, selectedChem, Gestures.performanceRoll(0.7f))
            return@LaunchedEffect
        }
        var shakeE = 0f
        var shakePeak = 0f
        var stillMs = 0L
        while (!resolved) {
            val t = telemetry.value
            val g = t.accelG ?: 1f
            val gyro = abs(t.gyroDps ?: 0f)
            val jolt = abs(g - 1f)

            shakeE = shakeE * SHAKE_DECAY + jolt
            if (jolt > shakePeak) shakePeak = jolt
            // The still-timer only runs once the player has armed the hold — no accidental resting-on-a-table commit.
            if (holdArmed && gyro < STILL_GYRO && jolt < STILL_ACC) stillMs += POLL_MS else stillMs = 0

            meter = listOfNotNull(
                if (GestureType.FLICK in wanted) ((g - 1f) / (FLICK_GREAT_G - 1f)).coerceIn(0f, 1f) else null,
                if (GestureType.SHAKE in wanted) (shakeE / SHAKE_FIRE).coerceIn(0f, 1f) else null,
                if (GestureType.HOLD in wanted) (stillMs.toFloat() / STILL_HOLD_MS).coerceIn(0f, 1f) else null,
            ).maxOrNull() ?: 0f

            val done: Pair<GestureType, Float>? = when {
                GestureType.FLICK in wanted && g > FLICK_MIN_G ->
                    GestureType.FLICK to ((g - FLICK_MIN_G) / (FLICK_GREAT_G - FLICK_MIN_G)).coerceIn(0f, 1f)
                GestureType.SHAKE in wanted && shakeE > SHAKE_FIRE ->
                    GestureType.SHAKE to (shakePeak / SHAKE_GREAT_PEAK).coerceIn(0f, 1f)
                GestureType.HOLD in wanted && stillMs > STILL_HOLD_MS ->
                    GestureType.HOLD to ((stillMs - STILL_HOLD_MS).toFloat() / (STILL_GREAT_MS - STILL_HOLD_MS)).coerceIn(0f, 1f)
                else -> null
            }
            if (done != null) {
                resolved = true
                // The choice whose stat uses this gesture — if several, the one you're strongest in.
                val idx = e.choices.indices
                    .filter { i -> e.choices[i].stat?.let { Gestures.forStat(it) == done.first } == true }
                    .maxByOrNull { i -> ch.stat(e.choices[i].stat!!) } ?: 0
                onResolve(idx, selectedChem, Gestures.performanceRoll(done.second.coerceAtLeast(PERF_FLOOR)))
                break
            }
            kotlinx.coroutines.delay(POLL_MS)
        }
    }

    PipFrame(Modifier.fillMaxWidth(), accent = c.amber) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(e.title, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                color = c.amber, letterSpacing = 1.sp)
            Text(e.prompt, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink)
            if (chems.isNotEmpty()) {
                Text("PREP A CHEM", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 9.sp,
                    color = c.sky, letterSpacing = 1.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    chems.forEach { chem ->
                        val on = selectedChem == chem.id
                        ChemChip("${chem.name} +${chem.statBonusAmt} ${chem.statBonus?.letter ?: ' '}", on, c) {
                            selectedChem = if (on) null else chem.id
                        }
                    }
                }
            }
            e.choices.forEach { choice -> ApproachRow(choice, ch, selectedChem, c) }
            Spacer(Modifier.height(2.dp))
            SegBar(meter, if (resolved) c.positive else c.sky, c)
            when {
                resolved -> Text("⟳ COMMITTING…", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                    fontSize = 9.sp, color = c.positive, letterSpacing = 0.5.sp)
                // HOLD scene, not yet armed → require an explicit tap so a still phone can't auto-commit.
                holdNeedsArm && !holdArmed -> {
                    val others = (wanted - GestureType.HOLD).joinToString(" · ") { it.label }
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                            .border(1.dp, c.sky.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                            .background(c.sky.copy(alpha = 0.08f)).clickable { holdArmed = true }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        Text(
                            "◉ TAP TO ARM THE HOLD — then keep the phone dead still" +
                                if (others.isNotEmpty()) "   ·   or $others now" else "",
                            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 9.sp,
                            color = c.sky, letterSpacing = 0.5.sp,
                        )
                    }
                }
                holdNeedsArm && holdArmed -> Text(
                    "◉ ARMED — HOLD DEAD STILL" +
                        (wanted - GestureType.HOLD).joinToString(" · ") { it.label }.let { if (it.isNotEmpty()) "   ·   or $it" else "" },
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = c.amber, letterSpacing = 0.5.sp,
                )
                else -> Text(
                    "▸ PERFORM AN ACTION — " + wanted.joinToString(" · ") { it.label },
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = c.sky, letterSpacing = 0.5.sp,
                )
            }
        }
    }
}

@Composable
private fun ChemChip(label: String, on: Boolean, c: NightwirePalette, onClick: () -> Unit) {
    val col = if (on) c.sky else c.muted
    Box(
        Modifier.clip(RoundedCornerShape(3.dp)).border(1.dp, col.copy(alpha = if (on) 0.9f else 0.4f), RoundedCornerShape(3.dp))
            .background(c.sky.copy(alpha = if (on) 0.16f else 0.04f)).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 9.sp, color = if (on) c.sky else c.ink)
    }
}

/** A stat-gated approach, CP2077-style: `[STR 12] Heave it wide  ▸ SHAKE`, coloured by your odds. Read-only. */
@Composable
private fun ApproachRow(choice: Choice, ch: Character, selectedChem: String?, c: NightwirePalette) {
    val gate = choice.stat
    val chemBonus = if (gate != null) {
        val chem = selectedChem?.let { Items.byId(it) }
        if (chem != null && chem.kind == ItemKind.CHEM && chem.statBonus == gate && (ch.inventory[chem.id] ?: 0) > 0) chem.statBonusAmt else 0
    } else 0
    val bonus = if (gate != null) SpecialGame.perkStatBonus(ch, gate) + SpecialGame.gearStatBonus(ch, gate) + chemBonus else 0
    val effStat = if (gate != null) ch.stat(gate) + bonus else 0
    val tag = if (gate == null) "SAFE" else oddsLabel(effStat, choice.difficulty, ch.stat(Special.LUCK))
    val tagColor = when (tag) {
        "SURE", "SAFE" -> c.positive
        "LIKELY" -> c.accent
        "EVEN" -> c.ink
        "RISKY" -> c.amber
        else -> c.negative
    }
    val gesture = gate?.let { Gestures.forStat(it) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .border(1.dp, tagColor.copy(alpha = 0.45f), RoundedCornerShape(4.dp)).background(c.accent.copy(alpha = 0.05f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                (if (gate != null) "[${gate.display.take(3)} ${choice.difficulty}] " else "[SAFE] ") + choice.text,
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink,
            )
            Text(
                (gesture?.let { "▸ ${it.label}" } ?: "▸ automatic") + (if (bonus != 0) "   (${if (bonus > 0) "+" else ""}$bonus)" else ""),
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 9.sp,
                color = if (gesture != null) c.sky else c.muted, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(tag, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = tagColor, letterSpacing = 1.sp)
    }
}

/** The grind, made visible: what you've earned and what's next. Drives return play. */
@Composable
private fun AchievementsPanel(unlocked: Set<String>, metrics: GameMetrics, c: NightwirePalette) {
    PipFrame(Modifier.fillMaxWidth(), accent = c.positive) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ACHIEVEMENTS · ${unlocked.size}/${Achievements.ALL.size}", fontFamily = ChakraPetch,
                fontWeight = FontWeight.Bold, fontSize = 12.sp, color = c.positive, letterSpacing = 1.sp)
            Achievements.ALL.forEach { a -> AchievementRow(a, a.id in unlocked, metrics, c) }
        }
    }
}

@Composable
private fun AchievementRow(a: Achievement, done: Boolean, metrics: GameMetrics, c: NightwirePalette) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (done) "✓" else "○", fontFamily = JetBrainsMono, fontSize = 12.sp,
            color = if (done) c.positive else c.muted, modifier = Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                color = if (done) c.ink else c.muted)
            Text(a.desc, fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted)
            if (!done) {
                Spacer(Modifier.height(2.dp))
                SegBar(Achievements.progress(a, metrics), c.positive, c)
                Text("${metrics.value(a.metric).coerceAtMost(a.threshold)} / ${a.threshold}",
                    fontFamily = JetBrainsMono, fontSize = 7.sp, color = c.muted)
            }
        }
        val reward = rewardText(a)
        if (reward.isNotEmpty()) {
            Text(reward, fontFamily = JetBrainsMono, fontSize = 8.sp,
                color = if (done) c.amber else c.muted.copy(alpha = 0.6f), modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** The one-shot "you earned it" banner shown when an achievement clears. */
@Composable
private fun UnlockBanner(a: Achievement, c: NightwirePalette, onDismiss: () -> Unit) {
    PipFrame(Modifier.fillMaxWidth(), accent = c.positive) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("★ ACHIEVEMENT UNLOCKED", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                    fontSize = 10.sp, color = c.positive, letterSpacing = 1.sp)
                Text(a.name, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.ink)
                val reward = rewardText(a)
                if (reward.isNotEmpty()) Text("Reward · $reward", fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.amber)
            }
            Box(
                Modifier.clip(RoundedCornerShape(3.dp)).border(1.dp, c.muted.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                    .clickable(onClick = onDismiss).padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text("OK", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = c.muted)
            }
        }
    }
}

@Composable
private fun QuestCompleteBanner(q: Quest, c: NightwirePalette, onDismiss: () -> Unit) {
    PipFrame(Modifier.fillMaxWidth(), accent = c.amber) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("✓ QUEST COMPLETE", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                    fontSize = 10.sp, color = c.amber, letterSpacing = 1.sp)
                Text(q.title, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.ink)
                Text("Reward · +${q.rewardXp} XP · +${q.rewardCaps} caps", fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.amber)
            }
            Box(
                Modifier.clip(RoundedCornerShape(3.dp)).border(1.dp, c.muted.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                    .clickable(onClick = onDismiss).padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text("OK", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = c.muted)
            }
        }
    }
}

/** A compact "+40 XP, +30 caps, Field Medkit" reward summary for an achievement. */
private fun rewardText(a: Achievement): String = buildList {
    if (a.rewardXp > 0) add("+${a.rewardXp} XP")
    if (a.rewardCaps > 0) add("+${a.rewardCaps} caps")
    a.rewardItemId?.let { id -> Items.byId(id)?.let { add(it.name) } }
}.joinToString(", ")

/** The Pokémon-Go layer: real shops near you become traders/medics/… — buy, talk, and track your travel. */
@Composable
private fun WastelandPanel(
    locations: List<GameLocation>,
    travel: TravelStats,
    scanning: Boolean,
    gps: DeviceLocation?,
    ch: Character,
    c: NightwirePalette,
    onScan: () -> Unit,
    onBuy: (String, LocationKind) -> Unit,
    onTalk: (Encounter, LocationKind) -> Unit,
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    PipFrame(Modifier.fillMaxWidth(), accent = c.sky) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("WASTELAND MAP", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                color = c.sky, letterSpacing = 1.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TravelStat("WALKED", Geo.formatDistance(travel.distanceM), c)
                TravelStat("PLACES", "${travel.placesVisited}", c)
                TravelStat("PLAYED", formatPlayTime(travel.playMs), c)
            }
            GameButton(if (scanning) "SCANNING…" else "SCAN AREA ▸", c.sky, onScan)
            if (locations.isNotEmpty()) {
                WastelandMap(locations, gps, c, Modifier.fillMaxWidth().height(200.dp)) { id ->
                    selectedId = if (selectedId == id) null else id
                }
            }
            if (locations.isEmpty()) {
                Text(
                    if (gps == null) "No fix yet. Grant location and give it a moment."
                    else "Nothing scanned. Tap SCAN AREA to find real shops nearby.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                )
            } else {
                locations.forEach { loc ->
                    val dist = gps?.let { Geo.distanceMeters(it.latitude, it.longitude, loc.lat, loc.lon) }
                    LocationRow(loc, dist, selectedId == loc.id, c) {
                        selectedId = if (selectedId == loc.id) null else loc.id
                    }
                    if (selectedId == loc.id) {
                        // Pokémon-Go rule: you must physically BE at the shop (within reach) to trade/talk.
                        val here = LocationGate.isAtLocation(gps?.latitude, gps?.longitude, loc)
                        LocationSheet(loc, ch, here, dist, c, onBuy, onTalk)
                    }
                }
            }
        }
    }
}

/** The personalised quest board — your real tasks/interests/nearby places/day, as Fallout missions. */
@Composable
private fun QuestsPanel(quests: List<QuestView>, c: NightwirePalette) {
    PipFrame(Modifier.fillMaxWidth(), accent = c.amber) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("QUESTS", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                color = c.amber, letterSpacing = 1.sp)
            quests.forEach { v -> QuestCard(v, c) }
        }
    }
}

@Composable
private fun QuestCard(v: QuestView, c: NightwirePalette) {
    val q = v.quest
    val kindColor = when (q.kind) {
        QuestKind.MAIN -> c.amber
        QuestKind.SIDE -> c.ink
        QuestKind.DAILY -> c.positive
    }
    val frac = if (q.target > 0) (v.done.toFloat() / q.target).coerceIn(0f, 1f) else 1f
    val unit = if (q.goal == QuestGoal.WALK_DISTANCE) " m" else ""
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .border(1.dp, kindColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .background(kindColor.copy(alpha = 0.05f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("◆ ${q.kind.label}", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 8.sp,
                color = kindColor, letterSpacing = 1.sp)
            Spacer(Modifier.width(8.dp))
            Text(q.title, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = c.ink)
        }
        Text(q.brief, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.ink)
        Text(q.source, fontFamily = JetBrainsMono, fontSize = 7.sp, color = c.muted)
        SegBar(frac, if (v.complete) c.positive else kindColor, c)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (v.complete) "✓ COMPLETE" else "${v.done} / ${q.target}$unit",
                fontFamily = JetBrainsMono, fontSize = 8.sp, color = if (v.complete) c.positive else c.muted,
            )
            Text("${q.rewardXp} XP · ${q.rewardCaps} caps", fontFamily = ChakraPetch,
                fontWeight = FontWeight.Bold, fontSize = 8.sp, color = c.amber, letterSpacing = 0.5.sp)
        }
    }
}

@Composable
private fun TravelStat(label: String, value: String, c: NightwirePalette) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.ink)
        Text(label, fontFamily = JetBrainsMono, fontSize = 7.sp, color = c.muted, letterSpacing = 1.sp)
    }
}

@Composable
private fun LocationRow(loc: GameLocation, distanceM: Double?, open: Boolean, c: NightwirePalette, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .border(1.dp, if (open) c.sky.copy(alpha = 0.7f) else c.line, RoundedCornerShape(4.dp))
            .background(c.sky.copy(alpha = if (open) 0.1f else 0.04f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(loc.name, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = c.ink)
            Text("${loc.kind.title} · ${loc.kind.role}", fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted)
        }
        if (distanceM != null) {
            Text(Geo.formatDistance(distanceM), fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.sky)
        }
    }
}

@Composable
private fun LocationSheet(
    loc: GameLocation,
    ch: Character,
    atLocation: Boolean,
    distanceM: Double?,
    c: NightwirePalette,
    onBuy: (String, LocationKind) -> Unit,
    onTalk: (Encounter, LocationKind) -> Unit,
) {
    val npc = GameLocations.npcName(loc.kind, loc.id.hashCode())
    val repPts = SpecialGame.rep(ch, loc.kind)
    val tier = Reputation.tier(repPts)
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp, start = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(GameLocations.greeting(loc.kind, npc), fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.ink)
        if (!atLocation) {
            // Out of reach — show wares as a lure, but you have to walk there to deal.
            Text(
                "▸ TRAVEL HERE TO TRADE" + (distanceM?.let { " — ${Geo.formatDistance(it)} away" } ?: " — location unknown"),
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 9.sp,
                color = c.amber, letterSpacing = 0.5.sp,
            )
        }
        Text(
            "WARES · ${tier.label}${if (tier.discountPct > 0) " −${tier.discountPct}%" else ""}",
            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 9.sp,
            color = if (tier.discountPct > 0) c.positive else c.amber, letterSpacing = 1.sp,
        )
        GameLocations.stock(loc.kind).forEach { id ->
            Items.byId(id)?.let { item ->
                val price = Reputation.discountedPrice(item.value, repPts)
                // Buyable only when you're physically at the shop AND can afford it.
                ShopRow(item, price, atLocation && ch.caps >= price, c) { onBuy(id, loc.kind) }
            }
        }
        if (atLocation) {
            GameButton("TALK TO $npc", c.sky) { onTalk(GameLocations.conversation(loc.kind, npc), loc.kind) }
        } else {
            Text("Talk to $npc when you arrive.", fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted)
        }
    }
}

@Composable
private fun ShopRow(item: Item, price: Int, affordable: Boolean, c: NightwirePalette, onBuy: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(item.name, fontFamily = JetBrainsMono, fontSize = 10.sp, color = if (affordable) c.ink else c.muted)
            Text(item.desc, fontFamily = JetBrainsMono, fontSize = 7.sp, color = c.muted)
        }
        val col = if (affordable) c.amber else c.muted
        Box(
            Modifier.clip(RoundedCornerShape(3.dp)).border(1.dp, col.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                .background(col.copy(alpha = 0.1f))
                .then(if (affordable) Modifier.clickable(onClick = onBuy) else Modifier)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Text("BUY $price", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = col)
        }
    }
}

/** "1h 23m" / "12m" / "45s" from a play-time in ms. */
private fun formatPlayTime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "${s}s"
    }
}

/** The workbench: turn scavenged junk into gear/aid. Shows recipes you have at least one material for. */
@Composable
private fun WorkbenchPanel(ch: Character, c: NightwirePalette, onCraft: (String) -> Unit) {
    val relevant = Recipes.ALL.filter { r -> r.inputs.keys.any { (ch.inventory[it] ?: 0) > 0 } }
    PipFrame(Modifier.fillMaxWidth(), accent = c.amber) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("WORKBENCH", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                color = c.amber, letterSpacing = 1.sp)
            if (relevant.isEmpty()) {
                Text("Scavenge materials (scrap, wire, boards) to craft gear here.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
            } else {
                relevant.forEach { r -> RecipeRow(r, SpecialGame.canCraft(ch, r), c) { onCraft(r.id) } }
            }
        }
    }
}

@Composable
private fun RecipeRow(r: Recipe, craftable: Boolean, c: NightwirePalette, onCraft: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .border(1.dp, c.line, RoundedCornerShape(4.dp)).background(c.amber.copy(alpha = 0.05f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${r.name} → ${Items.byId(r.outputId)?.name ?: r.outputId}", fontFamily = ChakraPetch,
                fontWeight = FontWeight.Bold, fontSize = 11.sp, color = if (craftable) c.ink else c.muted)
            val needs = r.inputs.entries.joinToString(" + ") { (id, qty) -> "$qty× ${Items.byId(id)?.name ?: id}" } +
                (r.stat?.let { " · ${it.display.take(3)} ${r.minStat}" } ?: "")
            Text(needs, fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted, modifier = Modifier.padding(top = 1.dp))
        }
        val col = if (craftable) c.positive else c.muted
        Box(
            Modifier.clip(RoundedCornerShape(3.dp)).border(1.dp, col.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                .background(col.copy(alpha = 0.1f))
                .then(if (craftable) Modifier.clickable(onClick = onCraft) else Modifier)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Text("CRAFT", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = col, letterSpacing = 0.5.sp)
        }
    }
}

/** The companion: one hired ally who helps on every encounter. Hire (for caps), swap, or dismiss. */
@Composable
private fun CompanionPanel(ch: Character, c: NightwirePalette, onHire: (String) -> Unit, onDismiss: () -> Unit) {
    val active = ch.companion?.let { Companions.byId(it) }
    PipFrame(Modifier.fillMaxWidth(), accent = c.violet) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("COMPANION", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                color = c.violet, letterSpacing = 1.sp)
            if (active != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("● ${active.name}", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = c.violet)
                        Text(active.desc, fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted)
                    }
                    InvAction("DISMISS", c.negative) { onDismiss() }
                }
                Text("HIRE ANOTHER (swaps)", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = c.muted, letterSpacing = 1.sp)
            }
            Companions.ALL.filter { it.id != ch.companion }.forEach { comp ->
                CompanionHireRow(comp, ch.caps >= comp.cost, c) { onHire(comp.id) }
            }
        }
    }
}

@Composable
private fun CompanionHireRow(comp: Companion, affordable: Boolean, c: NightwirePalette, onHire: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .border(1.dp, c.line, RoundedCornerShape(4.dp)).background(c.violet.copy(alpha = 0.05f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(comp.name, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = if (affordable) c.ink else c.muted)
            Text(comp.desc, fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted)
        }
        val col = if (affordable) c.amber else c.muted
        Box(
            Modifier.clip(RoundedCornerShape(3.dp)).border(1.dp, col.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                .background(col.copy(alpha = 0.1f))
                .then(if (affordable) Modifier.clickable(onClick = onHire) else Modifier)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Text("HIRE ${comp.cost}", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = col, letterSpacing = 0.5.sp)
        }
    }
}

/** Daily objectives — three rotating goals a day, each claimable when done, feeding the play streak. */
@Composable
private fun DailyPanel(daily: DailyState, c: NightwirePalette, onClaim: (String) -> Unit) {
    PipFrame(Modifier.fillMaxWidth(), accent = c.positive) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("DAILY OBJECTIVES", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    color = c.positive, letterSpacing = 1.sp)
                if (daily.streak > 0) {
                    Text("▲ ${daily.streak}-DAY STREAK", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                        fontSize = 10.sp, color = c.amber, letterSpacing = 0.5.sp)
                }
            }
            daily.objectives.forEach { o -> DailyObjRow(o, daily.metrics, o.id in daily.claimed, c) { onClaim(o.id) } }
        }
    }
}

@Composable
private fun DailyObjRow(o: DailyObjective, m: TodayMetrics, claimed: Boolean, c: NightwirePalette, onClaim: () -> Unit) {
    val done = DailyObjectives.isComplete(o, m)
    val prog = DailyObjectives.progress(o, m)
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val reward = buildList {
                if (o.rewardXp > 0) add("+${o.rewardXp} XP")
                if (o.rewardCaps > 0) add("+${o.rewardCaps} caps")
                o.rewardItemId?.let { id -> Items.byId(id)?.let { add(it.name) } }
            }.joinToString(", ")
            Text(o.description, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                color = if (claimed) c.muted else c.ink)
            Spacer(Modifier.height(2.dp))
            SegBar((prog.toFloat() / o.target).coerceIn(0f, 1f), c.positive, c)
            Text("$prog / ${o.target}${if (reward.isNotEmpty()) "  ·  $reward" else ""}",
                fontFamily = JetBrainsMono, fontSize = 7.sp, color = c.muted, modifier = Modifier.padding(top = 1.dp))
        }
        Spacer(Modifier.width(8.dp))
        when {
            claimed -> Text("✓", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.positive)
            done -> InvAction("CLAIM", c.positive, onClaim)
            else -> Text("${(prog * 100 / o.target.coerceAtLeast(1))}%", fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted)
        }
    }
}

/** Faction standing: your reputation with each kind of wasteland shop, and the discount it earns. */
@Composable
private fun ReputationPanel(ch: Character, c: NightwirePalette) {
    PipFrame(Modifier.fillMaxWidth(), accent = c.sky) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("REPUTATION", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                color = c.sky, letterSpacing = 1.sp)
            LocationKind.entries.forEach { kind ->
                val pts = SpecialGame.rep(ch, kind)
                val tier = Reputation.tier(pts)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(kind.title, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.ink)
                    Text(
                        "${tier.label}${if (tier.discountPct > 0) " · −${tier.discountPct}% shops" else ""}",
                        fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                        color = if (tier == RepTier.NEUTRAL) c.muted else c.positive,
                    )
                }
            }
        }
    }
}

/** A rough pass-odds label for a stat check — how many of the 10 die faces would succeed. */
private fun oddsLabel(stat: Int, difficulty: Int, luck: Int): String {
    val passes = (1..SpecialGame.DIE).count { SpecialGame.check(stat, difficulty, luck, it).success }
    return when {
        passes >= 9 -> "SURE"
        passes >= 6 -> "LIKELY"
        passes == 5 -> "EVEN"
        passes >= 2 -> "RISKY"
        else -> "LONGSHOT"
    }
}

@Composable
private fun IdlePanel(resolution: Resolution?, c: NightwirePalette, onVenture: () -> Unit) {
    PipFrame(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (resolution != null) {
                val o = resolution.outcome
                Text(
                    if (resolution.crit) "CRITICAL SUCCESS" else if (resolution.success) "SUCCESS" else "FAILURE",
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp,
                    color = if (resolution.success) c.positive else c.negative,
                )
                Text(o.text, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink)
                val rewards = buildList {
                    if (o.xp > 0) add("+${o.xp} XP")
                    if (o.caps != 0) add("${if (o.caps > 0) "+" else ""}${o.caps} caps")
                    if (o.hp != 0) add("${if (o.hp > 0) "+" else ""}${o.hp} HP")
                    if (o.statPoint) add("+1 point")
                }.joinToString("    ")
                if (rewards.isNotBlank()) {
                    Text(rewards, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = c.amber)
                }
            } else {
                Text(
                    "The wasteland waits. Venture out — your S.P.E.C.I.A.L. decides how it goes.",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                )
            }
            GameButton(if (resolution != null) "VENTURE ON ▸" else "VENTURE OUT ▸", c.accent, onVenture)
        }
    }
}

@Composable
private fun DownedPanel(c: NightwirePalette, onRevive: () -> Unit) {
    PipFrame(Modifier.fillMaxWidth(), accent = c.negative) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("YOU WENT DOWN", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                color = c.negative, letterSpacing = 1.sp)
            Text(
                "The wasteland got the better of you. Patch up to venture again — it'll cost a quarter of your caps.",
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink,
            )
            GameButton("PATCH UP ✚", c.negative, onRevive)
        }
    }
}

@Composable
private fun GameButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.5.sp, color = color)
    }
}

/** One status-effect row: a tag glyph, the effect name (bold) and a short description, banded. */
@Composable
private fun EffectRow(e: StatusEffect, c: NightwirePalette) {
    Row(
        Modifier.fillMaxWidth()
            .drawBehind {
                drawRect(c.accent.copy(alpha = 0.08f))
                drawLine(c.accent.copy(alpha = 0.35f), Offset(0f, size.height), Offset(size.width, size.height), 1.2.dp.toPx())
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(e.tag, fontFamily = JetBrainsMono, fontSize = 14.sp, color = if (e.good) c.accent else c.amber,
            modifier = Modifier.width(26.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                color = if (e.good) c.ink else c.amber, letterSpacing = 0.8.sp)
            Text(e.desc, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, modifier = Modifier.padding(top = 1.dp))
        }
    }
}
