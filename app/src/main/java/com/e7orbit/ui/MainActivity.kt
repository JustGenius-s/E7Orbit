package com.e7orbit.ui

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.e7orbit.AppGraph
import com.e7orbit.R
import com.e7orbit.capture.MediaProjectionCaptureService
import com.e7orbit.model.AutomationPhase
import com.e7orbit.model.AutomationStatus
import com.e7orbit.model.HuntConfig
import com.e7orbit.model.HuntDifficulty
import com.e7orbit.model.HuntDungeon
import com.e7orbit.model.HuntEnergyRefill
import com.e7orbit.model.HuntPhase
import com.e7orbit.model.HuntStatus
import com.e7orbit.model.RunConfig
import com.e7orbit.model.RunSummary
import com.e7orbit.data.E7Artifact
import com.e7orbit.data.E7Hero
import com.e7orbit.data.E7HeroStats
import com.e7orbit.ui.theme.E7OrbitTheme
import com.e7orbit.ui.theme.OrbitSuccess
import com.e7orbit.ui.theme.OrbitWarning
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
                AppGraph.projectionCapture.isReady
                    .filter { it }
                    .first()
            } != null
            viewModel.refreshEnvironment()
            if (ready) {
                preparePendingAutomation()
            } else if (!ready) {
                AppGraph.logger.error("projection.start_timeout")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            E7OrbitTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                OrbitDashboard(
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
                    onPrepare = { requestProjection(PendingAutomation.SHOP) },
                    onPauseOrResume = viewModel::pauseOrResume,
                    onStop = viewModel::stop,
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

    private enum class PendingAutomation {
        SHOP,
        HUNT,
    }
}

@Composable
private fun OrbitDashboard(
    state: MainUiState,
    onBuyCovenantChanged: (Boolean) -> Unit,
    onBuyMysticChanged: (Boolean) -> Unit,
    onMaxRefreshChanged: (Int) -> Unit,
    onThresholdChanged: (Double) -> Unit,
    onHuntDungeonChanged: (HuntDungeon) -> Unit,
    onHuntDifficultyChanged: (HuntDifficulty) -> Unit,
    onHuntManagedBattleChanged: (Boolean) -> Unit,
    onHuntRunCountChanged: (Int) -> Unit,
    onHuntEnergyRefillChanged: (HuntEnergyRefill) -> Unit,
    onEnableAccessibility: () -> Unit,
    onPrepare: () -> Unit,
    onPauseOrResume: () -> Unit,
    onStop: () -> Unit,
    onPrepareHunt: () -> Unit,
    onPauseOrResumeHunt: () -> Unit,
    onStopHunt: () -> Unit,
    onLoadData: (Boolean) -> Unit,
    onDataSectionChanged: (DataSection) -> Unit,
    onDataQueryChanged: (String) -> Unit,
    onSelectHero: (String) -> Unit,
    onSelectArtifact: (String) -> Unit,
) {
    var rootSection by rememberSaveable { androidx.compose.runtime.mutableStateOf(RootSection.AUTOMATION) }
    var automationTask by rememberSaveable {
        androidx.compose.runtime.mutableStateOf(AutomationTask.SHOP)
    }
    val shopActive = state.automation.isRunning ||
        state.automation.phase == AutomationPhase.PAUSED
    val huntActive = state.huntAutomation.isRunning ||
        state.huntAutomation.phase == HuntPhase.PAUSED
    val visibleTask = when {
        shopActive -> AutomationTask.SHOP
        huntActive -> AutomationTask.HUNT
        else -> automationTask
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(contentPadding)
                .padding(horizontal = 28.dp, vertical = 18.dp),
        ) {
            OrbitTopBar(
                section = rootSection,
                onSectionChanged = { section ->
                    rootSection = section
                    if (section == RootSection.DATA) onLoadData(false)
                },
            )
            Spacer(Modifier.height(18.dp))
            when (rootSection) {
                RootSection.AUTOMATION -> {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1.15f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            WorkspaceHeading(
                                title = "自动化任务",
                                detail = if (shopActive || huntActive) {
                                    "运行中 · 配置已锁定"
                                } else {
                                    "配置可编辑"
                                },
                            )
                            TaskSelector(
                                selected = visibleTask,
                                enabled = !shopActive && !huntActive,
                                onSelected = { task -> automationTask = task },
                            )
                            when (visibleTask) {
                                AutomationTask.SHOP -> AutomationCard(
                                    config = state.config,
                                    canStart = state.environment.canPrepare &&
                                        state.automation.templatesReady &&
                                        state.config.hasPurchaseTarget &&
                                        !state.automation.isRunning &&
                                        !state.huntAutomation.isRunning &&
                                        state.huntAutomation.phase != HuntPhase.PAUSED,
                                    automation = state.automation,
                                    onBuyCovenantChanged = onBuyCovenantChanged,
                                    onBuyMysticChanged = onBuyMysticChanged,
                                    onMaxRefreshChanged = onMaxRefreshChanged,
                                    onThresholdChanged = onThresholdChanged,
                                    onPrepare = onPrepare,
                                    onPauseOrResume = onPauseOrResume,
                                    onStop = onStop,
                                )

                                AutomationTask.HUNT -> HuntCard(
                                    config = state.huntConfig,
                                    automation = state.huntAutomation,
                                    canStart = state.environment.canPrepare &&
                                        state.huntAutomation.templatesReady &&
                                        !state.huntAutomation.isRunning &&
                                        state.huntAutomation.phase != HuntPhase.PAUSED &&
                                        !state.automation.isRunning &&
                                        state.automation.phase != AutomationPhase.PAUSED,
                                    onDungeonChanged = onHuntDungeonChanged,
                                    onDifficultyChanged = onHuntDifficultyChanged,
                                    onManagedBattleChanged = onHuntManagedBattleChanged,
                                    onRunCountChanged = onHuntRunCountChanged,
                                    onEnergyRefillChanged = onHuntEnergyRefillChanged,
                                    onPrepare = onPrepareHunt,
                                    onPauseOrResume = onPauseOrResumeHunt,
                                    onStop = onStopHunt,
                                )
                            }
                        }
                        Column(
                            modifier = Modifier
                                .weight(0.85f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            EnvironmentCard(
                                environment = state.environment,
                                onEnableAccessibility = onEnableAccessibility,
                            )
                            LastRunCard(state.lastSummary)
                        }
                    }
                }

                RootSection.DATA -> DataCenter(
                    data = state.data,
                    onRefresh = { onLoadData(true) },
                    onSectionChanged = onDataSectionChanged,
                    onQueryChanged = onDataQueryChanged,
                    onSelectHero = onSelectHero,
                    onSelectArtifact = onSelectArtifact,
                )
            }
        }
    }
}

private enum class RootSection {
    AUTOMATION,
    DATA,
}

private enum class AutomationTask {
    SHOP,
    HUNT,
}

@Composable
private fun OrbitTopBar(
    section: RootSection,
    onSectionChanged: (RootSection) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "E7 Orbit",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (section == RootSection.AUTOMATION) "游戏自动化控制台" else "英雄与神器数据中心",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceButton(
                label = "自动化",
                selected = section == RootSection.AUTOMATION,
                onClick = { onSectionChanged(RootSection.AUTOMATION) },
                modifier = Modifier.width(108.dp),
            )
            ChoiceButton(
                label = "数据中心",
                selected = section == RootSection.DATA,
                onClick = { onSectionChanged(RootSection.DATA) },
                modifier = Modifier.width(108.dp),
            )
        }
    }
}

@Composable
private fun WorkspaceHeading(
    title: String,
    detail: String,
) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TaskSelector(
    selected: AutomationTask,
    enabled: Boolean,
    onSelected: (AutomationTask) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChoiceButton(
            label = "神秘商店",
            selected = selected == AutomationTask.SHOP,
            enabled = enabled,
            onClick = { onSelected(AutomationTask.SHOP) },
            modifier = Modifier.weight(1f),
        )
        ChoiceButton(
            label = "讨伐",
            selected = selected == AutomationTask.HUNT,
            enabled = enabled,
            onClick = { onSelected(AutomationTask.HUNT) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DataCenter(
    data: DataUiState,
    onRefresh: () -> Unit,
    onSectionChanged: (DataSection) -> Unit,
    onQueryChanged: (String) -> Unit,
    onSelectHero: (String) -> Unit,
    onSelectArtifact: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "数据浏览",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    dataSourceSummary(data),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(
                onClick = onRefresh,
                enabled = data.loadState != DataLoadState.LOADING,
                colors = whiteOutlinedButtonColors(),
                border = whiteButtonBorder(),
            ) {
                Text(if (data.loadState == DataLoadState.LOADING) "更新中" else "刷新数据")
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChoiceButton(
                label = "英雄",
                selected = data.section == DataSection.HEROES,
                onClick = { onSectionChanged(DataSection.HEROES) },
                modifier = Modifier.width(110.dp),
            )
            ChoiceButton(
                label = "神器",
                selected = data.section == DataSection.ARTIFACTS,
                onClick = { onSectionChanged(DataSection.ARTIFACTS) },
                modifier = Modifier.width(110.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        when (data.loadState) {
            DataLoadState.IDLE -> DataEmptyState(onLoad = onRefresh)
            DataLoadState.LOADING -> DataLoadingState()
            DataLoadState.ERROR -> DataErrorState(
                message = data.errorMessage ?: "公开数据暂时不可用",
                onRetry = onRefresh,
            )

            DataLoadState.READY -> when (data.section) {
                DataSection.HEROES -> HeroBrowser(
                    data = data,
                    modifier = Modifier.weight(1f),
                    onQueryChanged = onQueryChanged,
                    onSelect = onSelectHero,
                )

                DataSection.ARTIFACTS -> ArtifactBrowser(
                    data = data,
                    modifier = Modifier.weight(1f),
                    onQueryChanged = onQueryChanged,
                    onSelect = onSelectArtifact,
                )
            }
        }
    }
}

private fun dataSourceSummary(data: DataUiState): String = when (data.loadState) {
    DataLoadState.IDLE -> "官方 Stove + Fribbels"
    DataLoadState.LOADING -> "正在读取官方与社区静态数据"
    DataLoadState.ERROR -> "上次数据读取失败"
    DataLoadState.READY -> "${data.heroes.size} 位英雄 · ${data.artifacts.size} 件神器 · 官方 Stove + Fribbels"
}

@Composable
private fun HeroBrowser(
    data: DataUiState,
    modifier: Modifier = Modifier,
    onQueryChanged: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    val filteredHeroes = data.heroes.filter { hero ->
        data.query.isBlank() || hero.name.contains(data.query, ignoreCase = true) ||
            hero.code.contains(data.query, ignoreCase = true)
    }
    val selected = data.heroes.firstOrNull { it.code == data.selectedHeroCode }
        ?: filteredHeroes.firstOrNull()

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(0.78f)
                .fillMaxHeight(),
        ) {
            OutlinedTextField(
                value = data.query,
                onValueChange = onQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("搜索英雄") },
                placeholder = { Text("名称或编码") },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${filteredHeroes.size} 个结果",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(filteredHeroes, key = { it.code }) { hero ->
                    DataListItem(
                        title = hero.name,
                        subtitle = "${hero.attributeLabel()} · ${hero.roleLabel()} · ${hero.code}",
                        selected = hero.code == data.selectedHeroCode,
                        leading = {
                            RemoteImage(
                                url = hero.assets.iconUrl,
                                contentDescription = "${hero.name}头像",
                                modifier = Modifier
                                    .width(38.dp)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                        },
                        onClick = { onSelect(hero.code) },
                    )
                }
            }
        }
        HeroDetail(
            hero = selected,
            modifier = Modifier
                .weight(1.22f)
                .fillMaxHeight(),
        )
    }
}

@Composable
private fun ArtifactBrowser(
    data: DataUiState,
    modifier: Modifier = Modifier,
    onQueryChanged: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    val filteredArtifacts = data.artifacts.filter { artifact ->
        data.query.isBlank() || artifact.name.contains(data.query, ignoreCase = true) ||
            artifact.code.contains(data.query, ignoreCase = true)
    }
    val selected = data.artifacts.firstOrNull { it.code == data.selectedArtifactCode }
        ?: filteredArtifacts.firstOrNull()

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(0.78f)
                .fillMaxHeight(),
        ) {
            OutlinedTextField(
                value = data.query,
                onValueChange = onQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("搜索神器") },
                placeholder = { Text("名称或编码") },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${filteredArtifacts.size} 个结果",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(filteredArtifacts, key = { it.code }) { artifact ->
                    DataListItem(
                        title = artifact.name,
                        subtitle = artifact.rarity ?: artifact.code,
                        selected = artifact.code == data.selectedArtifactCode,
                        onClick = { onSelect(artifact.code) },
                    )
                }
            }
        }
        ArtifactDetail(
            artifact = selected,
            modifier = Modifier
                .weight(1.22f)
                .fillMaxHeight(),
        )
    }
}

@Composable
private fun DataListItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surface
            },
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.let { content ->
                content()
                Spacer(Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun HeroDetail(
    hero: E7Hero?,
    modifier: Modifier = Modifier,
) {
    OrbitCard(modifier = modifier) {
        if (hero == null) {
            EmptyDetail("没有匹配的英雄")
            return@OrbitCard
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteImage(
                url = hero.assets.iconUrl,
                contentDescription = "${hero.name}头像",
                modifier = Modifier
                    .width(84.dp)
                    .height(84.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    hero.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${hero.code} · ${hero.attributeLabel()} · ${hero.roleLabel()}" +
                        (hero.rarity?.let { " · ${it} 星" } ?: ""),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                hero.zodiac?.takeIf { it.isNotBlank() }?.let { zodiac ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "星座 · $zodiac",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        hero.assets.thumbnailUrl?.let { thumbnailUrl ->
            Spacer(Modifier.height(14.dp))
            RemoteImage(
                url = thumbnailUrl,
                contentDescription = "${hero.name}角色图",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text("六星满觉基础属性", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        HeroStatsGrid(hero.stats)
    }
}

@Composable
private fun HeroStatsGrid(stats: E7HeroStats?) {
    if (stats == null) {
        Text("暂无 Fribbels 属性数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.weight(1f)) {
            MetricRow("攻击", stats.attack.displayOrDash())
            MetricRow("生命", stats.health.displayOrDash())
            MetricRow("防御", stats.defense.displayOrDash())
            MetricRow("速度", stats.speed.displayOrDash())
        }
        Column(Modifier.weight(1f)) {
            MetricRow("暴击", stats.criticalChance.percentOrDash())
            MetricRow("暴伤", stats.criticalDamage.percentOrDash())
            MetricRow("效果命中", stats.effectiveness.percentOrDash())
            MetricRow("效果抗性", stats.effectResistance.percentOrDash())
        }
    }
    Spacer(Modifier.height(6.dp))
    MetricRow("战斗力", stats.combatPower.displayOrDash())
}

@Composable
private fun ArtifactDetail(
    artifact: E7Artifact?,
    modifier: Modifier = Modifier,
) {
    OrbitCard(modifier = modifier) {
        if (artifact == null) {
            EmptyDetail("没有匹配的神器")
            return@OrbitCard
        }
        Text(artifact.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            listOfNotNull(artifact.code, artifact.rarity, artifact.role?.roleLabel()).joinToString(" · "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(14.dp))
        Text("满级加成", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1f)) {
                MetricRow("攻击", artifact.attack.displayOrDash())
                MetricRow("生命", artifact.health.displayOrDash())
            }
            Column(Modifier.weight(1f)) {
                MetricRow("防御", artifact.defense.displayOrDash())
                MetricRow("适用职业", artifact.role?.roleLabel() ?: "—")
            }
        }
        artifact.description?.takeIf { it.isNotBlank() }?.let { description ->
            Spacer(Modifier.height(12.dp))
            Text("效果描述", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyDetail(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DataEmptyState(onLoad: () -> Unit) {
    OrbitCard {
        Text("数据尚未加载", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "从官方 Stove 和 Fribbels 读取英雄与神器资料。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onLoad,
            colors = whiteButtonColors(),
            border = whiteButtonBorder(),
        ) {
            Text("加载数据")
        }
    }
}

@Composable
private fun DataLoadingState() {
    OrbitCard {
        Text("正在读取数据", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        repeat(5) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        }
    }
}

@Composable
private fun DataErrorState(
    message: String,
    onRetry: () -> Unit,
) {
    OrbitCard {
        Text("数据读取失败", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(14.dp))
        OutlinedButton(
            onClick = onRetry,
            colors = whiteOutlinedButtonColors(),
            border = whiteButtonBorder(),
        ) {
            Text("重新尝试")
        }
    }
}

private fun E7Hero.attributeLabel(): String = when (attribute.lowercase()) {
    "fire" -> "火"
    "ice", "water" -> "冰"
    "earth", "wind" -> "木"
    "light" -> "光"
    "dark" -> "暗"
    else -> attribute
}

private fun E7Hero.roleLabel(): String = when (role.lowercase()) {
    "knight" -> "骑士"
    "warrior" -> "战士"
    "ranger" -> "射手"
    "mage" -> "法师"
    "assassin" -> "盗贼"
    "manauser", "soul_weaver" -> "奶妈"
    else -> role
}

private fun String.roleLabel(): String = when (lowercase()) {
    "knight" -> "骑士"
    "warrior" -> "战士"
    "ranger" -> "射手"
    "mage" -> "法师"
    "assassin" -> "盗贼"
    "manauser", "soul_weaver" -> "奶妈"
    else -> this
}

private fun Int?.displayOrDash(): String = this?.toString() ?: "—"

private fun Int?.percentOrDash(): String = this?.let { "$it%" } ?: "—"

@Composable
private fun AutomationCard(
    config: RunConfig,
    canStart: Boolean,
    automation: AutomationStatus,
    onBuyCovenantChanged: (Boolean) -> Unit,
    onBuyMysticChanged: (Boolean) -> Unit,
    onMaxRefreshChanged: (Int) -> Unit,
    onThresholdChanged: (Double) -> Unit,
    onPrepare: () -> Unit,
    onPauseOrResume: () -> Unit,
    onStop: () -> Unit,
) {
    OrbitCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_secret_shop),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(38.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(7.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "自动神秘商店",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(14.dp))
        ToggleRow(
            title = "购买誓约书签",
            subtitle = "仅在商品与购买按钮同时匹配时执行",
            checked = config.buyCovenantBookmarks,
            onCheckedChange = onBuyCovenantChanged,
        )
        ToggleRow(
            title = "购买神秘奖牌",
            subtitle = "确认弹窗会再次验证商品类型",
            checked = config.buyMysticMedals,
            onCheckedChange = onBuyMysticChanged,
        )
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text("最大刷新次数", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = config.maxRefreshes.toString(),
            onValueChange = { raw ->
                raw.filter(Char::isDigit).toIntOrNull()?.let(onMaxRefreshChanged)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = { Text("范围 1–10,000；达到上限后安全停止") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("识别阈值", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "${(config.matchThreshold * 100).toInt()}%",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
        }
        Slider(
            value = config.matchThreshold.toFloat(),
            onValueChange = { onThresholdChanged(it.toDouble()) },
            valueRange = 0.85f..0.98f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.onSurface,
                activeTrackColor = MaterialTheme.colorScheme.onSurface,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                activeTickColor = MaterialTheme.colorScheme.surface,
                inactiveTickColor = MaterialTheme.colorScheme.outline,
            ),
        )
        Spacer(Modifier.height(12.dp))
        if (automation.isRunning || automation.phase == AutomationPhase.PAUSED) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onPauseOrResume,
                    modifier = Modifier.weight(1f),
                    colors = whiteButtonColors(),
                    border = whiteButtonBorder(),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                ) {
                    Text(if (automation.phase == AutomationPhase.PAUSED) "继续" else "暂停")
                }
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    colors = whiteOutlinedButtonColors(danger = true),
                    border = whiteButtonBorder(danger = true),
                ) {
                    Text("停止")
                }
            }
        } else {
            Button(
                onClick = onPrepare,
                enabled = canStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = whiteButtonColors(),
                border = whiteButtonBorder(),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            ) {
                Text("准备运行", fontWeight = FontWeight.Bold)
            }
        }
        if (automation.phase != AutomationPhase.IDLE) {
            Spacer(Modifier.height(10.dp))
            Text(
                automation.message,
                color = if (automation.phase == AutomationPhase.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun HuntCard(
    config: HuntConfig,
    automation: HuntStatus,
    canStart: Boolean,
    onDungeonChanged: (HuntDungeon) -> Unit,
    onDifficultyChanged: (HuntDifficulty) -> Unit,
    onManagedBattleChanged: (Boolean) -> Unit,
    onRunCountChanged: (Int) -> Unit,
    onEnergyRefillChanged: (HuntEnergyRefill) -> Unit,
    onPrepare: () -> Unit,
    onPauseOrResume: () -> Unit,
    onStop: () -> Unit,
) {
    OrbitCard {
        Text(
            "自动讨伐",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(14.dp))
        Text("地下城", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        HuntDungeon.entries.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { dungeon ->
                    DungeonChoiceButton(
                        dungeon = dungeon,
                        selected = config.dungeon == dungeon,
                        onClick = { onDungeonChanged(dungeon) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text("讨伐难度", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HuntDifficulty.entries.forEach { difficulty ->
                ChoiceButton(
                    label = difficulty.displayName(includeAvailability = true),
                    selected = config.difficulty == difficulty,
                    enabled = difficulty.isSupported,
                    onClick = { onDifficultyChanged(difficulty) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        ToggleRow(
            title = "托管战斗",
            subtitle = "当前仅支持托管战斗；普通战斗开发中",
            checked = config.managedBattle,
            enabled = false,
            onCheckedChange = onManagedBattleChanged,
        )
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text("讨伐次数", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = config.runCount.toString(),
            onValueChange = { raw ->
                raw.filter(Char::isDigit).toIntOrNull()?.let(onRunCountChanged)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = { Text("当前支持单批 1–30 次；达到次数后安全停止") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Spacer(Modifier.height(8.dp))
        Text("行动力补充", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        HuntEnergyRefill.entries.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { refill ->
                    ChoiceButton(
                        label = refill.displayName(includeAvailability = true),
                        selected = config.energyRefill == refill,
                        enabled = refill.isSupported,
                        onClick = { onEnergyRefillChanged(refill) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(4.dp))
        if (automation.isRunning || automation.phase == HuntPhase.PAUSED) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onPauseOrResume,
                    modifier = Modifier.weight(1f),
                    colors = whiteButtonColors(),
                    border = whiteButtonBorder(),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                ) {
                    Text(if (automation.phase == HuntPhase.PAUSED) "继续" else "暂停")
                }
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    colors = whiteOutlinedButtonColors(danger = true),
                    border = whiteButtonBorder(danger = true),
                ) {
                    Text("停止")
                }
            }
        } else {
            Button(
                onClick = onPrepare,
                enabled = canStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = whiteButtonColors(),
                border = whiteButtonBorder(),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            ) {
                Text("准备讨伐", fontWeight = FontWeight.Bold)
            }
        }
        if (automation.phase != HuntPhase.IDLE) {
            Spacer(Modifier.height(10.dp))
            Text(
                automation.message,
                color = if (automation.phase == HuntPhase.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DungeonChoiceButton(
    dungeon: HuntDungeon,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(6.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surface
            },
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(
            2.dp,
            if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(dungeon.imageResource()),
                contentDescription = dungeon.displayName,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                dungeon.displayName,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

private fun HuntDungeon.imageResource(): Int = when (this) {
    HuntDungeon.WYVERN -> R.drawable.hunt_dungeon_wyvern
    HuntDungeon.GOLEM -> R.drawable.hunt_dungeon_golem
    HuntDungeon.BANSHEE -> R.drawable.hunt_dungeon_banshee
    HuntDungeon.AZIMANAK -> R.drawable.hunt_dungeon_azimanak
    HuntDungeon.CAIDES -> R.drawable.hunt_dungeon_caides
}

@Composable
private fun EnvironmentCard(
    environment: EnvironmentStatus,
    onEnableAccessibility: () -> Unit,
) {
    OrbitCard {
        Text(
            "运行前检查",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        CheckRow("无障碍服务", environment.accessibilityEnabled)
        CheckRow(
            "屏幕捕获",
            environment.projectionReady,
            if (environment.projectionReady) "已授权" else "运行时授权",
        )
        CheckRow("国服游戏已安装", environment.gameInstalled)
        InfoRow(
            title = "当前分辨率",
            detail = "${environment.width}×${environment.height}",
        )
        if (!environment.accessibilityEnabled) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onEnableAccessibility,
                modifier = Modifier.fillMaxWidth(),
                colors = whiteOutlinedButtonColors(),
                border = whiteButtonBorder(),
            ) {
                Text("开启无障碍服务")
            }
        }
    }
}

@Composable
private fun LastRunCard(summary: RunSummary) {
    OrbitCard {
        Text(
            "上次运行",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        MetricRow("完成刷新", "${summary.completedRefreshes} 次")
        MetricRow("扫描商店", "${summary.shopPagesScanned} 页")
        MetricRow("耗费金币", "%,d".format(summary.goldSpent))
        MetricRow(
            "誓约书签率",
            "${summary.covenantBookmarksBought} 次 · " +
                "${"%.2f".format(summary.covenantRatePercent)}%",
        )
        MetricRow(
            "神秘书签率",
            "${summary.mysticMedalsBought} 次 · " +
                "${"%.2f".format(summary.mysticRatePercent)}%",
        )
        MetricRow("耗时", formatDuration(summary.elapsedMs))
        MetricRow("停止原因", summary.stopReason)
    }
}

@Composable
private fun OrbitCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            content = content,
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.surface,
                checkedTrackColor = MaterialTheme.colorScheme.onSurface,
                checkedBorderColor = MaterialTheme.colorScheme.onSurface,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )
    }
}

@Composable
private fun ChoiceButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surface
            },
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Text(
            label,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun CheckRow(
    title: String,
    ready: Boolean,
    detail: String = if (ready) "已就绪" else "未就绪",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (ready) "✓" else "!",
            color = if (ready) OrbitSuccess else OrbitWarning,
            fontWeight = FontWeight.Black,
            modifier = Modifier.width(24.dp),
        )
        Text(title, modifier = Modifier.weight(1f))
        Text(
            detail,
            color = if (ready) OrbitSuccess else OrbitWarning,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun InfoRow(
    title: String,
    detail: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "•",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Black,
            modifier = Modifier.width(24.dp),
        )
        Text(title, modifier = Modifier.weight(1f))
        Text(
            detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun whiteButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
)

@Composable
private fun whiteOutlinedButtonColors(danger: Boolean = false) =
    ButtonDefaults.outlinedButtonColors(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = if (danger) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    )

@Composable
private fun whiteButtonBorder(danger: Boolean = false) = BorderStroke(
    1.dp,
    if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
)

private fun HuntDifficulty.displayName(includeAvailability: Boolean = false): String = when (this) {
    HuntDifficulty.HELL -> "地狱"
    HuntDifficulty.OTHERWORLD -> if (includeAvailability) "异界 · 开发中" else "异界"
}

private fun HuntEnergyRefill.displayName(includeAvailability: Boolean = false): String = when (this) {
    HuntEnergyRefill.DISABLED -> "不补充"
    HuntEnergyRefill.LEIF_ONLY -> if (includeAvailability) "生命之叶 · 开发中" else "仅生命之叶"
    HuntEnergyRefill.SKYSTONE_ONLY -> if (includeAvailability) "天空石 · 开发中" else "仅天空石"
    HuntEnergyRefill.LEIF_THEN_SKYSTONE -> if (includeAvailability) {
        "叶子后天空石 · 开发中"
    } else {
        "叶子后天空石"
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1000
    return "%02d:%02d:%02d".format(
        totalSeconds / 3600,
        (totalSeconds % 3600) / 60,
        totalSeconds % 60,
    )
}
