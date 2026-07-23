package com.e7orbit.ui

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationItemIconPosition
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.e7orbit.AppGraph
import com.e7orbit.R
import com.e7orbit.capture.MediaProjectionCaptureService
import com.e7orbit.model.AutomationPhase
import com.e7orbit.model.HuntPhase
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
                    onLoadData = viewModel::loadData,
                    onDataSectionChanged = viewModel::setDataSection,
                    onDataQueryChanged = viewModel::setDataQuery,
                    onSelectHero = viewModel::selectHero,
                    onSelectArtifact = viewModel::selectArtifact,
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
    onLoadData: (Boolean) -> Unit,
    onDataSectionChanged: (DataSection) -> Unit,
    onDataQueryChanged: (String) -> Unit,
    onSelectHero: (String) -> Unit,
    onSelectArtifact: (String) -> Unit,
) {
    var destinationName by rememberSaveable { mutableStateOf(OrbitDestination.HOME.name) }
    var detailName by rememberSaveable { mutableStateOf<String?>(null) }
    val destination = OrbitDestination.valueOf(destinationName)
    val detail = detailName?.let(DetailRoute::valueOf)
    val shopActive = state.automation.isRunning || state.automation.phase == AutomationPhase.PAUSED
    val huntActive = state.huntAutomation.isRunning || state.huntAutomation.phase == HuntPhase.PAUSED

    BackHandler(enabled = detail != null) { detailName = null }
    LaunchedEffect(destination) {
        if (destination == OrbitDestination.DATA) onLoadData(false)
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
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            OrbitTopAppBar(
                title = detail?.title ?: destination.label,
                showBack = detail != null,
                showRefresh = detail == null && destination == OrbitDestination.DATA,
                refreshing = state.data.loadState == DataLoadState.LOADING,
                onBack = { detailName = null },
                onRefresh = { onLoadData(true) },
            )
        },
        bottomBar = {
            if (detail == null) {
                OrbitNavigationBar(
                    selected = destination,
                    onSelected = ::openDestination,
                )
            }
        },
    ) { contentPadding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
        when (detail) {
            DetailRoute.SHOP -> ShopTaskScreen(
                state = state,
                modifier = modifier,
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
                modifier = modifier,
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
                hero = state.data.heroes.firstOrNull { it.code == state.data.selectedHeroCode },
                modifier = modifier,
            )

            DetailRoute.ARTIFACT -> ArtifactDetailScreen(
                artifact = state.data.artifacts.firstOrNull {
                    it.code == state.data.selectedArtifactCode
                },
                modifier = modifier,
            )

            null -> when (destination) {
                OrbitDestination.HOME -> HomeScreen(
                    state = state,
                    modifier = modifier,
                    onOpenTasks = { openDestination(OrbitDestination.TASKS) },
                    onOpenTask = ::openTask,
                    onPauseOrResumeShop = onPauseOrResumeShop,
                    onStopShop = onStopShop,
                    onPauseOrResumeHunt = onPauseOrResumeHunt,
                    onStopHunt = onStopHunt,
                    onEnableAccessibility = onEnableAccessibility,
                )

                OrbitDestination.TASKS -> TaskListScreen(
                    state = state,
                    modifier = modifier,
                    onOpenTask = ::openTask,
                )

                OrbitDestination.DATA -> DataBrowserScreen(
                    data = state.data,
                    modifier = modifier,
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
                )

                OrbitDestination.SETTINGS -> SettingsScreen(
                    state = state,
                    modifier = modifier,
                    onEnableAccessibility = onEnableAccessibility,
                    onRefreshEnvironment = onRefreshEnvironment,
                    onRefreshData = { onLoadData(true) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrbitTopAppBar(
    title: String,
    showBack: Boolean,
    showRefresh: Boolean,
    refreshing: Boolean,
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
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = if (refreshing) "数据更新中" else "刷新数据",
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
}

@Composable
private fun OrbitNavigationBar(
    selected: OrbitDestination,
    onSelected: (OrbitDestination) -> Unit,
) {
    ShortNavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        OrbitDestination.entries.forEach { destination ->
            ShortNavigationBarItem(
                selected = selected == destination,
                onClick = { onSelected(destination) },
                icon = {
                    Icon(
                        painter = painterResource(destination.icon),
                        contentDescription = null,
                    )
                },
                label = { Text(destination.label) },
                iconPosition = NavigationItemIconPosition.Top,
            )
        }
    }
}
