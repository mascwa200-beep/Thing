package dev.mascwa.pulse.desktop.settings

import dev.mascwa.pulse.desktop.AppPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * A phone this desktop has paired with.
 *
 * [identitySpki] is the phone's **public** key, Base64-encoded — deliberately the only credential-shaped
 * thing stored, and it is not secret. Nothing here can authenticate as the phone or the desktop, so this
 * file leaking costs privacy about which devices exist, not control of them. The desktop's own private key
 * lives in a separate PKCS12 (see `DesktopIdentity`), and the pairing code is never persisted at all.
 */
@Serializable
data class PairedPhone(
    val name: String,
    val host: String,
    val port: Int,
    val identitySpki: String,
    val pairedAtMs: Long = 0,
)

/** Everything the desktop shell persists across launches. Deliberately small in Phase A — grows alongside
 *  each ported vertical (News, Markets, …), mirroring how the Android app's own `AppSettings` blob grew
 *  feature by feature rather than being pre-designed all at once. */
@Serializable
data class DesktopSettings(
    val windowWidth: Int = 1280,
    val windowHeight: Int = 800,
    /** Paired phones, most recently paired last. Empty until the user completes a pairing. */
    val pairedPhones: List<PairedPhone> = emptyList(),
    /** Last address typed into the pairing field, so the box is not blank every launch. */
    val lastRemoteHost: String = "",
    /** News category last viewed, so the app reopens where it was left. */
    val newsCategory: String = "TOP",
    /** Knowledge-library category last browsed — same precedent as [newsCategory]. */
    val libraryCategory: String = "",

    /**
     * Which Lemmy and Mastodon servers the discussion tabs read.
     *
     * Both are federated: there is no single site to point at, and the one you choose decides what you
     * see. The defaults are the largest general-purpose instance of each, which is what someone who has
     * never thought about it would want.
     */
    val lemmyInstance: String = "lemmy.world",
    val mastodonInstance: String = "mastodon.social",

    /**
     * Offer live TV channels from the volunteer-maintained public catalogue as well as the handful of
     * broadcasters' own feeds the app ships with.
     *
     * ⚠️ Default OFF, and a switch rather than a silent merge on purpose: that catalogue is of mixed
     * origin and includes unauthorised restreams of channels that are not free to watch. Whose call
     * that is, is the user's.
     */
    val communityChannels: Boolean = false,

    /**
     * A GitHub token with read access, so the app can see its own releases.
     *
     * ⚠️ The repository is private, so there is no anonymous way to read the installer — the phone asks
     * for the same thing for the same reason. **Stored in plain text** in this settings file: the phone
     * puts its copy behind the Titan M2 secure element, and a desktop has no equivalent this module can
     * reach, so the file's own permissions in your user profile are the whole protection. Worth knowing
     * before pasting a broadly-scoped token in; read-only is all this needs.
     */
    val githubToken: String = "",

    /** Look for a newer build on launch. The check is one request and never installs anything by itself. */
    val autoCheckUpdates: Boolean = true,

    // ----- Location & units -------------------------------------------------------------------
    //
    // ⚠️ A desktop has no GPS. Where it is has to be either guessed from the internet connection or
    // typed, and the guess is genuinely unreliable — see `IpLocationService`, which measured three
    // services disagreeing by two thousand miles from one machine because the traffic left through a
    // proxy. So: guess once to fill the field in, then let it be corrected, and never silently
    // re-guess over an answer someone typed.

    /** Latitude in degrees. `null` means nothing has established it yet — distinct from 0.0, which is
     *  a real place in the Gulf of Guinea and the classic wrong answer for "unset". */
    val latitude: Double? = null,
    val longitude: Double? = null,

    /** "San Francisco, United States" — what to show, so the setting reads as a place not a number pair. */
    val placeLabel: String = "",

    /** True once a person has edited the place by hand. While this is false the app may fill the fields
     *  in from the connection; once it is true it must not, ever — overwriting a correction is the one
     *  behaviour that would make the setting useless. */
    val placeSetByHand: Boolean = false,

    /** Celsius when false. Named for the departure from the norm rather than as an enum, because there
     *  are exactly two and a boolean here reads better at the call site than `Units.IMPERIAL`. */
    val fahrenheit: Boolean = false,

    /** Miles and feet when true, kilometres and metres when false. Separate from [fahrenheit] on
     *  purpose: plenty of people want °C with miles, and one combined "imperial" switch would deny it. */
    val miles: Boolean = false,

    /** 12-hour clock when true. */
    val twelveHourClock: Boolean = false,

    /**
     * ISO-3166 alpha-2, for the figures that belong to a country rather than a coordinate — the
     * economic indicators and the fuel prices.
     *
     * ⚠️ Separate from [latitude]/[longitude] on purpose, and not derived from them. Somebody may
     * well want the weather where they are sitting and the economy of somewhere else, and deriving
     * one from the other would take that away while looking like a convenience. The economy screen
     * was hardcoded to "US" until this existed.
     */
    val countryCode: String = "US",

    /**
     * An optional EIA key, which unlocks weekly US retail pump prices on the fuel screen.
     *
     * ⚠️ Stored in plain text, exactly like [githubToken] and for the same reason: this machine has
     * no secure element to put it behind, and the settings page says so rather than implying a
     * protection that is not there. It is a free, read-only, rate-limited key.
     */
    val eiaKey: String = "",

    // ----- Data & refresh ---------------------------------------------------------------------

    /** How often a live feed re-fetches while its screen is open, in minutes. ⚠️ Only while OPEN —
     *  off-screen there is no timer at all, which is what makes this cheap. */
    val refreshMinutes: Int = 5,

    /** Re-fetch the moment a screen is opened if what it holds is older than [refreshMinutes]. Off
     *  means it shows what it has until the timer comes round, which is quieter on a metered link. */
    val refreshOnOpen: Boolean = true,

)

private val defaultJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * JSON-file-backed persistence for [DesktopSettings] — the desktop counterpart to the Android app's
 * DataStore-backed stores (`ProfileStore`/`TaskStore`/…): in-memory state is authoritative, writes are
 * debounced, and a read failure never overwrites what's already on disk (mirrors the Android
 * `SettingsRepository`'s "never clobber an undecodable blob" lesson). Backed by a plain JSON file under the
 * OS's conventional per-user app-data directory instead of DataStore, which has no desktop target.
 */
class DesktopSettingsStore(
    private val path: Path = defaultSettingsPath(),
    private val json: Json = defaultJson,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val mutex = Mutex()
    private var cached: DesktopSettings? = null
    private var flushJob: Job? = null

    private val _settingsFlow = MutableStateFlow(DesktopSettings())
    val settingsFlow: StateFlow<DesktopSettings> = _settingsFlow.asStateFlow()

    private suspend fun ensureLoaded(): DesktopSettings = mutex.withLock {
        cached ?: run {
            val loaded = readFromDisk() ?: DesktopSettings()
            cached = loaded
            _settingsFlow.value = loaded
            loaded
        }
    }

    suspend fun current(): DesktopSettings = ensureLoaded()

    suspend fun update(transform: (DesktopSettings) -> DesktopSettings) {
        ensureLoaded()
        mutex.withLock {
            val next = transform(cached ?: DesktopSettings())
            cached = next
            _settingsFlow.value = next
        }
        scheduleFlush()
    }

    /** Force a buffered write to disk now. */
    suspend fun flushNow() {
        flushJob?.cancel()
        flush()
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
    }

    /** The disk IO runs INSIDE the lock (not just the snapshot read) so two flushes can never race on the
     *  same `.tmp` sibling file — [flushNow]'s `flushJob?.cancel()` can't interrupt a job that's already
     *  past its `delay()` and into this blocking section, so without this the debounced and forced paths
     *  could both reach here at once. A small local JSON write is fast enough that serializing it is a
     *  fine trade for never risking a torn/interleaved write. */
    private suspend fun flush() {
        mutex.withLock {
            val snapshot = cached ?: return@withLock
            withContext(Dispatchers.IO) {
                runCatching {
                    path.parent?.let(Files::createDirectories)
                    val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
                    Files.writeString(tmp, json.encodeToString(DesktopSettings.serializer(), snapshot))
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    /** A missing or malformed file on disk is left untouched (never overwritten with defaults) until the
     *  next successful [update] — same discipline as the Android `SettingsRepository` lesson: a transient
     *  read failure must never erase what's already saved. */
    private suspend fun readFromDisk(): DesktopSettings? = withContext(Dispatchers.IO) {
        runCatching {
            if (!Files.exists(path)) return@runCatching null
            json.decodeFromString(DesktopSettings.serializer(), Files.readString(path))
        }.getOrNull()
    }

    companion object {
        private const val FLUSH_DELAY_MS = 800L

        /** `<AppPaths.dataDir>/settings.json` — the shared per-user Pulse data directory every desktop
         *  store resolves under (see [dev.mascwa.pulse.desktop.AppPaths] for the OS-specific base). */
        fun defaultSettingsPath(): Path = AppPaths.dataDir.resolve("settings.json")
    }
}
