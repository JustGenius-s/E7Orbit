package com.e7orbit.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.e7orbit.AppGraph
import com.e7orbit.automation.TaskKind
import com.e7orbit.capture.VpnCaptureService
import com.e7orbit.data.E7Artifact
import com.e7orbit.data.E7Gear
import com.e7orbit.data.E7Hero
import com.e7orbit.data.E7ScannedHero
import com.e7orbit.data.E7DataSnapshot
import com.e7orbit.data.GearImportPhase
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
import com.e7orbit.optimizer.GearOptimizationConfig
import com.e7orbit.optimizer.GearInventoryFilter
import com.e7orbit.optimizer.GearInventorySort
import com.e7orbit.optimizer.GearOptimizer
import com.e7orbit.optimizer.EquipmentPlan
import com.e7orbit.optimizer.EquipmentPlanCollection
import com.e7orbit.optimizer.EquipmentPlanStore
import com.e7orbit.optimizer.HeroBuildSort
import com.e7orbit.optimizer.HeroOptimizerPreference
import com.e7orbit.optimizer.ImprintRank
import com.e7orbit.optimizer.OptimizerContent
import com.e7orbit.optimizer.OptimizerPreferenceStore
import com.e7orbit.optimizer.OptimizerUiPreferenceStore
import com.e7orbit.optimizer.matchScannedHero
import com.e7orbit.optimizer.withSelfImprint
import com.e7orbit.optimizer.withArtifact
import com.e7orbit.optimizer.applyBuild
import com.e7orbit.optimizer.applyTo
import com.e7orbit.optimizer.copyAs
import com.e7orbit.optimizer.createEquipmentPlan
import com.e7orbit.optimizer.OptimizerConstraints
import com.e7orbit.optimizer.OptimizedBuild
import com.e7orbit.optimizer.OptimizerMetric
import com.e7orbit.optimizer.OptimizerStat
import com.e7orbit.service.E7AccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val vpnCapture: VpnCaptureUiState = VpnCaptureUiState(),
    val optimizer: OptimizerUiState = OptimizerUiState(),
)

enum class OptimizerPhase {
    IDLE,
    RUNNING,
    READY,
    ERROR,
}

data class OptimizerSetOption(
    val code: String,
    val name: String,
    val pieces: Int,
)

data class OptimizerUiState(
    val phase: OptimizerPhase = OptimizerPhase.IDLE,
    val content: OptimizerContent = OptimizerContent.HEROES,
    val selectedHeroCode: String? = null,
    val selectedEquippedHeroId: Long? = null,
    val plans: List<EquipmentPlan> = emptyList(),
    val selectedPlanId: String? = null,
    val heroSort: HeroBuildSort = HeroBuildSort(),
    val gearFilter: GearInventoryFilter = GearInventoryFilter(),
    val gearSort: GearInventorySort = GearInventorySort(),
    val metric: OptimizerMetric = OptimizerMetric.COMBAT_POWER,
    val minimums: Map<OptimizerStat, Int> = emptyMap(),
    val requiredSets: Set<String> = emptySet(),
    val imprintRank: ImprintRank = ImprintRank.DEFAULT,
    val artifactCode: String? = null,
    val allowLocked: Boolean = true,
    val allowEquipped: Boolean = true,
    val onlyMaxed: Boolean = true,
    val heroPreferences: Map<Long, HeroOptimizerPreference> = emptyMap(),
    val results: List<OptimizedBuild> = emptyList(),
    val combinationsEvaluated: Long = 0L,
    val elapsedMs: Long = 0L,
    val errorMessage: String? = null,
) {
    val selectedPlan: EquipmentPlan?
        get() = plans.firstOrNull { it.id == selectedPlanId }
}

data class VpnCaptureUiState(
    val running: Boolean = false,
    val packets: Long = 0L,
    val bytes: Long = 0L,
    val capturedSegments: Long = 0L,
    val capturedBytes: Long = 0L,
    val importPhase: GearImportPhase = GearImportPhase.IDLE,
    val importedGearCount: Int = 0,
    val importedHeroCount: Int = 0,
    val importedAtEpochMs: Long = 0L,
    val errorMessage: String? = null,
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
    val gears: List<E7Gear> = emptyList(),
    val scannedHeroes: List<E7ScannedHero> = emptyList(),
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
    private val optimizerPreferenceStore = OptimizerPreferenceStore(application)
    private val optimizerUiPreferenceStore = OptimizerUiPreferenceStore(application)
    private val equipmentPlanStore = EquipmentPlanStore(application)
    private val savedEquipmentPlans = equipmentPlanStore.load()
    private val optimizer = MutableStateFlow(
        OptimizerUiState(
            heroSort = optimizerUiPreferenceStore.loadHeroSort(),
            heroPreferences = optimizerPreferenceStore.load(),
            plans = savedEquipmentPlans.plans,
            selectedPlanId = savedEquipmentPlans.selectedPlanId,
        ),
    )
    private val gearOptimizer = GearOptimizer()
    private var optimizerJob: Job? = null
    private var optimizerRequestId = 0L
    private var importedGearIds: List<Long> = emptyList()
    private var rtaRequestId = 0L
    private val runtimeStatuses = combine(
        taskCoordinator.shopStatus,
        taskCoordinator.huntStatus,
    ) { shop, hunt -> shop to hunt }

    private val vpnStatus = combine(
        combine(
            AppGraph.vpnCapture.isRunning,
            AppGraph.vpnCapture.packets,
            AppGraph.vpnCapture.bytes,
            AppGraph.vpnCapture.capturedSegments,
            AppGraph.vpnCapture.capturedBytes,
        ) { running, packets, bytes, segments, capturedBytes ->
            VpnCaptureUiState(
                running = running,
                packets = packets,
                bytes = bytes,
                capturedSegments = segments,
                capturedBytes = capturedBytes,
            )
        },
        AppGraph.vpnCapture.lastError,
        AppGraph.gearImportRepository.state,
    ) { state, captureError, import ->
        state.copy(
            importPhase = import.phase,
            importedGearCount = import.gears.size,
            importedHeroCount = import.heroCount,
            importedAtEpochMs = import.importedAtEpochMs,
            errorMessage = captureError ?: import.errorMessage,
        )
    }

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
    }.combine(AppGraph.gearImportRepository.state) { state, import ->
        state.copy(
            data = state.data.copy(
                gears = import.gears,
                scannedHeroes = import.heroes,
            ),
        )
    }.combine(vpnStatus) { state, vpn ->
        state.copy(vpnCapture = vpn)
    }.combine(optimizer) { state, optimizerState ->
        state.copy(optimizer = optimizerState)
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
        viewModelScope.launch {
            AppGraph.gearImportRepository.state.collect { import ->
                val currentIds = import.gears.map(E7Gear::id)
                if (currentIds != importedGearIds) {
                    importedGearIds = currentIds
                    invalidateOptimizerResults()
                }
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

    fun setOptimizerContent(content: OptimizerContent) {
        optimizer.value = optimizer.value.copy(content = content)
    }

    fun createEquipmentPlan(name: String) {
        val trimmed = name.trim().take(40)
        if (trimmed.isEmpty()) return
        val plan = createEquipmentPlan(
            name = trimmed,
            gears = emptyList(),
        )
        optimizer.value = optimizer.value.copy(
            plans = optimizer.value.plans + plan,
            selectedPlanId = plan.id,
        )
        invalidateOptimizerResults()
        persistEquipmentPlans()
    }

    fun copySelectedEquipmentPlan(name: String) {
        val trimmed = name.trim().take(40)
        if (trimmed.isEmpty()) return
        val source = optimizer.value.selectedPlan ?: createEquipmentPlan(
            name = "默认方案",
            gears = AppGraph.gearImportRepository.state.value.gears,
        )
        val copy = source.copyAs(trimmed)
        optimizer.value = optimizer.value.copy(
            plans = optimizer.value.plans + copy,
            selectedPlanId = copy.id,
        )
        invalidateOptimizerResults()
        persistEquipmentPlans()
    }

    fun selectEquipmentPlan(planId: String) {
        if (planId.isNotEmpty() && optimizer.value.plans.none { it.id == planId }) return
        optimizer.value = optimizer.value.copy(selectedPlanId = planId.ifEmpty { null })
        invalidateOptimizerResults()
        persistEquipmentPlans()
    }

    fun deleteSelectedEquipmentPlan() {
        val selectedId = optimizer.value.selectedPlanId ?: return
        val nextPlans = optimizer.value.plans.filterNot { it.id == selectedId }
        optimizer.value = optimizer.value.copy(
            plans = nextPlans,
            selectedPlanId = nextPlans.firstOrNull()?.id,
        )
        invalidateOptimizerResults()
        persistEquipmentPlans()
    }

    fun applyOptimizerResult(build: OptimizedBuild) {
        val selected = optimizer.value.selectedPlan ?: return
        val heroId = optimizer.value.selectedEquippedHeroId ?: return
        val validGearIds = AppGraph.gearImportRepository.state.value.gears
            .mapTo(hashSetOf(), E7Gear::id)
        val updated = selected.applyBuild(
            heroId = heroId,
            gearIds = build.items.map(E7Gear::id),
            validGearIds = validGearIds,
        )
        optimizer.value = optimizer.value.copy(
            plans = optimizer.value.plans.map { plan ->
                if (plan.id == updated.id) updated else plan
            },
        )
        persistEquipmentPlans()
    }

    private fun persistEquipmentPlans() {
        val collection = EquipmentPlanCollection(
            plans = optimizer.value.plans,
            selectedPlanId = optimizer.value.selectedPlanId,
        )
        viewModelScope.launch {
            runCatching { equipmentPlanStore.save(collection) }
                .onFailure { AppGraph.logger.error("optimizer.plan_save_failed", it) }
        }
    }

    fun selectEquippedHero(instanceId: Long) {
        val scanned = AppGraph.gearImportRepository.state.value.heroes
            .firstOrNull { it.id == instanceId }
        val hero = matchScannedHero(scanned, data.value.heroes)
        val preference = optimizer.value.heroPreferences[instanceId] ?: HeroOptimizerPreference()
        updateOptimizerConfig {
            copy(
                selectedHeroCode = hero?.code,
                selectedEquippedHeroId = instanceId,
                metric = preference.metric,
                minimums = preference.minimums,
                requiredSets = preference.requiredSets,
                imprintRank = preference.imprintRank,
                artifactCode = preference.artifactCode,
            )
        }
    }

    fun setHeroBuildSort(sort: HeroBuildSort) {
        optimizer.value = optimizer.value.copy(heroSort = sort)
        viewModelScope.launch {
            runCatching { optimizerUiPreferenceStore.saveHeroSort(sort) }
                .onFailure { AppGraph.logger.error("optimizer.hero_sort_save_failed", it) }
        }
    }

    fun toggleGearSetFilter(code: String) {
        updateGearFilter {
            copy(setCodes = setCodes.toggle(code))
        }
    }

    fun toggleGearMainStatFilter(type: String) {
        updateGearFilter {
            copy(mainStatTypes = mainStatTypes.toggle(type))
        }
    }

    fun toggleGearSubstatFilter(type: String) {
        updateGearFilter {
            copy(substatTypes = substatTypes.toggle(type))
        }
    }

    fun setGearMinimumScore(score: Int) {
        updateGearFilter { copy(minimumScore = score.coerceAtLeast(0)) }
    }

    fun setGearSort(sort: GearInventorySort) {
        optimizer.value = optimizer.value.copy(gearSort = sort)
    }

    fun clearGearFilters() {
        optimizer.value = optimizer.value.copy(gearFilter = GearInventoryFilter())
    }

    private fun updateGearFilter(transform: GearInventoryFilter.() -> GearInventoryFilter) {
        optimizer.value = optimizer.value.copy(gearFilter = optimizer.value.gearFilter.transform())
    }

    private fun Set<String>.toggle(value: String): Set<String> =
        if (value in this) this - value else this + value

    fun setOptimizerMetric(metric: OptimizerMetric) {
        updateOptimizerPreference { copy(metric = metric) }
    }

    fun setOptimizerMinimum(stat: OptimizerStat, value: Int) {
        updateOptimizerPreference {
            copy(minimums = minimums + (stat to value.coerceAtLeast(0)))
        }
    }

    fun toggleOptimizerRequiredSet(code: String) {
        updateOptimizerPreference {
            val next = if (code in requiredSets) requiredSets - code else requiredSets + code
            copy(requiredSets = next)
        }
    }

    fun setOptimizerImprintRank(rank: ImprintRank) {
        updateOptimizerPreference { copy(imprintRank = rank) }
    }

    fun setOptimizerArtifact(code: String?) {
        updateOptimizerPreference { copy(artifactCode = code?.takeIf(String::isNotBlank)) }
    }

    fun setOptimizerAllowLocked(enabled: Boolean) {
        updateOptimizerConfig { copy(allowLocked = enabled) }
    }

    fun setOptimizerAllowEquipped(enabled: Boolean) {
        updateOptimizerConfig { copy(allowEquipped = enabled) }
    }

    fun setOptimizerOnlyMaxed(enabled: Boolean) {
        updateOptimizerConfig { copy(onlyMaxed = enabled) }
    }

    fun startOptimizer() {
        val current = optimizer.value
        val hero = data.value.heroes.firstOrNull { it.code == current.selectedHeroCode }
        val importedGears = AppGraph.gearImportRepository.state.value.gears
        val inventory = current.selectedPlan?.applyTo(importedGears) ?: importedGears
        if (hero == null) {
            optimizer.value = current.copy(
                phase = OptimizerPhase.ERROR,
                errorMessage = "请先选择英雄",
            )
            return
        }
        if (hero.stats == null) {
            optimizer.value = current.copy(
                phase = OptimizerPhase.ERROR,
                errorMessage = "该英雄缺少六星满觉基础属性",
            )
            return
        }
        if (inventory.isEmpty()) {
            optimizer.value = current.copy(
                phase = OptimizerPhase.ERROR,
                errorMessage = "尚未导入装备，请先在首页完成抓包",
            )
            return
        }
        if (current.requiredSets.sumOf(GearOptimizer::setPieces) > 6) {
            optimizer.value = current.copy(
                phase = OptimizerPhase.ERROR,
                errorMessage = "必选套装合计超过 6 件",
            )
            return
        }

        optimizerJob?.cancel()
        val requestId = ++optimizerRequestId
        val config = current.toOptimizationConfig()
        optimizer.value = current.copy(
            phase = OptimizerPhase.RUNNING,
            results = emptyList(),
            combinationsEvaluated = 0L,
            elapsedMs = 0L,
            errorMessage = null,
        )
        optimizerJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                val outcome = withContext(Dispatchers.Default) {
                    val artifact = data.value.artifacts.firstOrNull { it.code == current.artifactCode }
                    gearOptimizer.optimize(
                        hero = hero
                            .withSelfImprint(current.imprintRank)
                            .withArtifact(artifact),
                        inventory = inventory,
                        config = config,
                        isCancelled = { requestId != optimizerRequestId || !isActive },
                    )
                }
                if (requestId != optimizerRequestId) return@launch
                val elapsed = System.currentTimeMillis() - startedAt
                optimizer.value = optimizer.value.copy(
                    phase = OptimizerPhase.READY,
                    results = outcome.builds,
                    combinationsEvaluated = outcome.combinationsEvaluated,
                    elapsedMs = elapsed,
                    errorMessage = null,
                )
                AppGraph.logger.info(
                    "optimizer.completed",
                    "hero" to hero.code,
                    "inventory" to inventory.size,
                    "evaluated" to outcome.combinationsEvaluated,
                    "results" to outcome.builds.size,
                    "elapsedMs" to elapsed,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestId != optimizerRequestId) return@launch
                AppGraph.logger.error("optimizer.failed", error, "hero" to hero.code)
                optimizer.value = optimizer.value.copy(
                    phase = OptimizerPhase.ERROR,
                    results = emptyList(),
                    errorMessage = error.message ?: "配装计算失败",
                )
            }
        }
    }

    fun stopOptimizer() {
        if (optimizer.value.phase != OptimizerPhase.RUNNING) return
        optimizerRequestId++
        optimizerJob?.cancel()
        optimizerJob = null
        optimizer.value = optimizer.value.copy(
            phase = OptimizerPhase.IDLE,
            results = emptyList(),
            errorMessage = null,
        )
    }

    private fun updateOptimizerPreference(
        transform: HeroOptimizerPreference.() -> HeroOptimizerPreference,
    ) {
        val instanceId = optimizer.value.selectedEquippedHeroId
        if (instanceId == null) {
            val next = HeroOptimizerPreference(
                metric = optimizer.value.metric,
                minimums = optimizer.value.minimums,
                requiredSets = optimizer.value.requiredSets,
                imprintRank = optimizer.value.imprintRank,
                artifactCode = optimizer.value.artifactCode,
            ).transform()
            updateOptimizerConfig {
                copy(
                    metric = next.metric,
                    minimums = next.minimums,
                    requiredSets = next.requiredSets,
                    imprintRank = next.imprintRank,
                    artifactCode = next.artifactCode,
                )
            }
            return
        }
        val next = (optimizer.value.heroPreferences[instanceId] ?: HeroOptimizerPreference()).transform()
        updateOptimizerConfig {
            copy(
                metric = next.metric,
                minimums = next.minimums,
                requiredSets = next.requiredSets,
                imprintRank = next.imprintRank,
                artifactCode = next.artifactCode,
                heroPreferences = heroPreferences + (instanceId to next),
            )
        }
        viewModelScope.launch {
            runCatching { optimizerPreferenceStore.save(optimizer.value.heroPreferences) }
                .onFailure { AppGraph.logger.error("optimizer.preference_save_failed", it) }
        }
    }

    private fun updateOptimizerConfig(transform: OptimizerUiState.() -> OptimizerUiState) {
        optimizerRequestId++
        optimizerJob?.cancel()
        optimizerJob = null
        optimizer.value = optimizer.value.transform().copy(
            phase = OptimizerPhase.IDLE,
            results = emptyList(),
            combinationsEvaluated = 0L,
            elapsedMs = 0L,
            errorMessage = null,
        )
    }

    private fun invalidateOptimizerResults() {
        if (optimizer.value.phase == OptimizerPhase.IDLE && optimizer.value.results.isEmpty()) return
        optimizerRequestId++
        optimizerJob?.cancel()
        optimizerJob = null
        optimizer.value = optimizer.value.copy(
            phase = OptimizerPhase.IDLE,
            results = emptyList(),
            combinationsEvaluated = 0L,
            elapsedMs = 0L,
            errorMessage = null,
        )
    }

    private fun OptimizerUiState.toOptimizationConfig(): GearOptimizationConfig =
        GearOptimizationConfig(
            metric = metric,
            constraints = OptimizerConstraints(
                attack = minimums[OptimizerStat.ATTACK] ?: 0,
                health = minimums[OptimizerStat.HEALTH] ?: 0,
                defense = minimums[OptimizerStat.DEFENSE] ?: 0,
                speed = minimums[OptimizerStat.SPEED] ?: 0,
                critChance = minimums[OptimizerStat.CRIT_CHANCE] ?: 0,
                critDamage = minimums[OptimizerStat.CRIT_DAMAGE] ?: 0,
                effectiveness = minimums[OptimizerStat.EFFECTIVENESS] ?: 0,
                resistance = minimums[OptimizerStat.RESISTANCE] ?: 0,
            ),
            requiredSets = requiredSets,
            allowLocked = allowLocked,
            allowEquipped = allowEquipped,
            onlyMaxed = onlyMaxed,
        )

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
        val selectedInstanceId = optimizer.value.selectedEquippedHeroId
        val selectedScanned = AppGraph.gearImportRepository.state.value.heroes
            .firstOrNull { it.id == selectedInstanceId }
        val currentOptimizerHero = matchScannedHero(selectedScanned, snapshot.heroes)
            ?.takeIf { it.stats != null }
            ?.code
            ?: optimizer.value.selectedHeroCode
                ?.takeIf { selected -> snapshot.heroes.any { it.code == selected && it.stats != null } }
        if (currentOptimizerHero != optimizer.value.selectedHeroCode) {
            updateOptimizerConfig { copy(selectedHeroCode = currentOptimizerHero) }
        }
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

    /**
     * 开启装备抓包。若尚未授予 VPN 权限,通过 [onConsent]
     * 把授权 intent 交给 Activity 发起系统确认弹窗。
     */
    fun startVpnCapture(onConsent: (Intent) -> Unit) {
        val intent = VpnService.prepare(getApplication())
        if (intent == null) {
            VpnCaptureService.start(getApplication())
        } else {
            onConsent(intent)
        }
    }

    fun stopVpnCapture() = VpnCaptureService.stop(getApplication())

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
