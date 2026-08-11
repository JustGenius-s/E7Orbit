package com.e7orbit.ui

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.e7orbit.AppGraph
import com.e7orbit.R
import com.e7orbit.capture.MediaProjectionCaptureService
import com.e7orbit.capture.VpnCaptureService
import com.e7orbit.data.GearImportPhase
import com.e7orbit.ui.theme.E7OrbitTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    private var pendingAutomation = PendingAutomation.SHOP
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            AppGraph.logger.warn("projection.consent_denied")
            viewModel.refreshEnvironment()
            return@registerForActivityResult
        }

        MediaProjectionCaptureService.start(this, result.resultCode, data)
        lifecycleScope.launch {
            val ready = withTimeoutOrNull(10_000L) {
                AppGraph.projectionCapture.isReady.filter { it }.first()
            } != null
            viewModel.refreshEnvironment()
            if (ready) preparePendingAutomation()
            else AppGraph.logger.error("projection.start_timeout")
        }
    }

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            VpnCaptureService.start(this)
        } else {
            AppGraph.logger.warn("vpn.consent_denied")
        }
        viewModel.refreshEnvironment()
    }

    private val gearImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            runCatching {
                val payload = withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(uri)
                        ?: error("无法打开导入文件")
                    input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                AppGraph.gearImportRepository.importExport(payload)
            }.onSuccess { imported ->
                Toast.makeText(
                    this@MainActivity,
                    "已导入 ${imported.gears.size} 件装备 · ${imported.heroes.size} 个英雄",
                    Toast.LENGTH_LONG,
                ).show()
            }.onFailure { error ->
                Toast.makeText(
                    this@MainActivity,
                    error.message ?: "装备文件导入失败",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private var pendingPlanExport: com.e7orbit.optimizer.EquipmentPlan? = null
    private val gearExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) {
            pendingPlanExport = null
            return@registerForActivityResult
        }
        val plan = pendingPlanExport.also { pendingPlanExport = null }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val gears = AppGraph.gearImportRepository.state.value.gears
                    check(gears.isNotEmpty()) { "没有可导出的装备" }
                    val export = if (plan == null) {
                        AppGraph.gearImportRepository.readGearExport()
                    } else {
                        AppGraph.gearImportRepository.readGearExport(plan.assignments)
                    }
                    val output = contentResolver.openOutputStream(uri, "wt")
                        ?: error("无法打开导出文件")
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(export) }
                    gears.size
                }
            }.onSuccess { gearCount ->
                AppGraph.logger.info(
                    "gear.export_succeeded",
                    "items" to gearCount,
                    "plan" to plan?.id,
                    "uri" to uri,
                )
                val message = plan?.let { "已导出方案「${it.name}」" } ?: "已导出 $gearCount 件装备"
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                AppGraph.logger.error("gear.export_failed", error)
                Toast.makeText(
                    this@MainActivity,
                    error.message ?: "装备导出失败",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            E7OrbitTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                OrbitApp(
                    state = state,
                    onBuyCovenantChanged = viewModel::setBuyCovenant,
                    onBuyMysticChanged = viewModel::setBuyMystic,
                    onMaxRefreshChanged = viewModel::setMaxRefreshes,
                    onThresholdChanged = viewModel::setMatchThreshold,
                    onHuntDungeonChanged = viewModel::setHuntDungeon,
                    onHuntDifficultyChanged = viewModel::setHuntDifficulty,
                    onHuntManagedBattleChanged = viewModel::setHuntManagedBattle,
                    onHuntRunCountChanged = viewModel::setHuntRunCount,
                    onHuntEnergyRefillChanged = viewModel::setHuntEnergyRefill,
                    onEnableAccessibility = viewModel::openAccessibilitySettings,
                    onRefreshEnvironment = viewModel::refreshEnvironment,
                    onPrepareShop = { requestProjection(PendingAutomation.SHOP) },
                    onPauseOrResumeShop = viewModel::pauseOrResume,
                    onStopShop = viewModel::stop,
                    onPrepareHunt = { requestProjection(PendingAutomation.HUNT) },
                    onPauseOrResumeHunt = viewModel::pauseOrResumeHunt,
                    onStopHunt = viewModel::stopHunt,
                    onStartVpnCapture = { viewModel.startVpnCapture { vpnLauncher.launch(it) } },
                    onStopVpnCapture = viewModel::stopVpnCapture,
                    onLoadData = viewModel::loadData,
                    onDataSectionChanged = viewModel::setDataSection,
                    onDataQueryChanged = viewModel::setDataQuery,
                    onImportGear = ::startGearImport,
                    onExportGear = ::startGearExport,
                    onSelectHero = viewModel::selectHero,
                    onSelectArtifact = viewModel::selectArtifact,
                    onRtaSeasonChanged = viewModel::setRtaSeason,
                    onRtaTierChanged = viewModel::setRtaTier,
                    onRetryHeroRta = viewModel::retryHeroRta,
                    onOptimizerContentChanged = viewModel::setOptimizerContent,
                    onHeroBuildSortChanged = viewModel::setHeroBuildSort,
                    onGearSetToggled = viewModel::toggleGearSetFilter,
                    onGearMainStatToggled = viewModel::toggleGearMainStatFilter,
                    onGearSubstatToggled = viewModel::toggleGearSubstatFilter,
                    onGearMinimumScoreChanged = viewModel::setGearMinimumScore,
                    onGearSortChanged = viewModel::setGearSort,
                    onClearGearFilters = viewModel::clearGearFilters,
                    onCreatePlan = viewModel::createEquipmentPlan,
                    onCopyPlan = viewModel::copySelectedEquipmentPlan,
                    onSelectPlan = viewModel::selectEquipmentPlan,
                    onDeletePlan = viewModel::deleteSelectedEquipmentPlan,
                    onExportPlan = ::startEquipmentPlanExport,
                    onEquippedHeroSelected = viewModel::selectEquippedHero,
                    onOptimizerMetricChanged = viewModel::setOptimizerMetric,
                    onOptimizerMinimumChanged = viewModel::setOptimizerMinimum,
                    onOptimizerRequiredSetToggled = viewModel::toggleOptimizerRequiredSet,
                    onOptimizerAllowLockedChanged = viewModel::setOptimizerAllowLocked,
                    onOptimizerAllowEquippedChanged = viewModel::setOptimizerAllowEquipped,
                    onOptimizerOnlyMaxedChanged = viewModel::setOptimizerOnlyMaxed,
                    onStartOptimizer = viewModel::startOptimizer,
                    onStopOptimizer = viewModel::stopOptimizer,
                    onApplyOptimizerResult = viewModel::applyOptimizerResult,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshEnvironment()
    }

    private fun startGearImport() {
        gearImportLauncher.launch(
            arrayOf("application/json", "text/plain", "application/octet-stream"),
        )
    }

    private fun startGearExport() {
        if (!ensureGearExportAvailable()) return
        pendingPlanExport = null
        gearExportLauncher.launch("gear.txt")
    }

    private fun startEquipmentPlanExport(plan: com.e7orbit.optimizer.EquipmentPlan) {
        if (!ensureGearExportAvailable()) return
        pendingPlanExport = plan
        gearExportLauncher.launch("${plan.name.sanitizedFileName()}-gear.txt")
    }

    private fun ensureGearExportAvailable(): Boolean {
        if (AppGraph.gearImportRepository.hasCompatibleExport()) return true
        Toast.makeText(
            this,
            "当前数据来自旧版本，请重新抓包并打开背包后再导出",
            Toast.LENGTH_LONG,
        ).show()
        return false
    }

    private fun String.sanitizedFileName(): String =
        replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "plan" }

    private fun requestProjection(automation: PendingAutomation) {
        pendingAutomation = automation
        if (AppGraph.projectionCapture.isReady.value) {
            preparePendingAutomation()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun preparePendingAutomation() {
        when (pendingAutomation) {
            PendingAutomation.SHOP -> viewModel.prepareRun()
            PendingAutomation.HUNT -> viewModel.prepareHunt()
        }
    }

    private enum class PendingAutomation { SHOP, HUNT }
}

internal enum class OrbitDestination(
    val label: String,
    val icon: Int,
) {
    HOME("首页", R.drawable.ic_nav_home),
    TASKS("任务", R.drawable.ic_nav_tasks),
    DATA("图鉴", R.drawable.ic_nav_data),
    OPTIMIZER("配装", R.drawable.ic_nav_optimizer),
    SETTINGS("设置", R.drawable.ic_nav_settings),
}

internal enum class AutomationTask { SHOP, HUNT }

private enum class DetailRoute(val title: String) {
    SHOP("神秘商店"),
    HUNT("讨伐"),
    HERO("英雄详情"),
    ARTIFACT("神器详情"),
    OPTIMIZER_HERO("英雄配装"),
}

private data class OrbitRoute(
    val destination: OrbitDestination,
    val detail: DetailRoute?,
) {
    val title: String
        get() = detail?.title ?: destination.label
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OrbitApp(
    state: MainUiState,
    onBuyCovenantChanged: (Boolean) -> Unit,
    onBuyMysticChanged: (Boolean) -> Unit,
    onMaxRefreshChanged: (Int) -> Unit,
    onThresholdChanged: (Double) -> Unit,
    onHuntDungeonChanged: (com.e7orbit.model.HuntDungeon) -> Unit,
    onHuntDifficultyChanged: (com.e7orbit.model.HuntDifficulty) -> Unit,
    onHuntManagedBattleChanged: (Boolean) -> Unit,
    onHuntRunCountChanged: (Int) -> Unit,
    onHuntEnergyRefillChanged: (com.e7orbit.model.HuntEnergyRefill) -> Unit,
    onEnableAccessibility: () -> Unit,
    onRefreshEnvironment: () -> Unit,
    onPrepareShop: () -> Unit,
    onPauseOrResumeShop: () -> Unit,
    onStopShop: () -> Unit,
    onPrepareHunt: () -> Unit,
    onPauseOrResumeHunt: () -> Unit,
    onStopHunt: () -> Unit,
    onStartVpnCapture: () -> Unit,
    onStopVpnCapture: () -> Unit,
    onLoadData: (Boolean) -> Unit,
    onDataSectionChanged: (DataSection) -> Unit,
    onDataQueryChanged: (String) -> Unit,
    onImportGear: () -> Unit,
    onExportGear: () -> Unit,
    onSelectHero: (String) -> Unit,
    onSelectArtifact: (String) -> Unit,
    onRtaSeasonChanged: (String) -> Unit,
    onRtaTierChanged: (com.e7orbit.data.RtaTier) -> Unit,
    onRetryHeroRta: () -> Unit,
    onOptimizerContentChanged: (com.e7orbit.optimizer.OptimizerContent) -> Unit,
    onHeroBuildSortChanged: (com.e7orbit.optimizer.HeroBuildSort) -> Unit,
    onGearSetToggled: (String) -> Unit,
    onGearMainStatToggled: (String) -> Unit,
    onGearSubstatToggled: (String) -> Unit,
    onGearMinimumScoreChanged: (Int) -> Unit,
    onGearSortChanged: (com.e7orbit.optimizer.GearInventorySort) -> Unit,
    onClearGearFilters: () -> Unit,
    onCreatePlan: (String) -> Unit,
    onCopyPlan: (String) -> Unit,
    onSelectPlan: (String) -> Unit,
    onDeletePlan: () -> Unit,
    onExportPlan: (com.e7orbit.optimizer.EquipmentPlan) -> Unit,
    onEquippedHeroSelected: (Long) -> Unit,
    onOptimizerMetricChanged: (com.e7orbit.optimizer.OptimizerMetric) -> Unit,
    onOptimizerMinimumChanged: (com.e7orbit.optimizer.OptimizerStat, Int) -> Unit,
    onOptimizerRequiredSetToggled: (String) -> Unit,
    onOptimizerAllowLockedChanged: (Boolean) -> Unit,
    onOptimizerAllowEquippedChanged: (Boolean) -> Unit,
    onOptimizerOnlyMaxedChanged: (Boolean) -> Unit,
    onStartOptimizer: () -> Unit,
    onStopOptimizer: () -> Unit,
    onApplyOptimizerResult: (com.e7orbit.optimizer.OptimizedBuild) -> Unit,
) {
    var destinationName by rememberSaveable { mutableStateOf(OrbitDestination.HOME.name) }
    var detailName by rememberSaveable { mutableStateOf<String?>(null) }
    val destination = OrbitDestination.valueOf(destinationName)
    val detail = detailName?.let(DetailRoute::valueOf)
    val route = OrbitRoute(destination, detail)
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val fastSpatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<IntOffset>()
    val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val topAppBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    BackHandler(enabled = detail != null) { detailName = null }
    LaunchedEffect(destination) {
        if (destination == OrbitDestination.DATA || destination == OrbitDestination.OPTIMIZER) {
            onLoadData(false)
        }
    }
    LaunchedEffect(route) {
        topAppBarScrollBehavior.state.contentOffset = 0f
    }

    fun openDestination(target: OrbitDestination) {
        detailName = null
        destinationName = target.name
    }

    fun openTask(task: AutomationTask) {
        detailName = when (task) {
            AutomationTask.SHOP -> DetailRoute.SHOP.name
            AutomationTask.HUNT -> DetailRoute.HUNT.name
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (detail == DetailRoute.HERO ||
                (detail == null && destination == OrbitDestination.DATA)
            ) {
                // Hero detail and the catalog search surface provide their own top affordances.
            } else if (destination == OrbitDestination.OPTIMIZER && detail == null) {
                OptimizerPlanTopBar(
                    plans = state.optimizer.plans,
                    selectedPlan = state.optimizer.selectedPlan,
                    importing = state.vpnCapture.importPhase == GearImportPhase.PARSING,
                    canCreate = state.data.gears.isNotEmpty(),
                    onCreate = onCreatePlan,
                    onCopy = onCopyPlan,
                    onSelect = onSelectPlan,
                    onDelete = onDeletePlan,
                    onExport = onExportPlan,
                    onImportGear = onImportGear,
                    onExportGear = onExportGear,
                    scrollBehavior = topAppBarScrollBehavior,
                )
            } else {
                OrbitTopAppBar(
                    title = route.title,
                    showBack = detail != null,
                    scrollBehavior = topAppBarScrollBehavior,
                    onBack = { detailName = null },
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = detail == null,
                enter = slideInVertically(
                    animationSpec = spatialSpec,
                    initialOffsetY = { it },
                ),
                exit = slideOutVertically(
                    animationSpec = fastSpatialSpec,
                    targetOffsetY = { it },
                ),
                label = "primary navigation visibility",
            ) {
                OrbitNavigationBar(
                    selected = destination,
                    onSelected = ::openDestination,
                )
            }
        },
    ) { contentPadding ->
        SharedTransitionLayout(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            val sharedTransitionScope: SharedTransitionScope = this
            val contentBottomPadding = contentPadding.calculateBottomPadding()
            AnimatedContent(
                targetState = route,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    orbitContentTransform(
                        initial = initialState,
                        target = targetState,
                        spatialSpec = spatialSpec,
                        effectsSpec = effectsSpec,
                    )
                },
                contentKey = { it },
                label = "page transition",
            ) { animatedRoute ->
                val animatedVisibilityScope: AnimatedVisibilityScope = this@AnimatedContent
                val screenModifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                // Detail screens without a top app bar must also escape the top inset.
                val fullScreenModifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(bottom = contentBottomPadding)
                when (animatedRoute.detail) {
                    DetailRoute.SHOP -> ShopTaskScreen(
                        state = state,
                        modifier = screenModifier,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        onBuyCovenantChanged = onBuyCovenantChanged,
                        onBuyMysticChanged = onBuyMysticChanged,
                        onMaxRefreshChanged = onMaxRefreshChanged,
                        onThresholdChanged = onThresholdChanged,
                        onPrepare = onPrepareShop,
                        onPauseOrResume = onPauseOrResumeShop,
                        onStop = onStopShop,
                    )

                    DetailRoute.HUNT -> HuntTaskScreen(
                        state = state,
                        modifier = screenModifier,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        onDungeonChanged = onHuntDungeonChanged,
                        onDifficultyChanged = onHuntDifficultyChanged,
                        onManagedBattleChanged = onHuntManagedBattleChanged,
                        onRunCountChanged = onHuntRunCountChanged,
                        onEnergyRefillChanged = onHuntEnergyRefillChanged,
                        onPrepare = onPrepareHunt,
                        onPauseOrResume = onPauseOrResumeHunt,
                        onStop = onStopHunt,
                    )

                    DetailRoute.HERO -> HeroDetailScreen(
                        hero = state.data.heroes.firstOrNull {
                            it.code == state.data.selectedHeroCode
                        },
                        rta = state.data.rta,
                        modifier = fullScreenModifier,
                        onBack = { detailName = null },
                        onSeasonChanged = onRtaSeasonChanged,
                        onTierChanged = onRtaTierChanged,
                        onRetryRta = onRetryHeroRta,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                    )

                    DetailRoute.ARTIFACT -> ArtifactDetailScreen(
                        artifact = state.data.artifacts.firstOrNull {
                            it.code == state.data.selectedArtifactCode
                        },
                        modifier = screenModifier,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                    )

                    DetailRoute.OPTIMIZER_HERO -> OptimizerHeroDetailScreen(
                        state = state,
                        modifier = screenModifier,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        onMetricChanged = onOptimizerMetricChanged,
                        onMinimumChanged = onOptimizerMinimumChanged,
                        onRequiredSetToggled = onOptimizerRequiredSetToggled,
                        onAllowLockedChanged = onOptimizerAllowLockedChanged,
                        onAllowEquippedChanged = onOptimizerAllowEquippedChanged,
                        onOnlyMaxedChanged = onOptimizerOnlyMaxedChanged,
                        onStart = onStartOptimizer,
                        onStop = onStopOptimizer,
                        onApplyResult = onApplyOptimizerResult,
                    )

                    null -> when (animatedRoute.destination) {
                        OrbitDestination.HOME -> HomeScreen(
                            state = state,
                            modifier = screenModifier,
                            onOpenTasks = { openDestination(OrbitDestination.TASKS) },
                            onOpenTask = ::openTask,
                            onPauseOrResumeShop = onPauseOrResumeShop,
                            onStopShop = onStopShop,
                            onPauseOrResumeHunt = onPauseOrResumeHunt,
                            onStopHunt = onStopHunt,
                            onEnableAccessibility = onEnableAccessibility,
                            onStartVpnCapture = onStartVpnCapture,
                            onStopVpnCapture = onStopVpnCapture,
                        )

                        OrbitDestination.TASKS -> TaskListScreen(
                            state = state,
                            modifier = screenModifier,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            onOpenTask = ::openTask,
                        )

                        OrbitDestination.DATA -> DataBrowserScreen(
                            data = state.data,
                            modifier = screenModifier,
                            onSectionChanged = onDataSectionChanged,
                            onQueryChanged = onDataQueryChanged,
                            onSelectHero = { code ->
                                onSelectHero(code)
                                detailName = DetailRoute.HERO.name
                            },
                            onSelectArtifact = { code ->
                                onSelectArtifact(code)
                                detailName = DetailRoute.ARTIFACT.name
                            },
                            onLoad = { onLoadData(true) },
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                        )

                        OrbitDestination.OPTIMIZER -> OptimizerScreen(
                            state = state,
                            modifier = screenModifier,
                            onContentChanged = onOptimizerContentChanged,
                            onHeroSortChanged = onHeroBuildSortChanged,
                            onGearSetToggled = onGearSetToggled,
                            onGearMainStatToggled = onGearMainStatToggled,
                            onGearSubstatToggled = onGearSubstatToggled,
                            onGearMinimumScoreChanged = onGearMinimumScoreChanged,
                            onGearSortChanged = onGearSortChanged,
                            onClearGearFilters = onClearGearFilters,
                            onHeroSelected = { instanceId ->
                                onEquippedHeroSelected(instanceId)
                                detailName = DetailRoute.OPTIMIZER_HERO.name
                            },
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                        )

                        OrbitDestination.SETTINGS -> SettingsScreen(
                            state = state,
                            modifier = screenModifier,
                            onEnableAccessibility = onEnableAccessibility,
                            onRefreshEnvironment = onRefreshEnvironment,
                            onRefreshData = { onLoadData(true) },
                        )
                    }
                }
            }
        }
    }
}

private fun orbitContentTransform(
    initial: OrbitRoute,
    target: OrbitRoute,
    spatialSpec: FiniteAnimationSpec<IntOffset>,
    effectsSpec: FiniteAnimationSpec<Float>,
): ContentTransform {
    val usesContainerTransform = when (initial.destination) {
        OrbitDestination.TASKS ->
            target.destination == OrbitDestination.TASKS &&
                ((initial.detail == null && target.detail.isTaskDetail()) ||
                    (target.detail == null && initial.detail.isTaskDetail()))

        OrbitDestination.DATA ->
            target.destination == OrbitDestination.DATA &&
                ((initial.detail == null && target.detail.isCatalogDetail()) ||
                    (target.detail == null && initial.detail.isCatalogDetail()))

        OrbitDestination.OPTIMIZER ->
            target.destination == OrbitDestination.OPTIMIZER &&
                ((initial.detail == null && target.detail == DetailRoute.OPTIMIZER_HERO) ||
                    (target.detail == null && initial.detail == DetailRoute.OPTIMIZER_HERO))

        else -> false
    }
    if (usesContainerTransform) {
        // Stagger the two fades: outgoing page fades first, incoming page waits
        // until the shared element lands before revealing its non-shared content.
        // A simultaneous fadeOut+fadeIn would dip the shared element's combined
        // alpha below 1 mid-flight and produce a visible flicker.
        val exit = fadeOut(animationSpec = effectsSpec)
        val enter = fadeIn(
            animationSpec = tween(
                durationMillis = ContainerTransformFadeMillis,
                delayMillis = ContainerTransformFadeMillis,
            ),
        )
        return (enter togetherWith exit).apply { targetContentZIndex = 1f }
    }

    val movesForward = when {
        initial.detail == null && target.detail != null -> true
        initial.detail != null && target.detail == null -> false
        else -> target.destination.ordinal >= initial.destination.ordinal
    }
    val direction = if (movesForward) 1 else -1
    val enter = slideInHorizontally(
        animationSpec = spatialSpec,
        initialOffsetX = { width -> direction * width },
    ) + fadeIn(animationSpec = effectsSpec)
    val exit = slideOutHorizontally(
        animationSpec = spatialSpec,
        targetOffsetX = { width -> -direction * width },
    ) + fadeOut(animationSpec = effectsSpec)
    return (enter togetherWith exit).apply { targetContentZIndex = 1f }
}

/**
 * Duration of each half of the staggered container-transform fade. Matches the M3
 * shared-element flight time (~450ms slow spatial spec) so the incoming page only
 * reveals its non-shared content after the shared element has landed.
 */
private const val ContainerTransformFadeMillis = 450

private fun DetailRoute?.isTaskDetail(): Boolean =
    this == DetailRoute.SHOP || this == DetailRoute.HUNT

private fun DetailRoute?.isCatalogDetail(): Boolean =
    this == DetailRoute.HERO || this == DetailRoute.ARTIFACT

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrbitTopAppBar(
    title: String,
    showBack: Boolean,
    scrollBehavior: TopAppBarScrollBehavior,
    onBack: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = if (title == OrbitDestination.HOME.label) "E7 Orbit" else title,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        navigationIcon = {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = "返回",
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        scrollBehavior = scrollBehavior,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptimizerPlanTopBar(
    plans: List<com.e7orbit.optimizer.EquipmentPlan>,
    selectedPlan: com.e7orbit.optimizer.EquipmentPlan?,
    importing: Boolean,
    canCreate: Boolean,
    onCreate: (String) -> Unit,
    onCopy: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDelete: () -> Unit,
    onExport: (com.e7orbit.optimizer.EquipmentPlan) -> Unit,
    onImportGear: () -> Unit,
    onExportGear: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    var planMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var moreMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var nameDialogAction by rememberSaveable { mutableStateOf<String?>(null) }
    var planName by rememberSaveable { mutableStateOf("") }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    TopAppBar(
        title = {
            Box {
                Row(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.large)
                        .clickable { planMenuExpanded = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = "配装",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = selectedPlan?.name ?: "默认方案",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_drop_down),
                        contentDescription = "选择方案",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = planMenuExpanded,
                    onDismissRequest = { planMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("默认方案") },
                        leadingIcon = {
                            if (selectedPlan == null) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = "已选择",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                        onClick = {
                            planMenuExpanded = false
                            onSelect("")
                        },
                    )
                    plans.forEach { plan ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    plan.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = {
                                if (plan.id == selectedPlan?.id) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_check),
                                        contentDescription = "已选择",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                            onClick = {
                                planMenuExpanded = false
                                onSelect(plan.id)
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("新建方案…") },
                        enabled = canCreate,
                        onClick = {
                            planMenuExpanded = false
                            planName = "方案 ${plans.size + 1}"
                            nameDialogAction = "create"
                        },
                    )
                }
            }
        },
        actions = {
            Box {
                IconButton(onClick = { moreMenuExpanded = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = "方案与装备操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = moreMenuExpanded,
                    onDismissRequest = { moreMenuExpanded = false },
                ) {
                    if (selectedPlan != null) {
                        DropdownMenuItem(
                            text = { Text("复制方案…") },
                            onClick = {
                                moreMenuExpanded = false
                                planName = "${selectedPlan.name} 副本"
                                nameDialogAction = "copy"
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导出方案 gear.txt") },
                            onClick = {
                                moreMenuExpanded = false
                                onExport(selectedPlan)
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text("删除方案…", color = MaterialTheme.colorScheme.error)
                            },
                            onClick = {
                                moreMenuExpanded = false
                                confirmDelete = true
                            },
                        )
                        HorizontalDivider()
                    }
                    DropdownMenuItem(
                        text = { Text(if (importing) "正在导入装备…" else "导入 gear.txt") },
                        enabled = !importing,
                        onClick = {
                            moreMenuExpanded = false
                            onImportGear()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("导出原始 gear.txt") },
                        enabled = canCreate && !importing,
                        onClick = {
                            moreMenuExpanded = false
                            onExportGear()
                        },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        scrollBehavior = scrollBehavior,
    )

    if (nameDialogAction != null) {
        val isCopy = nameDialogAction == "copy"
        AlertDialog(
            onDismissRequest = { nameDialogAction = null },
            title = { Text(if (isCopy) "复制配装方案" else "新建配装方案") },
            text = {
                OutlinedTextField(
                    value = planName,
                    onValueChange = { planName = it.take(40) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("方案名称") },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = planName.trim()
                        if (isCopy) onCopy(name) else onCreate(name)
                        nameDialogAction = null
                    },
                    enabled = planName.isNotBlank(),
                ) {
                    Text(if (isCopy) "复制" else "创建")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { nameDialogAction = null }) { Text("取消") }
            },
        )
    }

    if (confirmDelete && selectedPlan != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除配装方案？") },
            text = { Text("将删除「${selectedPlan.name}」，不会影响游戏当前配装和其他方案。") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun OrbitNavigationBar(
    selected: OrbitDestination,
    onSelected: (OrbitDestination) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        OrbitDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = selected == destination,
                onClick = { onSelected(destination) },
                icon = {
                    Icon(
                        painter = painterResource(destination.icon),
                        contentDescription = null,
                    )
                },
                label = { Text(destination.label) },
            )
        }
    }
}
