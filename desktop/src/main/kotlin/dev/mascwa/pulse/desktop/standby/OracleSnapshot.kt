package dev.mascwa.pulse.desktop.standby

import dev.mascwa.pulse.core.telemetry.EmergencyNews
import dev.mascwa.pulse.core.telemetry.OracleMover
import dev.mascwa.pulse.core.telemetry.OracleSignals
import dev.mascwa.pulse.desktop.study.localDayIndex
import dev.mascwa.pulse.data.markets.MarketsRepository
import dev.mascwa.pulse.desktop.news.NewsCategory
import dev.mascwa.pulse.desktop.news.NewsRepository
import dev.mascwa.pulse.data.space.SpaceWeatherRepository
import dev.mascwa.pulse.data.weather.WeatherRepository
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.desktop.study.StudyStore
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Everything this machine can tell the Oracle about right now.
 *
 * ⚠️ **Extracted so there is exactly one of it.** Four surfaces now want this — the Advisories
 * screen, Home, the standby display's live HUD, and the two standalone processes that render the
 * screensaver and the lock-screen image. The screen's own KDoc already states the reason in full:
 * two independent snapshots taken seconds apart disagree about which insight ranks first, because
 * the ranking is a function of the moment, and the surfaces would then be telling you different
 * things about one machine. Copying this would have made that certain rather than merely possible —
 * and a duplicated rule is a mistake this project has corrected five times.
 *
 * Shaped after the phone's `OracleEngine.snapshot`: every read is wrapped, every failure leaves its
 * field at the neutral default, and **nothing is forced**. These are warm reads of caches the other
 * screens have already filled; a display that made six live requests every time it drew would cost
 * more than it is worth and would rate-limit the screens that actually present the data.
 */
suspend fun gatherOracleSignals(
    settings: DesktopSettingsStore,
    weather: WeatherRepository,
    markets: MarketsRepository,
    space: SpaceWeatherRepository,
    news: NewsRepository,
    study: StudyStore,
): OracleSignals {
    val now = System.currentTimeMillis()
    val local = LocalDateTime.now()
    val prefs = runCatching { settings.current() }.getOrNull()
    val lat = prefs?.latitude
    val lon = prefs?.longitude

    val wx = if (lat != null && lon != null) {
        runCatching {
            weather.fetch(lat, lon, prefs.placeLabel.ifBlank { "Here" }, force = false).data
        }.getOrNull()
    } else {
        null
    }

    // Tonight's low, taken as the minimum of the next twelve hourly readings rather than from a
    // daily figure. A daily minimum belongs to a calendar day, so by the evening today's is already
    // behind you and tomorrow's covers a night that has not started.
    //
    // ⚠️ The forecast's own zone, not this machine's. Open-Meteo returns hourly stamps in the
    // requested location's timezone, and comparing them against a local clock would be an hour or
    // ten out for anyone reading a forecast somewhere they are not.
    val overnightLow = wx?.let { data ->
        runCatching {
            val zone = ZoneId.of(data.timezone)
            val here = LocalDateTime.now(zone)
            data.hourly
                .dropWhile { LocalDateTime.parse(it.timeIso) < here }
                .take(12)
                .mapNotNull { it.temperatureC }
                .minOrNull()
        }.getOrNull()
    }

    val movers = runCatching {
        markets.fetchAll(force = false).data.mapNotNull { q ->
            q.changePercent?.let { OracleMover(name = q.label, changePct = it, onWatchlist = true) }
        }
    }.getOrDefault(emptyList())

    val kp = runCatching { space.fetch(force = false).data.kp }.getOrNull()

    val emergency = runCatching {
        news.headlines(NewsCategory.TOP, force = false).getOrNull()?.data
            ?.maxByOrNull { EmergencyNews.severity(it.title, it.summary) }
            ?.takeIf { EmergencyNews.isMajor(it.title, it.summary) }?.title
    }.getOrNull()

    // The study deck. Warm reads on a store that is already loaded, and every failure leaves the
    // field at its neutral default — so someone who has never studied hears nothing from it.
    val progress = runCatching { study.progress() }.getOrNull()
    val due = runCatching { study.dueCount() }.getOrDefault(0)
    val today = localDayIndex(now)
    val studiedToday = progress != null && progress.lastStudiedAtMs > 0L &&
        localDayIndex(progress.lastStudiedAtMs) == today
    val shaky = runCatching { study.weakestGuide() }.getOrNull()

    return OracleSignals(
        nowMs = now,
        hourOfDay = local.hour,
        minuteOfDay = local.hour * 60 + local.minute,
        // java.time counts Monday as 1, which is the convention the core states.
        dayOfWeek = local.dayOfWeek.value,
        lat = lat,
        lon = lon,
        placeName = prefs?.placeLabel?.ifBlank { null },
        // ⚠️ Left at rest, and that is a measurement rather than a default: a tower PC is not going
        // anywhere. The rules that ask whether you are settled are therefore correct here, which is
        // why the study advisories can fire at all.
        tempC = wx?.current?.temperatureC,
        precipChancePct = wx?.daily?.firstOrNull()?.precipProbabilityMax,
        uvIndex = wx?.daily?.firstOrNull()?.uvIndexMax,
        windKmh = wx?.current?.windKmh,
        humidityPct = wx?.current?.humidity,
        dewPointC = wx?.current?.dewPointC,
        gustKmh = wx?.current?.gustKmh,
        overnightLowC = overnightLow,
        movers = movers,
        emergencyHeadline = emergency,
        kpIndex = kp,
        reviewsDue = due,
        studyStreakDays = progress?.streakDays ?: 0,
        studiedToday = studiedToday,
        shakyGuideTitle = shaky?.first,
        shakyGuideDetail = shaky?.second,
    )
}
