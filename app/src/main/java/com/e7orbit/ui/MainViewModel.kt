package com.e7orbit.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.e7orbit.AppGraph
import com.e7orbit.automation.TaskKind
import com.e7orbit.data.E7Artifact
import com.e7orbit.data.E7Hero
import com.e7orbit.data.E7DataSnapshot
import com.e7orbit.data.HeroRtaAnalysis
import com.e7orbit.data.RtaSeason
import com.e7orbit.data.RtaTier
import com.e7orbit.model.AutomationStatus
import com.e7orbit.model.E7_CN_PACKAGE
import com.e7orbit.model.HuntConfig
import com.e7orbit.model.HuntDifficulty
import com.e7orbit.model.HuntDungeon
import com.e7orbit.model.HuntEnergyRefill
import com.e7orbit.model.HuntPhase
import com.e7orbit.model.HuntStatus
import com.e7orbit.model.MAX_SUPPORTED_HUNT_RUNS
import com.e7orbit.model.RunConfig
import com.e7orbit.model.RunSummary
import com.e7orbit.service.E7AccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate

data class EnvironmentStatus(
    val accessibilityEnabled: Boolean = false,
    val gameInstalled: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val projectionReady: Boolean = false,
    val openCvReady: Boolean = false,
) {
    val canPrepare: Boolean
        get() = accessibilityEnabled &&
            gameInstalled &&
            openCvReady
}

data class MainUiState(
    val config: RunConfig = RunConfig(),
    val huntConfig: HuntConfig = HuntConfig(),
    val automation: AutomationStatus = AutomationStatus(),
    val huntAutomation: HuntStatus = HuntStatus(),
    val environment: EnvironmentStatus = EnvironmentStatus(),
    val lastSummary: RunSummary = RunSummary(),
    val data: DataUiState = DataUiState(),
)

enum class DataSection {
    HEROES,
    ARTIFACTS,
}

enum class DataLoadState {
    IDLE,
    LOADING,
    READY,
    ERROR,
}

data class DataUiState(
    val loadState: DataLoadState = DataLoadState.IDLE,
    val heroes: List<E7Hero> = emptyList(),
    val artifacts: List<E7Artifact> = emptyList(),
    val section: DataSection = DataSection.HEROES,
    val query: String = "",
    val selectedHeroCode: String? = null,
    val selectedArtifactCode: String? = null,
    val fetchedAtEpochMs: Long = 0L,
    val errorMessage: String? = null,
    val rta: HeroRtaUiState = HeroRtaUiState(),
)

data class HeroRtaUiState(
    val loadState: DataLoadState = DataLoadState.IDLE,
    val seasons: List<RtaSeason> = emptyList(),
    val selectedSeasonCode: String? = null,
    val selectedTier: RtaTier = RtaTier.MASTER,
    val heroCode: String? = null,
    val analysis: HeroRtaAnalysis? = null,
    val errorMessage: String? = null,
)

private fun List<RtaSeason>.defaultRtaSeasonCode(): String? {
    val today = LocalDate.now().toString()
    return firstOrNull(RtaSeason::isCurrent)?.code
        ?: firstOrNull { it.endDate.take(10) <= today }?.code
        ?: firstOrNull()?.code
}

internal class PersistedDraft<T>(initialValue: T) {
    private val mutableValue = MutableStateFlow(initialValue)
    private var persistedValue = initialValue
    private var pendingValue: T? = null
    private var hasPendingValue = false

    val state: StateFlow<T> = mutableValue
    val value: T
        get() = mutableValue.value

    fun update(value: T): Boolean {
        if (value == mutableValue.value) return false
        pendingValue = value
        hasPendingValue = true
        mutableValue.value = value
        return true
    }

    fun acceptPersisted(value: T) {
        persistedValue = value
        if (!hasPendingValue || pendingValue == value) {
            mutableValue.value = value
            pendingValue = null
            hasPendingValue = false
        }
    }

    fun rejectPending(value: T) {
        if (!hasPendingValue || pendingValue != value) return
        pendingValue = null
        hasPendingValue = false
        mutableValue.value = persistedValue
    }
}

class MainViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val taskCoordinator = AppGraph.taskCoordinator
    private val settings = AppGraph.settingsRepository
    private val draftConfig = PersistedDraft(RunConfig())
    private val draftHuntConfig = PersistedDraft(HuntConfig())
    private val environment = MutableStateFlow(readEnvironment())
    private val data = MutableStateFlow(DataUiState())
    private var rtaRequestId = 0L
    private val runtimeStatuses = combine(
        taskCoordinator.shopStatus,
        taskCoordinator.huntStatus,
    ) { shop, hunt -> shop to hunt }

    private val baseUiState: Flow<MainUiState> = combine(
        draftConfig.state,
        draftHuntConfig.state,
        runtimeStatuses,
        environment,
        settings.lastSummary,
    ) { config, huntConfig, runtimes, environmentStatus, lastSummary ->
        MainUiState(
            config = config,
            huntConfig = huntConfig,
            automation = runtimes.first,
            huntAutomation = runtimes.second,
            environment = environmentStatus,
            lastSummary = lastSummary,
        )
    }

    val uiState: StateFlow<MainUiState> = baseUiState.combine(data) { state, dataState ->
        state.copy(data = dataState)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = MainUiState(),
    )

    init {
        viewModelScope.launch {
            settings.config.collect { saved ->
                draftConfig.acceptPersisted(saved)
            }
        }
        viewModelScope.launch {
            settings.huntConfig.collect { saved ->
                draftHuntConfig.acceptPersisted(saved)
            }
        }
        taskCoordinator.refreshHealth()
    }

    fun refreshEnvironment() {
        taskCoordinator.refreshHealth()
        environment.value = readEnvironment()
    }

    fun loadData(forceRefresh: Boolean = false) {
        if (!forceRefresh && data.value.loadState in setOf(DataLoadState.LOADING, DataLoadState.READY)) {
            return
        }
        data.value = data.value.copy(
            loadState = DataLoadState.LOADING,
            errorMessage = null,
        )
        viewModelScope.launch {
            try {
                val snapshot = AppGraph.e7DataRepository.load(forceRefresh)
                applyDataSnapshot(snapshot)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                AppGraph.logger.error("data.load_failed", error)
                data.value = data.value.copy(
                    loadState = DataLoadState.ERROR,
                    errorMessage = error.message ?: "公开数据暂时不可用",
                )
            }
        }
    }

    fun setDataSection(section: DataSection) {
        data.value = data.value.copy(section = section)
    }

    fun setDataQuery(query: String) {
        data.value = data.value.copy(query = query)
    }

    fun selectHero(code: String) {
        val previousRta = data.value.rta
        data.value = data.value.copy(
            selectedHeroCode = code,
            rta = previousRta.copy(
                loadState = DataLoadState.LOADING,
                heroCode = code,
                analysis = null,
                errorMessage = null,
            ),
        )
        loadHeroRta(code)
    }

    fun selectArtifact(code: String) {
        data.value = data.value.copy(selectedArtifactCode = code)
    }

    fun setRtaSeason(code: String) {
        val current = data.value.rta
        val heroCode = current.heroCode ?: return
        if (current.selectedSeasonCode == code && current.loadState == DataLoadState.READY) return
        data.value = data.value.copy(
            rta = current.copy(
                selectedSeasonCode = code,
                analysis = null,
                errorMessage = null,
            ),
        )
        loadHeroRta(heroCode)
    }

    fun setRtaTier(tier: RtaTier) {
        val current = data.value.rta
        val heroCode = current.heroCode ?: return
        if (current.selectedTier == tier && current.loadState == DataLoadState.READY) return
        data.value = data.value.copy(
            rta = current.copy(
                selectedTier = tier,
                analysis = null,
                errorMessage = null,
            ),
        )
        loadHeroRta(heroCode)
    }

    fun retryHeroRta() {
        val heroCode = data.value.rta.heroCode ?: return
        loadHeroRta(heroCode, forceRefresh = true)
    }

    private fun loadHeroRta(heroCode: String, forceRefresh: Boolean = false) {
        val requestId = ++rtaRequestId
        val requestedState = data.value.rta
        val requestedSeasonCode = requestedState.selectedSeasonCode
        val requestedTier = requestedState.selectedTier
        data.value = data.value.copy(
            rta = requestedState.copy(
                loadState = DataLoadState.LOADING,
                heroCode = heroCode,
                analysis = null,
                errorMessage = null,
            ),
        )
        viewModelScope.launch {
            try {
                val seasons = if (requestedState.seasons.isEmpty() || forceRefresh) {
                    AppGraph.e7DataRepository.loadRtaSeasons(forceRefresh)
                } else {
                    requestedState.seasons
                }
                if (requestId != rtaRequestId || data.value.selectedHeroCode != heroCode) return@launch
                val seasonCode = requestedSeasonCode
                    ?.takeIf { selected -> seasons.any { it.code == selected } }
                    ?: seasons.defaultRtaSeasonCode()
                    ?: throw IllegalStateException("官方暂未提供 RTA 赛季")
                data.value = data.value.copy(
                    rta = data.value.rta.copy(
                        seasons = seasons,
                        selectedSeasonCode = seasonCode,
                    ),
                )
                val analysis = AppGraph.e7DataRepository.loadHeroRta(
                    heroCode = heroCode,
                    seasonCode = seasonCode,
                    tierCode = requestedTier.code,
                    forceRefresh = forceRefresh,
                )
                val currentRta = data.value.rta
                if (
                    requestId != rtaRequestId ||
                    data.value.selectedHeroCode != heroCode ||
                    currentRta.selectedSeasonCode != seasonCode ||
                    currentRta.selectedTier != requestedTier
                ) {
                    return@launch
                }
                data.value = data.value.copy(
                    rta = currentRta.copy(
                        loadState = DataLoadState.READY,
                        analysis = analysis,
                        errorMessage = null,
                    ),
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (requestId != rtaRequestId || data.value.selectedHeroCode != heroCode) return@launch
                AppGraph.logger.error("data.rta_load_failed", error)
                data.value = data.value.copy(
                    rta = data.value.rta.copy(
                        loadState = DataLoadState.ERROR,
                        analysis = null,
                        errorMessage = error.message ?: "官方 RTA 数据暂时不可用",
                    ),
                )
            }
        }
    }

    private fun applyDataSnapshot(snapshot: E7DataSnapshot) {
        val currentHero = data.value.selectedHeroCode
            ?.takeIf { selected -> snapshot.heroes.any { it.code == selected } }
            ?: snapshot.heroes.firstOrNull()?.code
        val currentArtifact = data.value.selectedArtifactCode
            ?.takeIf { selected -> snapshot.artifacts.any { it.code == selected } }
            ?: snapshot.artifacts.firstOrNull()?.code
        data.value = data.value.copy(
            loadState = DataLoadState.READY,
            heroes = snapshot.heroes,
            artifacts = snapshot.artifacts,
            selectedHeroCode = currentHero,
            selectedArtifactCode = currentArtifact,
            fetchedAtEpochMs = snapshot.fetchedAtEpochMs,
            errorMessage = null,
        )
    }

    fun setBuyCovenant(enabled: Boolean) {
        updateConfig { copy(buyCovenantBookmarks = enabled) }
    }

    fun setBuyMystic(enabled: Boolean) {
        updateConfig { copy(buyMysticMedals = enabled) }
    }

    fun setMaxRefreshes(value: Int) {
        updateConfig { copy(maxRefreshes = value) }
    }

    fun setMatchThreshold(value: Double) {
        updateConfig { copy(matchThreshold = value) }
    }

    fun setHuntDifficulty(difficulty: HuntDifficulty) {
        updateHuntConfig { copy(difficulty = difficulty) }
    }

    fun setHuntDungeon(dungeon: HuntDungeon) {
        updateHuntConfig { copy(dungeon = dungeon) }
    }

    fun setHuntManagedBattle(enabled: Boolean) {
        updateHuntConfig { copy(managedBattle = enabled) }
    }

    fun setHuntRunCount(value: Int) {
        updateHuntConfig { copy(runCount = value.coerceIn(1, MAX_SUPPORTED_HUNT_RUNS)) }
    }

    fun setHuntEnergyRefill(refill: HuntEnergyRefill) {
        updateHuntConfig { copy(energyRefill = refill) }
    }

    fun prepareRun() {
        refreshEnvironment()
        viewModelScope.launch {
            val config = draftConfig.value.normalized()
            taskCoordinator.startShop(config)
            if (taskCoordinator.shopStatus.value.isRunning) {
                launchGame()
            }
        }
    }

    fun prepareHunt() {
        refreshEnvironment()
        viewModelScope.launch {
            val config = draftHuntConfig.value.normalized()
            taskCoordinator.startHunt(config)
            if (taskCoordinator.huntStatus.value.isRunning) {
                launchGame()
            }
        }
    }

    fun pauseOrResume() {
        if (taskCoordinator.shopStatus.value.phase == com.e7orbit.model.AutomationPhase.PAUSED) {
            taskCoordinator.resume(TaskKind.SHOP)
        } else {
            taskCoordinator.pause(TaskKind.SHOP)
        }
    }

    fun stop() = taskCoordinator.stop(TaskKind.SHOP)

    fun pauseOrResumeHunt() {
        if (taskCoordinator.huntStatus.value.phase == HuntPhase.PAUSED) {
            taskCoordinator.resume(TaskKind.HUNT)
        } else {
            taskCoordinator.pause(TaskKind.HUNT)
        }
    }

    fun stopHunt() = taskCoordinator.stop(TaskKind.HUNT)

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        getApplication<Application>().startActivity(intent)
    }

    private fun updateHuntConfig(update: HuntConfig.() -> HuntConfig) {
        val updated = draftHuntConfig.value.update().normalized()
        if (!draftHuntConfig.update(updated)) return
        viewModelScope.launch {
            try {
                settings.saveHuntConfig(updated)
                draftHuntConfig.acceptPersisted(updated)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppGraph.logger.error("settings.hunt.save_failed", error)
                draftHuntConfig.rejectPending(updated)
            }
        }
    }

    private fun updateConfig(update: RunConfig.() -> RunConfig) {
        val updated = draftConfig.value.update().normalized()
        if (!draftConfig.update(updated)) return
        viewModelScope.launch {
            try {
                settings.saveConfig(updated)
                draftConfig.acceptPersisted(updated)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppGraph.logger.error("settings.shop.save_failed", error)
                draftConfig.rejectPending(updated)
            }
        }
    }

    private fun launchGame() {
        val context = getApplication<Application>()
        val intent = context.packageManager.getLaunchIntentForPackage(E7_CN_PACKAGE)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun readEnvironment(): EnvironmentStatus {
        val context = getApplication<Application>()
        val bounds = context.getSystemService(WindowManager::class.java)
            .maximumWindowMetrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
        val expectedService = ComponentName(context, E7AccessibilityService::class.java)
        val accessibilityEnabled = accessibilityManager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { service ->
                val info = service.resolveInfo.serviceInfo
                ComponentName(info.packageName, info.name) == expectedService
            }
        val gameInstalled = try {
            context.packageManager.getApplicationInfo(E7_CN_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        return EnvironmentStatus(
            accessibilityEnabled = accessibilityEnabled,
            gameInstalled = gameInstalled,
            width = width,
            height = height,
            projectionReady = AppGraph.projectionCapture.isReady.value,
            openCvReady = AppGraph.openCvReady,
        )
    }
}
