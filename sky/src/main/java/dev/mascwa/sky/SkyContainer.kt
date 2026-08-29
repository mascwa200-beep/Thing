package dev.mascwa.sky

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.telemetry.SkyBudget
import dev.mascwa.pulse.crash.CrashReporter
import dev.mascwa.pulse.crash.CrashUploader
import dev.mascwa.pulse.data.sky.ConstellationCatalog
import dev.mascwa.pulse.data.sky.DeepSkyCatalog
import dev.mascwa.pulse.data.sky.DeepStarCatalog
import dev.mascwa.pulse.data.sky.MilkyWayCatalog
import dev.mascwa.pulse.data.sky.StarCatalog
import dev.mascwa.pulse.data.update.SelfUpdate
import dev.mascwa.pulse.data.update.UpdateRepository
import dev.mascwa.pulse.device.DeviceProbeReader
import dev.mascwa.pulse.feature.sky.SkyMapViewModel
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Everything this application owns: five catalogue readers, a sensor adapter, three remembered
 * preferences, the self-updater and the fault reporter.
 *
 * ⚠️ **Every member is `by lazy`, so constructing this opens no file and touches no sensor.** The
 * deep catalogue memory-maps twenty-five megabytes on its first read and the bright one parses eight
 * thousand rows; doing either during `Application.onCreate` would put both in front of the first
 * frame for no reason. The view model asks for them when it loads, off the main thread.
 *
 * ⚠️ **One instance for the process, held by [SkyApplication].** Two containers would mean two
 * `DeepStarCatalog`s each holding their own mapping of the same file — twice the address space for
 * one catalogue, and two mutexes guarding nothing in common. The activity reads this one rather
 * than building its own, which is the mistake the nutrition application's container records having
 * made.
 */
class SkyContainer(context: Context) {

    /**
     * ⚠️ **`applicationContext`, not whatever was handed in.** Every member below outlives any
     * screen — the catalogue mapping, the sensor listener, the updater — so holding an Activity
     * here would leak one for the life of the process. [SkyApplication] does pass the application
     * today, which is exactly the sort of thing that stays true until somebody writes a second
     * caller; the same discipline the nutrition container states.
     *
     * ⚠️ Named `appContext` rather than shadowing the parameter, and that is not style. A
     * constructor parameter stays in scope through every property initializer, so a property called
     * `context` would leave `by lazy { StarCatalog(context) }` reading the PARAMETER — capturing
     * exactly the reference this line exists to discard, and compiling perfectly while doing it.
     */
    private val appContext: Context = context.applicationContext

    val starCatalog by lazy { StarCatalog(appContext) }
    val deepStarCatalog by lazy { DeepStarCatalog(appContext) }
    val constellationCatalog by lazy { ConstellationCatalog(appContext) }
    val deepSkyCatalog by lazy { DeepSkyCatalog(appContext) }
    val milkyWayCatalog by lazy { MilkyWayCatalog(appContext) }

    /**
     * Where this phone is and where it is aimed.
     *
     * ⚠️ Held here rather than made per screen, because it registers a sensor listener and a second
     * one would leave the first arming the hardware behind a control that reads as off — the same
     * shape [SkyHardware.startAttitude] is guarded against internally.
     */
    val hardware by lazy { SkyHardware(appContext) }

    /** The token, the one-at-a-time install guard, and whether faults are sent on. */
    val settings by lazy { SkySettings(appContext) }

    /**
     * How much machine this is.
     *
     * ⚠️ Already a dependency of this module through `:core:update`, so this costs no new artifact —
     * and it was the thing the potato pass left wired to nothing here. Lazy, because probing makes
     * five binder calls and a content-provider query and nothing needs the answer until the map is
     * built.
     */
    val deviceProbe by lazy { DeviceProbeReader(appContext) }

    /**
     * This application keeping itself current.
     *
     * ⚠️ **The state machine is [SelfUpdate] in the shared module, not a copy.** Three applications
     * now update themselves from this repository's releases; that class's own KDoc makes the
     * argument for why a third copy of an install state machine is not a duplication worth having.
     * What is decided here is only the four facts that genuinely differ: which release, where the
     * token lives, where the guard lives, and — [companionPackage] left unset — that nothing else
     * on this phone installs this app, so it is on its own.
     *
     * ⚠️ **Its own `OkHttpClient`, with no disk cache.** A cache would put GitHub's own
     * `max-age=60` between this app and the answer to "is there a newer build", which is the exact
     * defect the LCARS updater had to fix with a `Cache-Control: no-cache` header. Nothing else in
     * this application makes a request, so there is no shared client to reuse and none to build.
     */
    val updates: SelfUpdate by lazy {
        SelfUpdate(
            appContext,
            UpdateRepository(
                appContext,
                http,
                tag = UpdateRepository.SKY_TAG,
                workflow = UpdateRepository.SKY_WORKFLOW,
                currentVersionCode = BuildConfig.VERSION_CODE,
                currentVersionName = BuildConfig.VERSION_NAME,
                token = { settings.token() },
            ),
            saveToken = { settings.setToken(it) },
            pendingInstall = { settings.pendingInstall() },
            setPendingInstall = { settings.setPendingInstall(it) },
        )
    }

    /**
     * What went wrong, recorded before it can be lost.
     *
     * ⚠️ **[SkyApplication] said this would arrive with the updater and this is it.** A reporter on
     * its own would have recorded faults into a directory with no way to deliver them and no screen
     * to read them from; the token that makes the updater work is the same token that makes a
     * report deliverable, which is why the two belong in one commit rather than one being hinted at
     * ahead of the other.
     */
    val crashReporter: CrashReporter by lazy {
        CrashReporter(
            appContext,
            appLabel = "Star Map",
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
        )
    }

    val crashUploader: CrashUploader by lazy {
        CrashUploader(
            appContext,
            crashReporter,
            // ⚠️ Its own stream, so reports from three applications land in three places on the
            // `debug-reports` branch rather than interleaving into one file nobody can attribute.
            stream = "sky",
            appLabel = "Star Map",
            buildLabel = "${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE})",
            token = { settings.token() },
            autoSendEnabled = { settings.autoSendReports() },
            // ⚠️ The token is the only credential this application holds, and a report that carried
            // it would hand a repository token to whoever reads the report. Redacted by exact value
            // on top of the shape patterns.
            secrets = { listOfNotNull(settings.token()) },
        )
    }

    private val http: HttpClient by lazy {
        HttpClient(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build(),
            Json { ignoreUnknownKeys = true },
        )
    }

    /**
     * ⚠️ A factory rather than `viewModels()` with a no-argument constructor, because
     * [SkyMapViewModel] takes eight dependencies and there is nowhere else to hand them to it. The
     * `@Suppress` is the standard shape for this API: the cast is checked one line above it.
     */
    val viewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SkyMapViewModel::class.java)) {
                "SkyContainer builds only SkyMapViewModel, not ${modelClass.name}"
            }
            return SkyMapViewModel(
                starCatalog,
                deepStarCatalog,
                constellationCatalog,
                deepSkyCatalog,
                milkyWayCatalog,
                hardware,
                settings,
                // ⚠️ Measured once, when the view model is built, rather than per frame: probing
                // makes five binder calls and a content-provider query, and the answer to "how much
                // machine is this" does not change while somebody looks at the sky. `durableBudget`
                // is DeviceClass's own name for exactly this distinction and the tier comes from
                // the same reader.
                SkyBudget.forTier(deviceProbe.tier()),
            ) as T
        }
    }
}
