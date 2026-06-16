# Pulse

An in-depth personal news, markets, economy and weather dashboard built
**exclusively for the Google Pixel 10 Pro XL** and distributed by sideload.
Every data source is **free, public, and keyless by default** — the app works
fully out of the box with no signup, and optional free API keys can be added in
Settings to unlock richer sources.

> Single-user, sideload-only build. Not intended for the Play Store.

---

## What's inside

| Section | Source (keyless) | Optional key |
|---|---|---|
| **News / Politics / Pop culture / Tech** | Google News RSS (topic + search) · Hacker News API (tech) · your own RSS feeds | NewsAPI.org |
| **Markets** (indices, stocks, forex, commodities) | Stooq daily CSV (price, daily change, sparkline) | Finnhub |
| **Crypto** | CoinGecko public API | — |
| **Economy / Inflation** | World Bank Open Data (CPI, GDP, unemployment, debt, …) | FRED |
| **Fuel & Energy** | Stooq energy futures (Brent/WTI/NatGas/RBOB/Heating oil) + World Bank national pump prices | EIA (US weekly retail) |
| **Weather** | Open-Meteo forecast + geocoding + air quality (global) | OpenWeatherMap |

Plus hubs reached from the **GRID** tab:

- **SKY** — offline compass (true north via the WMM), space weather (NOAA SWPC
  Kp/solar wind/aurora), and orbital bodies (live ISS, sun/moon, planets, NEOs).
- **TACNET** — a real-time cyberpunk hub:
  - **Radar** — a phosphor scope with a rotating sweep, range rings
    (50/100/250/500 km), bearing ticks and a crosshair, plotting **real** contacts
    around your live GPS: **live aircraft** from keyless community ADS-B
    (`adsb.lol` → `adsb.fi` fallback), the **live ISS**, and **nearby earthquakes**
    (USGS). Blips ping as the sweep passes; tap one for callsign/altitude/speed.
    Auto-refreshes; offline shows the last picture with a `LINK LOST` marker.
  - **Telemetry** — a fully-offline vitals console: barometer→altitude,
    magnetometer, accel/gyro tilt, light, plus battery/network/memory and GPS,
    with live gauges and a scrolling data-stream log.
- **SURVIVE** — nearest help (hospitals/shelters/food banks/radio towers via OSM),
  offline survival guides & tools, and an SOS centre.
- **WIRE+** — social discovery (Lemmy/Mastodon/Hacker News), a keyless search bar,
  and an in-app image search.

Plus:

- **Global HUD strip** — an always-on cockpit bar (clock · GPS-lock · link ·
  battery · live Kp) under every screen; toggle in Settings.
- **Push notifications** — breaking-news alerts, market/price threshold alerts,
  severe-weather, geomagnetic-storm, nearby-incident, **overhead-flight**, and a
  daily digest, all via a `WorkManager` background job. Per-channel toggles,
  quiet hours, and digest time in Settings.
- **Customizable settings** — theme & Material You colour, region/units/currency,
  refresh interval, reorderable Home dashboard, editable watchlist, custom RSS
  feeds, muted keywords, optional API keys, cache management.
- **Offline support** — every screen caches its last good payload to disk and
  shows it instantly (with an "offline" banner) when the network is unavailable.

## Tech stack

- Kotlin · Jetpack Compose · Material 3 (Material You dynamic colour)
- MVVM + repositories · Coroutines/Flow
- Manual DI (no annotation processors → robust Gradle builds)
- OkHttp + kotlinx.serialization (JSON, RSS/XML, CSV) · Coil (images)
- DataStore (settings) · WorkManager (background refresh & notifications)
- `minSdk 31`, `targetSdk 35`, `compileSdk 35`

## Get the APK onto your Pixel 10 Pro XL

There are two ways to get an installable `app-debug.apk`. **Route A needs no
tools at all.**

### Route A — download the prebuilt APK from CI (no Android Studio)

Every push is compiled by GitHub Actions, which uploads a ready-to-install APK.

1. On GitHub, open the repo ▸ **Actions** tab ▸ the latest green **Android Build**
   run (for this branch).
2. Scroll to **Artifacts** and download **`pulse-debug-apk`** — it's a `.zip`
   containing `app-debug.apk`.
3. Unzip it to get `app-debug.apk`, then jump to **Sideloading** below.

> Artifacts expire ~90 days after the run; just re-run the workflow (Actions ▸
> the run ▸ **Re-run jobs**) to get a fresh one.

### Route B — build it yourself in Android Studio

Open the project in **Android Studio** (latest stable); the first Gradle sync
downloads the Android SDK platform 35 + build tools automatically. Then either
**Run ▶** with the phone connected, or **Build ▸ Build APK(s)**, or from a
terminal:

```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

## Sideloading onto the phone

**Easiest — over USB with adb** (Android Studio installs adb for you):

1. On the Pixel: **Settings ▸ About phone**, tap **Build number** 7× to unlock
   Developer options, then **Settings ▸ System ▸ Developer options ▸ USB
   debugging = on**.
2. Plug the phone into the computer, accept the "Allow USB debugging?" prompt.
3. Run:
   ```bash
   adb install -r app-debug.apk
   ```
   The app ("Pulse") appears in your launcher.

**No computer — install the file directly on the phone:**

1. Copy `app-debug.apk` to the phone (Drive, email to yourself, USB transfer,
   or download the CI artifact zip in Chrome and extract it with the Files app).
2. Tap the APK. Android will ask to allow your file manager / browser to
   **install unknown apps** — toggle it on, then **Install**.
3. Open **Pulse** from the launcher.

> This is a personal **debug** build, signed with the standard debug key — which
> is exactly what you want for sideloading to your own device. For a properly
> signed release build, see *Optional release signing* below.

### Optional release signing

Fill these in `gradle.properties` (or pass as `-P` flags) to produce a
properly signed release APK (`./gradlew assembleRelease`):

```
PULSE_RELEASE_STORE_FILE=/path/to/keystore.jks
PULSE_RELEASE_STORE_PASSWORD=…
PULSE_RELEASE_KEY_ALIAS=…
PULSE_RELEASE_KEY_PASSWORD=…
```

## Device exclusivity

Pulse is built for the Pixel 10 Pro XL. On launch it checks `Build.MODEL` /
`Build.DEVICE` against the target (`BuildConfig.TARGET_DEVICE_MODEL`). Because
Google's exact model string can vary by firmware/region, a mismatch shows a
dismissible notice ("Continue anyway") rather than hard-locking — so you, the
sole user, are never locked out if the reported string differs. The target
string lives in `app/build.gradle.kts` if you ever need to adjust it.

## Adding optional free API keys

All keys are optional and pasted in **Settings ▸ Optional API keys**:

- **NewsAPI.org** — richer, source-filtered headlines (free dev tier).
- **FRED** — high-frequency US economic series.
- **EIA** — US weekly retail gasoline/diesel prices (shown on the Fuel screen).
- **Finnhub** — real-time US equity quotes.
- **OpenWeatherMap** — alternative weather provider.

## Project layout

```
app/src/main/java/dev/mascwa/pulse/
├── core/          networking (OkHttp, RSS/CSV parsers), disk cache, utils, device gate
├── data/          settings + per-domain models & repositories (news, markets, economy, fuel, weather)
├── di/            manual DI container + ViewModel factory
├── notifications/ channels, notifier, WorkManager refresh worker, boot receiver
├── feature/       Compose screens + ViewModels per section + shared UI components
├── navigation/    routes & bottom-nav destinations
└── ui/            theme, app scaffold, device-gate screen
```

All information surfaced by Pulse comes from free, publicly accessible sources.
