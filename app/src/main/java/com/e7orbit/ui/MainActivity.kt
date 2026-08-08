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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.e7orbit.AppGraph
import com.e7orbit.R
import com.e7orbit.capture.MediaProjectionCaptureService
import com.e7orbit.capture.VpnCaptureService
import com.e7orbit.data.GearExportSerializer
import com.e7orbit.ui.theme.E7OrbitTheme
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    private val gearExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val gears = AppGraph.gearImportRepository.state.value.gears
            check(gears.isNotEmpty()) { "没有可导出的装备" }
            val output = contentResolver.openOutputStream(uri, "wt")
                ?: error("无法打开导出文件")
            output.bufferedWriter(Charsets.UTF_8).use {
                it.write(GearExportSerializer.serialize(gears))
            }
            AppGraph.logger.info("gear.export_succeeded", "items" to gears.size, "uri" to uri)
            Toast.makeText(this, "已导出 ${gears.size} 件装备", Toast.LENGTH_LONG).show()
        }.onFailure { error ->
            AppGraph.logger.error("gear.export_failed", error)
            Toast.makeText(this, error.message ?: "装备导出失败", Toast.LENGTH_LONG).show()
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
                    onExportGear = { gearExportLauncher.launch("gear.txt") },
                    onSelectHero = viewModel::selectHero,
                    onSelectArtifact = viewModel::selectArtifact,
                    onRtaSeasonChanged = viewModel::setRtaSeason,
                    onRtaTierChanged = viewModel::setRtaTier,
                    onRetryHeroRta = viewModel::retryHeroRta,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshEnvironment()
    }

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
    DATA("数据", R.drawable.ic_nav_data),
    SETTINGS("设置", R.drawable.ic_nav_settings),
}

internal enum class AutomationTask { SHOP, HUNT }

private enum class DetailRoute(val title: String) {
    SHOP("神秘商店"),
    HUNT("讨伐"),
    HERO("英雄详情"),
    ARTIFACT("神器详情"),
}

private data class OrbitRoute(
    val destination: OrbitDestination,
    val detail: DetailRoute?,
) {
    val title: String
        get() = detail?.title ?: destination.label
}

@OptIn(ExperimentalMaterial3Api::class)
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
    onExportGear: () -> Unit,
    onSelectHero: (String) -> Unit,
    onSelectArtifact: (String) -> Unit,
    onRtaSeasonChanged: (String) -> Unit,
    onRtaTierChanged: (com.e7orbit.data.RtaTier) -> Unit,
    onRetryHeroRta: () -> Unit,
) {
    var destinationName by rememberSaveable { mutableStateOf(OrbitDestination.HOME.name) }
    var detailName by rememberSaveable { mutableStateOf<String?>(null) }
    val destination = OrbitDestination.valueOf(destinationName)
    val detail = detailName?.let(DetailRoute::valueOf)
    val route = OrbitRoute(destination, detail)
    val topAppBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    BackHandler(enabled = detail != null) { detailName = null }
    LaunchedEffect(destination) {
        if (destination == OrbitDestination.DATA) onLoadData(false)
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
            OrbitTopAppBar(
                title = route.title,
                showBack = detail != null,
                showRefresh = detail == null && destination == OrbitDestination.DATA,
                refreshing = state.data.loadState == DataLoadState.LOADING,
                scrollBehavior = topAppBarScrollBehavior,
                onBack = { detailName = null },
                onRefresh = { onLoadData(true) },
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = detail == null,
                enter = slideInVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    initialOffsetY = { it },
                ),
                exit = slideOutVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
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
            AnimatedContent(
                targetState = route,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = { orbitContentTransform(initialState, targetState) },
                contentKey = { it },
                label = "page transition",
            ) { animatedRoute ->
                val animatedVisibilityScope: AnimatedVisibilityScope = this@AnimatedContent
                val screenModifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
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
                        modifier = screenModifier,
                        onSeasonChanged = onRtaSeasonChanged,
                        onTierChanged = onRtaTierChanged,
                        onRetryRta = onRetryHeroRta,
                    )

                    DetailRoute.ARTIFACT -> ArtifactDetailScreen(
                        artifact = state.data.artifacts.firstOrNull {
                            it.code == state.data.selectedArtifactCode
                        },
                        modifier = screenModifier,
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
                            onExportGear = onExportGear,
                            onSelectHero = { code ->
                                onSelectHero(code)
                                detailName = DetailRoute.HERO.name
                            },
                            onSelectArtifact = { code ->
                                onSelectArtifact(code)
                                detailName = DetailRoute.ARTIFACT.name
                            },
                            onLoad = { onLoadData(true) },
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
): ContentTransform {
    val usesTaskContainerTransform =
        initial.destination == OrbitDestination.TASKS &&
            target.destination == OrbitDestination.TASKS &&
            ((initial.detail == null && target.detail.isTaskDetail()) ||
                (target.detail == null && initial.detail.isTaskDetail()))
    if (usesTaskContainerTransform) {
        return (EnterTransition.None togetherWith ExitTransition.None).apply {
            targetContentZIndex = 1f
        }
    }

    val movesForward = when {
        initial.detail == null && target.detail != null -> true
        initial.detail != null && target.detail == null -> false
        else -> target.destination.ordinal >= initial.destination.ordinal
    }
    val direction = if (movesForward) 1 else -1
    val enter = slideInHorizontally(
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        initialOffsetX = { width -> direction * width },
    )
    val exit = slideOutHorizontally(
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        targetOffsetX = { width -> -direction * width },
    )
    return (enter togetherWith exit).apply { targetContentZIndex = 1f }
}

private fun DetailRoute?.isTaskDetail(): Boolean =
    this == DetailRoute.SHOP || this == DetailRoute.HUNT

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrbitTopAppBar(
    title: String,
    showBack: Boolean,
    showRefresh: Boolean,
    refreshing: Boolean,
    scrollBehavior: TopAppBarScrollBehavior,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
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
        actions = {
            if (showRefresh) {
                IconButton(onClick = onRefresh, enabled = !refreshing) {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = "刷新数据",
                        )
                    }
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
