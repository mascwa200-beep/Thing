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

Plus:

- **Push notifications** — breaking-news alerts, market/price threshold alerts,
  severe-weather alerts, and a daily digest, all via a `WorkManager` background
  job. Per-channel toggles, quiet hours, and digest time in Settings.
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

## Build & sideload

You need **Android Studio** (latest stable). The first sync downloads the
Android SDK platform 35 and build tools automatically.

```bash
# Debug APK (easiest to sideload — signed with the debug key)
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Release APK (minified). Without a keystore it falls back to the debug key,
# which is fine for personal sideloading.
./gradlew assembleRelease
```

Install on the device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

…or in Android Studio: **Run ▶** with the Pixel 10 Pro XL connected, or
**Build ▸ Build Bundle(s) / APK(s) ▸ Build APK(s)**.

### Optional release signing

Fill these in `gradle.properties` (or pass as `-P` flags) to produce a
properly signed release APK:

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
