package com.e7orbit.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.e7orbit.data.E7Gear
import com.e7orbit.data.GearSetNames
import com.e7orbit.optimizer.EquippedHeroBuild
import com.e7orbit.optimizer.GearOptimizer
import com.e7orbit.optimizer.HeroBuildSort
import com.e7orbit.optimizer.ImprintRank
import com.e7orbit.optimizer.OptimizedBuild
import com.e7orbit.optimizer.OptimizerContent
import com.e7orbit.optimizer.OptimizerMetric
import com.e7orbit.optimizer.OptimizerStat
import com.e7orbit.optimizer.applyTo
import com.e7orbit.optimizer.buildEquippedHeroes
import com.e7orbit.optimizer.filterAndSortGears
import com.e7orbit.optimizer.sortEquippedHeroes
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun OptimizerScreen(
    state: MainUiState,
    modifier: Modifier = Modifier,
    onContentChanged: (OptimizerContent) -> Unit,
    onHeroSortChanged: (HeroBuildSort) -> Unit,
    onGearSetToggled: (String) -> Unit,
    onGearMainStatToggled: (String) -> Unit,
    onGearSubstatToggled: (String) -> Unit,
    onGearMinimumScoreChanged: (Int) -> Unit,
    onGearSortChanged: (com.e7orbit.optimizer.GearInventorySort) -> Unit,
    onClearGearFilters: () -> Unit,
    onHeroSelected: (Long) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val optimizer = state.optimizer
    val displayedGears = remember(state.data.gears, optimizer.selectedPlan) {
        optimizer.selectedPlan?.applyTo(state.data.gears) ?: state.data.gears
    }
    val imprintRanks = remember(optimizer.heroPreferences) {
        optimizer.heroPreferences.mapValues { it.value.imprintRank }
    }
    val artifactCodes = remember(optimizer.heroPreferences) {
        optimizer.heroPreferences.mapNotNull { (id, pref) ->
            pref.artifactCode?.let { id to it }
        }.toMap()
    }
    val builds = remember(
        state.data.scannedHeroes,
        state.data.heroes,
        displayedGears,
        imprintRanks,
        artifactCodes,
        state.data.artifacts,
    ) {
        buildEquippedHeroes(
            scannedHeroes = state.data.scannedHeroes,
            catalog = state.data.heroes,
            gears = displayedGears,
            includeEmptyScannedHeroes = optimizer.selectedPlan != null,
            imprintRanks = imprintRanks,
            artifacts = state.data.artifacts,
            artifactCodes = artifactCodes,
        )
    }
    val sortedBuilds = remember(builds, optimizer.heroSort) {
        sortEquippedHeroes(builds, optimizer.heroSort)
    }
    val filteredGears = remember(displayedGears, optimizer.gearFilter, optimizer.gearSort) {
        filterAndSortGears(
            gears = displayedGears,
            filter = optimizer.gearFilter,
            sort = optimizer.gearSort,
        )
    }
    val gearSetOptions = remember(state.data.gears) {
        state.data.gears
            .groupBy(E7Gear::setCode)
            .map { (code, items) -> code to GearSetNames.shortName(code, items.first().setName) }
            .sortedBy { it.second }
    }
    val gearMainStatOptions = remember(state.data.gears) {
        state.data.gears
            .map { it.mainStat.type }
            .distinct()
            .sortedBy(::statFilterLabel)
    }
    val gearSubstatOptions = remember(state.data.gears) {
        state.data.gears
            .flatMap { it.substats }
            .map { it.type }
            .distinct()
            .sortedBy(::statFilterLabel)
    }
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    Column(modifier = modifier.fillMaxSize()) {
        OptimizerContentTabs(
            content = optimizer.content,
            onContentChanged = onContentChanged,
        )
        AnimatedContent(
            targetState = optimizer.content,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            transitionSpec = {
                val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                val enter = slideInHorizontally(
                    animationSpec = spatialSpec,
                    initialOffsetX = { width -> direction * width / 4 },
                ) + fadeIn(animationSpec = effectsSpec)
                val exit = slideOutHorizontally(
                    animationSpec = spatialSpec,
                    targetOffsetX = { width -> -direction * width / 4 },
                ) + fadeOut(animationSpec = effectsSpec)
                (enter togetherWith exit).apply { targetContentZIndex = 1f }
            },
            contentKey = OptimizerContent::name,
            label = "optimizer content",
        ) { content ->
            when (content) {
                OptimizerContent.HEROES -> OptimizerHeroesContent(
                    state = state,
                    builds = builds,
                    sortedBuilds = sortedBuilds,
                    onHeroSortChanged = onHeroSortChanged,
                    onHeroSelected = onHeroSelected,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                )

                OptimizerContent.EQUIPMENT -> OptimizerEquipmentContent(
                    state = state,
                    builds = builds,
                    displayedGears = displayedGears,
                    filteredGears = filteredGears,
                    gearSetOptions = gearSetOptions,
                    gearMainStatOptions = gearMainStatOptions,
                    gearSubstatOptions = gearSubstatOptions,
                    onGearSetToggled = onGearSetToggled,
                    onGearMainStatToggled = onGearMainStatToggled,
                    onGearSubstatToggled = onGearSubstatToggled,
                    onGearMinimumScoreChanged = onGearMinimumScoreChanged,
                    onGearSortChanged = onGearSortChanged,
                    onClearGearFilters = onClearGearFilters,
                )

                OptimizerContent.POWER -> OptimizerPowerContent(
                    state = state,
                    builds = builds,
                    displayedGears = displayedGears,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptimizerContentTabs(
    content: OptimizerContent,
    onContentChanged: (OptimizerContent) -> Unit,
) {
    PrimaryTabRow(
        selectedTabIndex = content.ordinal,
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        OptimizerContent.entries.forEach { entry ->
            Tab(
                selected = content == entry,
                onClick = { onContentChanged(entry) },
                text = {
                    Text(
                        when (entry) {
                            OptimizerContent.HEROES -> "英雄"
                            OptimizerContent.EQUIPMENT -> "装备"
                            OptimizerContent.POWER -> "战力"
                        },
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OptimizerHeroesContent(
    state: MainUiState,
    builds: List<EquippedHeroBuild>,
    sortedBuilds: List<EquippedHeroBuild>,
    onHeroSortChanged: (HeroBuildSort) -> Unit,
    onHeroSelected: (Long) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val itemSpatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val itemEffectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    // 已有装备但英雄目录仍在加载时，面板还算不出来，先显示 M3E loading，避免空面板闪烁。
    if (state.data.gears.isNotEmpty() && state.data.loadState == DataLoadState.LOADING) {
        OptimizerLoadingState()
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (builds.isNotEmpty()) {
            item(key = "hero-sort") {
                HeroBuildSortControls(
                    sort = state.optimizer.heroSort,
                    onSortChanged = onHeroSortChanged,
                )
            }
        }
        when {
            state.data.gears.isEmpty() -> item(key = "empty-gears") {
                OptimizerEmptyState(
                    title = "尚未导入装备",
                    detail = "请在首页开启抓包并进入游戏背包。",
                )
            }
            builds.isEmpty() -> item(key = "empty-builds") {
                OptimizerEmptyState(
                    title = "没有可配装英雄",
                    detail = "当前数据中没有英雄实例。重新抓包或导入包含英雄数据的 gear.txt。",
                )
            }
            else -> items(sortedBuilds, key = EquippedHeroBuild::instanceId) { build ->
                EquippedHeroCard(
                    build = build,
                    preferenceConfigured = state.optimizer.heroPreferences[build.instanceId]
                        ?.isConfigured == true,
                    onClick = { onHeroSelected(build.instanceId) },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    modifier = Modifier.animateItem(
                        fadeInSpec = itemEffectsSpec,
                        placementSpec = itemSpatialSpec,
                        fadeOutSpec = itemEffectsSpec,
                    ),
                )
            }
        }
        item(key = "footer") { Spacer(Modifier.height(12.dp)) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OptimizerEquipmentContent(
    state: MainUiState,
    builds: List<EquippedHeroBuild>,
    displayedGears: List<E7Gear>,
    filteredGears: List<E7Gear>,
    gearSetOptions: List<Pair<String, String>>,
    gearMainStatOptions: List<String>,
    gearSubstatOptions: List<String>,
    onGearSetToggled: (String) -> Unit,
    onGearMainStatToggled: (String) -> Unit,
    onGearSubstatToggled: (String) -> Unit,
    onGearMinimumScoreChanged: (Int) -> Unit,
    onGearSortChanged: (com.e7orbit.optimizer.GearInventorySort) -> Unit,
    onClearGearFilters: () -> Unit,
) {
    val itemSpatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val itemEffectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    // 装备归属英雄名依赖英雄目录，加载中时先显示 loading。
    if (displayedGears.isNotEmpty() && state.data.loadState == DataLoadState.LOADING) {
        OptimizerLoadingState()
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (displayedGears.isNotEmpty()) {
            item(key = "gear-summary") {
                OptimizerOverviewSummary(
                    heroCount = builds.size,
                    completeBuilds = builds.count(EquippedHeroBuild::isComplete),
                    gearCount = displayedGears.size,
                    equippedCount = displayedGears.count { it.equippedHeroId != null },
                    content = OptimizerContent.EQUIPMENT,
                )
            }
        }
        item(key = "gear-filters") {
            GearFilterPanel(
                filter = state.optimizer.gearFilter,
                sort = state.optimizer.gearSort,
                resultCount = filteredGears.size,
                totalCount = displayedGears.size,
                setOptions = gearSetOptions,
                mainStatOptions = gearMainStatOptions,
                substatOptions = gearSubstatOptions,
                onSetToggled = onGearSetToggled,
                onMainStatToggled = onGearMainStatToggled,
                onSubstatToggled = onGearSubstatToggled,
                onMinimumScoreChanged = onGearMinimumScoreChanged,
                onSortChanged = onGearSortChanged,
                onClear = onClearGearFilters,
            )
        }
        if (filteredGears.isEmpty()) {
            item(key = "empty-equipment") {
                OptimizerEmptyState(
                    title = if (state.data.gears.isEmpty()) "尚未导入装备" else "没有匹配的装备",
                    detail = if (state.data.gears.isEmpty()) {
                        "请在首页开启抓包并进入游戏背包。"
                    } else {
                        "请更换筛选条件。"
                    },
                )
            }
        } else {
            items(filteredGears, key = E7Gear::id) { gear ->
                InventoryGearCard(
                    gear = gear,
                    equippedHero = builds.firstOrNull {
                        it.instanceId == gear.equippedHeroId
                    },
                    modifier = Modifier.animateItem(
                        fadeInSpec = itemEffectsSpec,
                        placementSpec = itemSpatialSpec,
                        fadeOutSpec = itemEffectsSpec,
                    ),
                )
            }
        }
        item(key = "footer") { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun OptimizerPowerContent(
    state: MainUiState,
    builds: List<EquippedHeroBuild>,
    displayedGears: List<E7Gear>,
) {
    // 战力榜的英雄名依赖英雄目录，加载中时先显示 loading。
    if (state.data.gears.isNotEmpty() && state.data.loadState == DataLoadState.LOADING) {
        OptimizerLoadingState()
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.data.gears.isEmpty()) {
            item(key = "empty-power") {
                OptimizerEmptyState(
                    title = "尚未导入装备",
                    detail = "请先在首页开启抓包并进入游戏背包。导入后即可查看百里战力。",
                )
            }
        } else {
            powerScreenItems(
                gears = displayedGears,
                equippedHeroes = builds.associateBy { it.instanceId },
                importedAtEpochMs = state.vpnCapture.importedAtEpochMs,
            )
        }
        item(key = "footer") { Spacer(Modifier.height(12.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun OptimizerHeroDetailScreen(
    state: MainUiState,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onMetricChanged: (OptimizerMetric) -> Unit,
    onMinimumChanged: (OptimizerStat, Int) -> Unit,
    onRequiredSetToggled: (String) -> Unit,
    onImprintRankChanged: (ImprintRank) -> Unit,
    onArtifactChanged: (String?) -> Unit,
    onAllowLockedChanged: (Boolean) -> Unit,
    onAllowEquippedChanged: (Boolean) -> Unit,
    onOnlyMaxedChanged: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onApplyResult: (OptimizedBuild) -> Unit,
) {
    val optimizer = state.optimizer
    val displayedGears = remember(state.data.gears, optimizer.selectedPlan) {
        optimizer.selectedPlan?.applyTo(state.data.gears) ?: state.data.gears
    }
    val imprintRanks = remember(optimizer.heroPreferences) {
        optimizer.heroPreferences.mapValues { it.value.imprintRank }
    }
    val artifactCodes = remember(optimizer.heroPreferences) {
        optimizer.heroPreferences.mapNotNull { (id, pref) ->
            pref.artifactCode?.let { id to it }
        }.toMap()
    }
    val build = remember(
        optimizer.selectedEquippedHeroId,
        state.data.scannedHeroes,
        state.data.heroes,
        displayedGears,
        imprintRanks,
        artifactCodes,
        state.data.artifacts,
    ) {
        buildEquippedHeroes(
            scannedHeroes = state.data.scannedHeroes,
            catalog = state.data.heroes,
            gears = displayedGears,
            includeEmptyScannedHeroes = optimizer.selectedPlan != null,
            imprintRanks = imprintRanks,
            artifacts = state.data.artifacts,
            artifactCodes = artifactCodes,
        ).firstOrNull { it.instanceId == optimizer.selectedEquippedHeroId }
    }
    val setOptions = remember(state.data.gears) {
        state.data.gears
            .asSequence()
            .filter { GearOptimizer.setPieces(it.setCode) > 0 }
            .groupBy(E7Gear::setCode)
            .map { (code, items) ->
                OptimizerSetOption(
                    code = code,
                    name = GearSetNames.shortName(code, items.first().setName),
                    pieces = GearOptimizer.setPieces(code),
                )
            }
            .sortedWith(compareBy<OptimizerSetOption> { it.pieces }.thenBy { it.name })
    }

    if (build == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("该英雄配装已不存在", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    var selectedTab by rememberSaveable(build.instanceId) {
        mutableIntStateOf(if (optimizer.phase == OptimizerPhase.IDLE) 0 else 2)
    }
    LaunchedEffect(optimizer.phase) {
        if (optimizer.phase != OptimizerPhase.IDLE) selectedTab = 2
    }
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    Column(modifier = modifier.fillMaxSize()) {
        EquippedHeroHeader(
            build = build,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
        )
        PrimaryTabRow(
            selectedTabIndex = selectedTab,
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            listOf("概览", "偏好", "结果").forEachIndexed { index, label ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(label) },
                )
            }
        }
        AnimatedContent(
            targetState = selectedTab,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            transitionSpec = {
                val direction = if (targetState >= initialState) 1 else -1
                val enter = slideInHorizontally(
                    animationSpec = spatialSpec,
                    initialOffsetX = { width -> direction * width / 4 },
                ) + fadeIn(animationSpec = effectsSpec)
                val exit = slideOutHorizontally(
                    animationSpec = spatialSpec,
                    targetOffsetX = { width -> -direction * width / 4 },
                ) + fadeOut(animationSpec = effectsSpec)
                (enter togetherWith exit).apply { targetContentZIndex = 1f }
            },
            label = "optimizer hero tab",
        ) { tab ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
        if (tab == 0) {
        item(key = "stats") {
            SectionSurface {
                SectionTitle(title = "面板属性")
                Spacer(Modifier.height(12.dp))
                build.stats?.let { HeroStatsGrid(it) }
                    ?: Text(
                        "缺少可匹配的英雄基础属性",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }

        item(key = "equipment") {
            SectionSurface(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                SectionTitle(
                    title = optimizer.selectedPlan?.let { "方案装备 · ${it.name}" } ?: "游戏当前装备",
                    detail = "${build.items.size} / 6 个部位",
                )
                Spacer(Modifier.height(8.dp))
                EQUIPMENT_SLOTS.forEachIndexed { index, slot ->
                    val gear = build.items.firstOrNull { it.slot == slot }
                    DetailedGearRow(slot = slot, gear = gear)
                    if (index != EQUIPMENT_SLOTS.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        }

        if (tab == 1) {
        item(key = "preference") {
            SectionSurface {
                ImprintRankSelector(
                    build = build,
                    selectedRank = optimizer.imprintRank,
                    enabled = optimizer.phase != OptimizerPhase.RUNNING,
                    onRankSelected = onImprintRankChanged,
                )
                Spacer(Modifier.height(14.dp))
                ArtifactSelector(
                    artifacts = state.data.artifacts,
                    heroRole = build.hero?.role,
                    selectedCode = optimizer.artifactCode,
                    enabled = optimizer.phase != OptimizerPhase.RUNNING,
                    onSelected = onArtifactChanged,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "排序目标",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(OptimizerMetric.entries, key = OptimizerMetric::name) { metric ->
                        FilterChip(
                            selected = optimizer.metric == metric,
                            onClick = { onMetricChanged(metric) },
                            enabled = optimizer.phase != OptimizerPhase.RUNNING,
                            label = { Text(metric.label) },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "最低属性",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                OptimizerStat.entries.chunked(2).forEachIndexed { index, rowStats ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowStats.forEach { stat ->
                            MinimumStatField(
                                stat = stat,
                                value = optimizer.minimums[stat] ?: 0,
                                enabled = optimizer.phase != OptimizerPhase.RUNNING,
                                onChanged = { onMinimumChanged(stat, it) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (index != OptimizerStat.entries.chunked(2).lastIndex) {
                        Spacer(Modifier.height(8.dp))
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "必选套装",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(setOptions, key = OptimizerSetOption::code) { set ->
                        val icon = gearSetIconRes(set.code)
                        FilterChip(
                            selected = set.code in optimizer.requiredSets,
                            onClick = { onRequiredSetToggled(set.code) },
                            enabled = optimizer.phase != OptimizerPhase.RUNNING,
                            label = { Text("${set.name} · ${set.pieces}") },
                            leadingIcon = icon?.let { resId ->
                                {
                                    GearAssetIcon(
                                        resId = resId,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                        )
                    }
                }
                val requiredPieces = optimizer.requiredSets.sumOf(GearOptimizer::setPieces)
                if (requiredPieces > 6) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "必选套装合计超过 6 件，请取消一个套装。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item(key = "scope") {
            SectionSurface {
                SectionTitle("装备范围")
                ToggleSettingRow(
                    title = "只使用 +15 装备",
                    subtitle = "关闭后按装备当前属性参与计算。",
                    checked = optimizer.onlyMaxed,
                    enabled = optimizer.phase != OptimizerPhase.RUNNING,
                    onCheckedChange = onOnlyMaxedChanged,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleSettingRow(
                    title = "允许已装备",
                    subtitle = "包含当前穿在其他英雄身上的装备。",
                    checked = optimizer.allowEquipped,
                    enabled = optimizer.phase != OptimizerPhase.RUNNING,
                    onCheckedChange = onAllowEquippedChanged,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleSettingRow(
                    title = "允许锁定装备",
                    subtitle = "锁定只作为库存筛选条件。",
                    checked = optimizer.allowLocked,
                    enabled = optimizer.phase != OptimizerPhase.RUNNING,
                    onCheckedChange = onAllowLockedChanged,
                )
            }
        }
        }

        if (tab == 2) {
        when (optimizer.phase) {
            OptimizerPhase.IDLE -> item(key = "idle") {
                OptimizerEmptyState(
                    title = "尚未开始计算",
                    detail = "在偏好页设置目标后，使用下方按钮开始配装。",
                )
            }
            OptimizerPhase.RUNNING -> item(key = "running") { OptimizerRunningState() }
            OptimizerPhase.ERROR -> item(key = "error") {
                OptimizerMessageCard(
                    title = "无法开始配装",
                    detail = optimizer.errorMessage ?: "配装计算失败",
                    error = true,
                )
            }
            OptimizerPhase.READY -> {
                item(key = "result-summary") {
                    val evaluated = NumberFormat.getIntegerInstance(Locale.CHINA)
                        .format(optimizer.combinationsEvaluated)
                    OptimizerMessageCard(
                        title = if (optimizer.results.isEmpty()) {
                            "没有满足偏好的组合"
                        } else {
                            "找到 ${optimizer.results.size} 套方案"
                        },
                        detail = "评估 $evaluated 个候选分支 · ${optimizer.elapsedMs} ms",
                        error = false,
                    )
                }
                items(
                    items = optimizer.results,
                    key = { result -> result.items.joinToString("-") { it.id.toString() } },
                ) { result ->
                    OptimizedBuildCard(
                        build = result,
                        rank = optimizer.results.indexOf(result) + 1,
                        metric = optimizer.metric,
                        selectedPlan = optimizer.selectedPlan,
                        heroId = build.instanceId,
                        onApply = { onApplyResult(result) },
                    )
                }
            }
        }
        }

        item(key = "footer") { Spacer(Modifier.height(12.dp)) }
            }
        }
        OptimizerActionBar(
            state = state,
            selectedHero = build.hero,
            onStart = onStart,
            onStop = onStop,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun Modifier.optimizerSharedBounds(
    key: String,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
): Modifier {
    val spatialSpec = MaterialTheme.motionScheme.slowSpatialSpec<Rect>()
    val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    return with(sharedTransitionScope) {
        this@optimizerSharedBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = animatedVisibilityScope,
            enter = fadeIn(animationSpec = effectsSpec),
            exit = fadeOut(animationSpec = effectsSpec),
            boundsTransform = BoundsTransform { _, _ -> spatialSpec },
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        )
    }
}
