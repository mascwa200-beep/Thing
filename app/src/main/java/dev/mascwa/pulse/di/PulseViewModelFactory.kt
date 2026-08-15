package dev.mascwa.pulse.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.mascwa.pulse.feature.compass.CompassViewModel
import dev.mascwa.pulse.feature.economy.EconomyViewModel
import dev.mascwa.pulse.feature.fuel.FuelViewModel
import dev.mascwa.pulse.feature.sky.OrbitalViewModel
import dev.mascwa.pulse.feature.sky.SpaceWeatherViewModel
import dev.mascwa.pulse.feature.sos.SosViewModel
import dev.mascwa.pulse.feature.survive.GuidesViewModel
import dev.mascwa.pulse.feature.survive.PlacesViewModel
import dev.mascwa.pulse.feature.survive.ToolsViewModel
import dev.mascwa.pulse.feature.home.HomeViewModel
import dev.mascwa.pulse.feature.markets.MarketsViewModel
import dev.mascwa.pulse.feature.news.NewsViewModel
import dev.mascwa.pulse.feature.settings.SettingsViewModel
import dev.mascwa.pulse.feature.weather.WeatherViewModel

/** Manual ViewModel factory backed by the [AppContainer]. */
class PulseViewModelFactory(private val c: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val vm: ViewModel = when {
            modelClass.isAssignableFrom(HomeViewModel::class.java) ->
                HomeViewModel(
                    c.newsRepository, c.marketsRepository, c.weatherRepository,
                    c.economyRepository, c.fuelRepository, c.locationProvider, c.settingsRepository,
                    c.orbitalRepository, c.spaceWeatherRepository, c.radarRepository, c.selfEditStore,
                    c.usageRepository, c.profileStore, c.taskStore,
                )
            modelClass.isAssignableFrom(NewsViewModel::class.java) ->
                NewsViewModel(
                    c.newsRepository, c.settingsRepository, c.marketsRepository, c.socialRepository,
                    c.newsAnalysisEngine, c.newsAnalysisStore, c.breakingCoverageRepository,
                )
            modelClass.isAssignableFrom(MarketsViewModel::class.java) ->
                MarketsViewModel(c.marketsRepository, c.settingsRepository)
            modelClass.isAssignableFrom(EconomyViewModel::class.java) ->
                EconomyViewModel(c.economyRepository, c.settingsRepository)
            modelClass.isAssignableFrom(FuelViewModel::class.java) ->
                FuelViewModel(c.fuelRepository, c.settingsRepository)
            modelClass.isAssignableFrom(SpaceWeatherViewModel::class.java) ->
                SpaceWeatherViewModel(c.spaceWeatherRepository, c.locationProvider)
            modelClass.isAssignableFrom(OrbitalViewModel::class.java) ->
                OrbitalViewModel(c.orbitalRepository, c.locationProvider, c.tleRepository, c.launchRepository)
            modelClass.isAssignableFrom(CompassViewModel::class.java) ->
                CompassViewModel(c.newCompassController(), c.locationProvider, c.waypointStore)
            modelClass.isAssignableFrom(PlacesViewModel::class.java) ->
                PlacesViewModel(c.overpassRepository, c.locationProvider)
            modelClass.isAssignableFrom(GuidesViewModel::class.java) ->
                GuidesViewModel(c.survivalContentRepository)
            modelClass.isAssignableFrom(dev.mascwa.pulse.feature.survive.HabitatViewModel::class.java) ->
                dev.mascwa.pulse.feature.survive.HabitatViewModel(c.locationProvider)
            modelClass.isAssignableFrom(ToolsViewModel::class.java) ->
                ToolsViewModel(c.survivalTools)
            modelClass.isAssignableFrom(SosViewModel::class.java) ->
                SosViewModel(c.emergencyService, c.settingsRepository, c.locationProvider, c.survivalTools)
            modelClass.isAssignableFrom(dev.mascwa.pulse.feature.safety.SafetyViewModel::class.java) ->
                dev.mascwa.pulse.feature.safety.SafetyViewModel(c.safetyRepository, c.locationProvider)
            modelClass.isAssignableFrom(dev.mascwa.pulse.feature.social.SocialViewModel::class.java) ->
                dev.mascwa.pulse.feature.social.SocialViewModel(c.socialRepository)
            modelClass.isAssignableFrom(dev.mascwa.pulse.feature.search.SearchViewModel::class.java) ->
                dev.mascwa.pulse.feature.search.SearchViewModel(c.settingsRepository)
            modelClass.isAssignableFrom(dev.mascwa.pulse.feature.tacnet.RadarViewModel::class.java) ->
                dev.mascwa.pulse.feature.tacnet.RadarViewModel(c.radarRepository, c.locationProvider, c.spaceWeatherRepository)
            modelClass.isAssignableFrom(dev.mascwa.pulse.feature.tacnet.TelemetryViewModel::class.java) ->
                dev.mascwa.pulse.feature.tacnet.TelemetryViewModel(c.newTelemetryController(), c.locationProvider, c.settingsRepository)
            modelClass.isAssignableFrom(dev.mascwa.pulse.feature.tacnet.RadioViewModel::class.java) ->
                dev.mascwa.pulse.feature.tacnet.RadioViewModel(c.locationProvider, c.radioBrowserRepository, c.settingsRepository, c.tuneInRepository)
            modelClass.isAssignableFrom(dev.mascwa.pulse.feature.notes.NotesViewModel::class.java) ->
                dev.mascwa.pulse.feature.notes.NotesViewModel(c.notesStore)
            modelClass.isAssignableFrom(dev.mascwa.pulse.feature.tasks.TasksViewModel::class.java) ->
                dev.mascwa.pulse.feature.tasks.TasksViewModel(c.taskStore)
            modelClass.isAssignableFrom(dev.mascwa.pulse.feature.diary.DiaryViewModel::class.java) ->
                dev.mascwa.pulse.feature.diary.DiaryViewModel(c.diaryStore)
            modelClass.isAssignableFrom(dev.mascwa.pulse.feature.spotify.SpotifyViewModel::class.java) ->
                dev.mascwa.pulse.feature.spotify.SpotifyViewModel(c.spotifyRepository)
            modelClass.isAssignableFrom(dev.mascwa.pulse.feature.jarvis.JarvisViewModel::class.java) ->
                dev.mascwa.pulse.feature.jarvis.JarvisViewModel(
                    c.jarvisMemory, c.inferenceEngine, c.deviceContextProvider, c.banterEngine,
                    c.intentRouter, c.actionOrchestrator, c.textToSpeech, c.settingsRepository,
                    c.voskSpeech, c.agentOrchestrator, c.knowledgeStore, c.selfEditStore, c.briefingBuilder,
                    c.curiosityEngine, c.approvalGate, c.usageRepository, c.cerebellumStore, c.profileStore,
                    c.taskStore, c.memoryStream, c.procedureStore, c.sensoriumEngine,
                )
            modelClass.isAssignableFrom(dev.mascwa.pulse.feature.jarvis.JarvisSetupViewModel::class.java) ->
                dev.mascwa.pulse.feature.jarvis.JarvisSetupViewModel(
                    c.modelManager, c.inferenceEngine, c.settingsRepository, c.knowledgeStore, c.selfEditStore,
                    c.jarvisMemory, c.actionOrchestrator, c.textToSpeech,
                )
            modelClass.isAssignableFrom(dev.mascwa.pulse.feature.jarvis.JarvisMemoryViewModel::class.java) ->
                dev.mascwa.pulse.feature.jarvis.JarvisMemoryViewModel(c.jarvisMemory, c.profileStore, c.taskStore, c.memoryStream, c.interestStore, c.findingStore, c.procedureStore, c.auditLedgerStore)
            modelClass.isAssignableFrom(dev.mascwa.pulse.feature.jarvis.JarvisApprovalsViewModel::class.java) ->
                dev.mascwa.pulse.feature.jarvis.JarvisApprovalsViewModel(c.selfEditStore, c.approvalGate)
            modelClass.isAssignableFrom(dev.mascwa.pulse.feature.jarvis.JarvisDossierViewModel::class.java) ->
                dev.mascwa.pulse.feature.jarvis.JarvisDossierViewModel(c.profileStore, c.taskStore, c.deviceContextProvider, c.usageRepository)
            modelClass.isAssignableFrom(WeatherViewModel::class.java) ->
                WeatherViewModel(c.weatherRepository, c.locationProvider, c.settingsRepository)
            modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                SettingsViewModel(c.settingsRepository, c.notificationScheduler, c.diskCache, c.notifier, c.updateRepository, c.selfCoder, c.usageRepository, c.cerebellumStore, c.profileStore, c.taskStore, c.memoryStream, c.wifiPolicyController, c.auditLedgerStore, c.ledgerSelfTest, c.oracleLearningStore)
            modelClass.isAssignableFrom(dev.mascwa.pulse.feature.diagnostics.CrashLogViewModel::class.java) ->
                dev.mascwa.pulse.feature.diagnostics.CrashLogViewModel(c.crashReporter, c.debugUploader)
            modelClass.isAssignableFrom(dev.mascwa.pulse.feature.security.SecurityAuditViewModel::class.java) ->
                dev.mascwa.pulse.feature.security.SecurityAuditViewModel(c.securityAuditor, c.securityAuditStore, c.applicationContext)
            modelClass.isAssignableFrom(dev.mascwa.pulse.feature.nav.NavViewModel::class.java) ->
                dev.mascwa.pulse.feature.nav.NavViewModel(c.locationProvider, c.newCompassController(), c.overpassRepository, c.settingsRepository, c.waypointStore, c.safetyRepository, c.routingRepository, c.rainViewerRepository, c.radarRepository, c.trackStore, c.elevationRepository)
            modelClass.isAssignableFrom(dev.mascwa.pulse.feature.objectives.ObjectivesViewModel::class.java) ->
                dev.mascwa.pulse.feature.objectives.ObjectivesViewModel(c.calendarObjectives, c.waypointStore, c.locationProvider)
            modelClass.isAssignableFrom(dev.mascwa.pulse.feature.oracle.OracleViewModel::class.java) ->
                dev.mascwa.pulse.feature.oracle.OracleViewModel(c)
            modelClass.isAssignableFrom(dev.mascwa.pulse.feature.sensorium.SensoriumViewModel::class.java) ->
                dev.mascwa.pulse.feature.sensorium.SensoriumViewModel(c)
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
        return vm as T
    }
}
