package com.e7orbit.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.e7orbit.R
import com.e7orbit.data.E7Gear
import com.e7orbit.data.E7GearStat
import com.e7orbit.data.E7Hero
import com.e7orbit.data.GearSlot
import com.e7orbit.optimizer.EquippedHeroBuild
import com.e7orbit.optimizer.EquippedSetSummary
import com.e7orbit.optimizer.EquipmentPlan
import com.e7orbit.optimizer.GearInventoryFilter
import com.e7orbit.optimizer.GearInventorySort
import com.e7orbit.optimizer.GearOptimizer
import com.e7orbit.optimizer.GearSortDirection
import com.e7orbit.optimizer.GearSortField
import com.e7orbit.optimizer.HeroBuildSort
import com.e7orbit.optimizer.HeroBuildSortField
import com.e7orbit.optimizer.OptimizedBuild
import com.e7orbit.optimizer.OptimizedHeroStats
import com.e7orbit.optimizer.OptimizerContent
import com.e7orbit.optimizer.OptimizerMetric
import com.e7orbit.optimizer.OptimizerStat
import com.e7orbit.optimizer.applyTo
import com.e7orbit.optimizer.buildEquippedHeroes
import com.e7orbit.optimizer.containsBuild
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
    onGearSortChanged: (GearInventorySort) -> Unit,
    onClearGearFilters: () -> Unit,
    onHeroSelected: (Long) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val optimizer = state.optimizer
    val displayedGears = remember(state.data.gears, optimizer.selectedPlan) {
        optimizer.selectedPlan?.applyTo(state.data.gears) ?: state.data.gears
    }
    val builds = remember(state.data.scannedHeroes, state.data.heroes, displayedGears) {
        buildEquippedHeroes(
            scannedHeroes = state.data.scannedHeroes,
            catalog = state.data.heroes,
            gears = displayedGears,
            includeEmptyScannedHeroes = optimizer.selectedPlan != null,
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
            .map { (code, items) -> code to items.first().setName.removeSuffix("套装") }
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (builds.isNotEmpty()) {
            item(key = "hero-summary") {
                OptimizerOverviewSummary(
                    heroCount = builds.size,
                    completeBuilds = builds.count(EquippedHeroBuild::isComplete),
                    gearCount = state.data.gears.size,
                    equippedCount = state.data.gears.count { it.equippedHeroId != null },
                    content = OptimizerContent.HEROES,
                )
            }
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
    onGearSortChanged: (GearInventorySort) -> Unit,
    onClearGearFilters: () -> Unit,
) {
    val itemSpatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val itemEffectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
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
                    equippedName = builds.firstOrNull {
                        it.instanceId == gear.equippedHeroId
                    }?.displayName,
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
                heroNames = builds.associate { it.instanceId to it.displayName },
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
    val build = remember(
        optimizer.selectedEquippedHeroId,
        state.data.scannedHeroes,
        state.data.heroes,
        displayedGears,
    ) {
        buildEquippedHeroes(
            scannedHeroes = state.data.scannedHeroes,
            catalog = state.data.heroes,
            gears = displayedGears,
            includeEmptyScannedHeroes = optimizer.selectedPlan != null,
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
                    name = items.first().setName.removeSuffix("套装"),
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
                SectionTitle(
                    title = "最终属性",
                    detail = if (build.stats == null) {
                        "需要完整六件装备和可匹配的英雄基础属性。"
                    } else {
                        "按当前装备、基础属性和已激活套装计算。"
                    },
                )
                Spacer(Modifier.height(12.dp))
                build.stats?.let { HeroStatsGrid(it) }
                    ?: Text(
                        "最终面板暂不可计算",
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
                SectionTitle(
                    title = "属性偏好",
                    detail = "设置只属于 ${build.displayName}，计算结果先满足最低属性，再按目标排序。",
                )
                Spacer(Modifier.height(12.dp))
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

@Composable
private fun HeroBuildSortControls(
    sort: HeroBuildSort,
    onSortChanged: (HeroBuildSort) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            Text(
                "排序",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            CompactDropdown(label = sort.field.label) { dismiss ->
                HeroBuildSortField.entries.forEach { field ->
                    DropdownMenuItem(
                        text = { Text(field.label) },
                        leadingIcon = {
                            if (sort.field == field) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                )
                            }
                        },
                        onClick = {
                            dismiss()
                            onSortChanged(sort.copy(field = field))
                        },
                    )
                }
            }
        }
        item {
            SortDirectionChip(
                direction = sort.direction,
                onClick = {
                    onSortChanged(
                        sort.copy(direction = sort.direction.toggled()),
                    )
                },
            )
        }
    }
}

@Composable
private fun CompactDropdown(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(modifier = modifier) {
        FilterChip(
            selected = false,
            onClick = { expanded = true },
            label = {
                Text(
                    label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_drop_down),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            content { expanded = false }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GearFilterPanel(
    filter: GearInventoryFilter,
    sort: GearInventorySort,
    resultCount: Int,
    totalCount: Int,
    setOptions: List<Pair<String, String>>,
    mainStatOptions: List<String>,
    substatOptions: List<String>,
    onSetToggled: (String) -> Unit,
    onMainStatToggled: (String) -> Unit,
    onSubstatToggled: (String) -> Unit,
    onMinimumScoreChanged: (Int) -> Unit,
    onSortChanged: (GearInventorySort) -> Unit,
    onClear: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val cornerSize by animateDpAsState(
        targetValue = if (expanded) 28.dp else 12.dp,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Dp>(),
        label = "gear filter shape",
    )
    SectionSurface(
        shape = RoundedCornerShape(cornerSize),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "筛选 · 排序",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (filter.hasFilters) {
                        "$resultCount / $totalCount 件 · ${filter.activeFilterCount()} 项条件"
                    } else {
                        "$resultCount 件 · ${sort.field.label}${sort.direction.label}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (filter.hasFilters) {
                IconButton(onClick = onClear) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = "清除筛选",
                    )
                }
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (expanded) 90f else 0f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                GearFilterGroup(
                    title = "套装",
                    options = setOptions,
                    selected = filter.setCodes,
                    iconRes = ::gearSetIconRes,
                    onToggle = onSetToggled,
                )
                Spacer(Modifier.height(10.dp))
                GearFilterGroup(
                    title = "主属性",
                    options = mainStatOptions.map { it to statFilterLabel(it) },
                    selected = filter.mainStatTypes,
                    iconRes = ::gearStatIconRes,
                    onToggle = onMainStatToggled,
                )
                Spacer(Modifier.height(10.dp))
                GearFilterGroup(
                    title = "副属性",
                    options = substatOptions.map { it to statFilterLabel(it) },
                    selected = filter.substatTypes,
                    iconRes = ::gearStatIconRes,
                    onToggle = onSubstatToggled,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = filter.minimumScore.takeIf { it > 0 }?.toString().orEmpty(),
                    onValueChange = { input ->
                        onMinimumScoreChanged(
                            input.filter(Char::isDigit).take(MAX_STAT_DIGITS).toIntOrNull() ?: 0,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("最低装备分") },
                    placeholder = { Text("不限") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "排序",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item {
                        CompactDropdown(label = sort.field.label) { dismiss ->
                            GearSortField.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    leadingIcon = {
                                        if (sort.field == option) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_check),
                                                contentDescription = null,
                                            )
                                        }
                                    },
                                    onClick = {
                                        dismiss()
                                        onSortChanged(
                                            sort.copy(
                                                field = option,
                                                statType = when (option) {
                                                    GearSortField.MAIN_STAT -> mainStatOptions.firstOrNull()
                                                    GearSortField.SUBSTAT -> substatOptions.firstOrNull()
                                                    else -> null
                                                },
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                    item {
                        SortDirectionChip(
                            direction = sort.direction,
                            onClick = {
                                onSortChanged(
                                    sort.copy(direction = sort.direction.toggled()),
                                )
                            },
                        )
                    }
                    if (sort.field == GearSortField.MAIN_STAT || sort.field == GearSortField.SUBSTAT) {
                        item {
                            CompactDropdown(
                                label = statFilterLabel(sort.statType ?: "属性"),
                            ) { dismiss ->
                                val options = if (sort.field == GearSortField.MAIN_STAT) {
                                    mainStatOptions
                                } else {
                                    substatOptions
                                }
                                options.forEach { type ->
                                    DropdownMenuItem(
                                        text = { Text(statFilterLabel(type)) },
                                        onClick = {
                                            dismiss()
                                            onSortChanged(sort.copy(statType = type))
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SortDirectionChip(
    direction: GearSortDirection,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = { Text(direction.label) },
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_drop_down),
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (direction == GearSortDirection.ASCENDING) 180f else 0f),
            )
        },
    )
}

private fun GearSortDirection.toggled(): GearSortDirection =
    if (this == GearSortDirection.DESCENDING) {
        GearSortDirection.ASCENDING
    } else {
        GearSortDirection.DESCENDING
    }

@Composable
private fun GearFilterGroup(
    title: String,
    options: List<Pair<String, String>>,
    selected: Set<String>,
    iconRes: (String) -> Int?,
    onToggle: (String) -> Unit,
) {
    if (options.isEmpty()) return
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(5.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options, key = { it.first }) { (code, label) ->
                val icon = iconRes(code)
                FilterChip(
                    selected = code in selected,
                    onClick = { onToggle(code) },
                    label = { Text(label) },
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
    }
}

@Composable
private fun OptimizerOverviewSummary(
    heroCount: Int,
    completeBuilds: Int,
    gearCount: Int,
    equippedCount: Int,
    content: OptimizerContent,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (content == OptimizerContent.HEROES) {
            SummaryMetric("已配装英雄", heroCount.toString(), Modifier.weight(1f))
            SummaryDivider()
            SummaryMetric("完整六件", completeBuilds.toString(), Modifier.weight(1f))
            SummaryDivider()
            SummaryMetric("已穿装备", "$equippedCount 件", Modifier.weight(1f))
        } else {
            SummaryMetric("装备总数", "$gearCount 件", Modifier.weight(1f))
            SummaryDivider()
            SummaryMetric("已装备", "$equippedCount 件", Modifier.weight(1f))
            SummaryDivider()
            SummaryMetric("库存", "${gearCount - equippedCount} 件", Modifier.weight(1f))
        }
    }
}

@Composable
private fun EquippedHeroCard(
    build: EquippedHeroBuild,
    preferenceConfigured: Boolean,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .optimizerSharedBounds(
                key = "optimizer-hero-${build.instanceId}",
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
            ),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(
                    url = build.hero?.assets?.iconUrl ?: build.hero?.assets?.thumbnailUrl,
                    contentDescription = build.displayName,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = build.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    GearSetSummaryRow(build.sets)
                    HeroIdentityIcons(
                        attribute = build.hero?.attribute,
                        role = build.hero?.role,
                        rarity = build.scannedHero?.stars ?: build.hero?.rarity,
                        iconSize = 17.dp,
                    )
                    Text(
                        text = build.combatSummaryText(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${build.items.size}/6",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (preferenceConfigured) "已设置偏好" else "查看详情",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (preferenceConfigured) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            build.stats?.let { CompactStatsGrid(it) }
                ?: Text(
                    text = if (!build.isComplete) "装备不完整，无法计算最终属性" else "未匹配到英雄基础属性",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EquippedHeroHeader(
    build: EquippedHeroBuild,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    SectionSurface(
        modifier = modifier.optimizerSharedBounds(
            key = "optimizer-hero-${build.instanceId}",
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
        ),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.extraLargeIncreased,
        contentPadding = PaddingValues(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(
                url = build.hero?.assets?.iconUrl ?: build.hero?.assets?.thumbnailUrl,
                contentDescription = build.displayName,
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = build.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HeroIdentityIcons(
                    attribute = build.hero?.attribute,
                    role = build.hero?.role,
                    rarity = build.scannedHero?.stars ?: build.hero?.rarity,
                    iconSize = 22.dp,
                )
                Text(
                    text = listOfNotNull(
                        build.scannedHero?.awaken?.let { "觉醒 $it" },
                        build.hero?.zodiac,
                    ).joinToString(" · ").ifBlank { "游戏实例 ${build.instanceId}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                GearSetSummaryRow(build.sets)
            }
        }
    }
}

@Composable
private fun CompactStatsGrid(stats: OptimizedHeroStats) {
    val values = listOf(
        StatDisplay("Attack", "攻击", formatNumber(stats.attack)),
        StatDisplay("Health", "生命", formatNumber(stats.health)),
        StatDisplay("Defense", "防御", formatNumber(stats.defense)),
        StatDisplay("Speed", "速度", stats.speed.toString()),
        StatDisplay("CriticalHitChancePercent", "暴击", "${stats.critChance}%"),
        StatDisplay("CriticalHitDamagePercent", "暴伤", "${stats.critDamage}%"),
        StatDisplay("EffectivenessPercent", "命中", "${stats.effectiveness}%"),
        StatDisplay("EffectResistancePercent", "抗性", "${stats.resistance}%"),
    )
    values.chunked(4).forEachIndexed { rowIndex, row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            row.forEach { stat ->
                StatCell(stat, Modifier.weight(1f), compact = true)
            }
        }
        if (rowIndex == 0) Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HeroStatsGrid(stats: OptimizedHeroStats) {
    val values = listOf(
        StatDisplay("Attack", "攻击", formatNumber(stats.attack)),
        StatDisplay("Health", "生命", formatNumber(stats.health)),
        StatDisplay("Defense", "防御", formatNumber(stats.defense)),
        StatDisplay("Speed", "速度", stats.speed.toString()),
        StatDisplay("CriticalHitChancePercent", "暴击率", "${stats.critChance}%"),
        StatDisplay("CriticalHitDamagePercent", "暴击伤害", "${stats.critDamage}%"),
        StatDisplay("EffectivenessPercent", "效果命中", "${stats.effectiveness}%"),
        StatDisplay("EffectResistancePercent", "效果抗性", "${stats.resistance}%"),
        StatDisplay(null, "战斗力", formatNumber(stats.combatPower)),
        StatDisplay(null, "有效生命", formatNumber(stats.effectiveHealth)),
        StatDisplay(null, "伤害", formatNumber(stats.damage)),
        StatDisplay(null, "装备分", stats.gearScore.toString()),
    )
    values.chunked(3).forEachIndexed { rowIndex, row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            row.forEach { stat ->
                StatCell(stat, Modifier.weight(1f), compact = false)
            }
        }
        if (rowIndex != values.chunked(3).lastIndex) Spacer(Modifier.height(10.dp))
    }
}

private data class StatDisplay(
    val type: String?,
    val label: String,
    val value: String,
)

@Composable
private fun StatCell(stat: StatDisplay, modifier: Modifier, compact: Boolean) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            stat.type?.let(::gearStatIconRes)?.let { resId ->
                GearAssetIcon(
                    resId = resId,
                    contentDescription = null,
                    modifier = Modifier.size(if (compact) 14.dp else 16.dp),
                )
                Spacer(Modifier.width(3.dp))
            }
            Text(
                text = stat.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            text = stat.value,
            fontSize = if (compact) 13.sp else 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DetailedGearRow(slot: GearSlot, gear: E7Gear?) {
    if (gear == null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GearSlotLabel(slot = slot, rank = null, modifier = Modifier.width(62.dp))
            Text("未装备", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.width(62.dp)) {
                GearSlotLabel(slot = slot, rank = gear.rank)
                Text(
                    "+${gear.enhance}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    gearSetIconRes(gear.setCode)?.let { resId ->
                        GearAssetIcon(
                            resId = resId,
                            contentDescription = gear.setName,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        "${gear.setName.removeSuffix("套装")} · ${gear.rank}",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GearStatInline(stat = gear.mainStat, showModified = false)
                    Text(
                        " · Lv.${gear.level}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "分 ${GearOptimizer.gearScore(gear)}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        if (gear.substats.isNotEmpty()) {
            Spacer(Modifier.height(7.dp))
            GearSubstatsRow(
                substats = gear.substats,
                modifier = Modifier.padding(start = 62.dp),
            )
        }
    }
}

@Composable
private fun InventoryGearCard(
    gear: E7Gear,
    equippedName: String?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.width(64.dp)) {
                    GearSlotLabel(slot = gear.slot, rank = gear.rank)
                    Text(
                        "+${gear.enhance}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        gearSetIconRes(gear.setCode)?.let { resId ->
                            GearAssetIcon(
                                resId = resId,
                                contentDescription = gear.setName,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            "${gear.setName.removeSuffix("套装")} · ${gear.rank} · Lv.${gear.level}",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    GearStatInline(stat = gear.mainStat, showModified = false)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        GearOptimizer.gearScore(gear).toString(),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        equippedName ?: "库存",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (equippedName == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (gear.substats.isNotEmpty()) {
                Spacer(Modifier.height(7.dp))
                GearSubstatsRow(substats = gear.substats)
            }
        }
    }
}

@Composable
private fun GearSlotLabel(
    slot: GearSlot,
    rank: String?,
    modifier: Modifier = Modifier,
) {
    if (slot == GearSlot.UNKNOWN) {
        Text(
            slot.label,
            modifier = modifier,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    GearSlotAsset(
        slot = slot,
        rank = rank,
        modifier = modifier.size(33.dp),
    )
}

@Composable
internal fun GearSlotAsset(
    slot: GearSlot,
    rank: String?,
    modifier: Modifier = Modifier,
) {
    val iconRes = gearSlotIconRes(slot) ?: return
    val style = gearRankIconStyle(rank)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(style.background)
            .border(1.5.dp, style.border, RoundedCornerShape(3.dp))
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        GearAssetIcon(
            resId = iconRes,
            contentDescription = slot.label,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

internal data class GearRankIconStyle(
    val border: Color,
    val background: Color,
)

internal fun gearRankIconStyle(rank: String?): GearRankIconStyle = when (rank?.lowercase()) {
    "epic", "传说" -> GearRankIconStyle(Color(0xFFA20707), Color(0xFFE53935))
    "heroic", "英雄" -> GearRankIconStyle(Color(0xFF8E24AA), Color(0xFFD05CE3))
    "rare", "稀有" -> GearRankIconStyle(Color(0xFF1722F9), Color(0xFF4D8DFF))
    "good", "优秀" -> GearRankIconStyle(Color(0xFF009208), Color(0xFF50C85A))
    "normal", "普通" -> GearRankIconStyle(Color(0xFF616161), Color(0xFFB5B8B7))
    else -> GearRankIconStyle(
        border = Color(0xFF8A9099),
        background = Color(0x263A414B),
    )
}

@Composable
internal fun GearAssetIcon(
    resId: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(resId),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun GearSetSummaryRow(sets: List<EquippedSetSummary>) {
    val visibleSets = sets.filter { it.completedCount > 0 }.ifEmpty { sets }
    if (visibleSets.isEmpty()) {
        Text(
            "暂无套装",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(visibleSets, key = EquippedSetSummary::code) { set ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                gearSetIconRes(set.code)?.let { resId ->
                    GearAssetIcon(
                        resId = resId,
                        contentDescription = set.name,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = buildString {
                        append(set.name)
                        if (set.completedCount > 1) append(" x${set.completedCount}")
                        else if (set.completedCount == 0) append(" ${set.pieceCount}/${set.requiredPieces}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun GearStatInline(
    stat: E7GearStat,
    modifier: Modifier = Modifier,
    showModified: Boolean = true,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        gearStatIconRes(stat.type)?.let { resId ->
            GearAssetIcon(
                resId = resId,
                contentDescription = stat.label,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = "${stat.label} ${stat.displayValue()}${if (showModified && stat.modified) "*" else ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun GearSubstatsRow(
    substats: List<E7GearStat>,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(substats, key = E7GearStat::type) { stat ->
            GearStatInline(stat)
        }
    }
}

@Composable
private fun MinimumStatField(
    stat: OptimizerStat,
    value: Int,
    enabled: Boolean,
    onChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value.takeIf { it > 0 }?.toString().orEmpty(),
        onValueChange = { input ->
            val digits = input.filter(Char::isDigit).take(MAX_STAT_DIGITS)
            onChanged(digits.toIntOrNull() ?: 0)
        },
        modifier = modifier,
        enabled = enabled,
        singleLine = true,
        label = { Text(stat.label) },
        placeholder = { Text("不限") },
        leadingIcon = gearStatIconRes(optimizerStatType(stat))?.let { resId ->
            {
                GearAssetIcon(
                    resId = resId,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OptimizerActionBar(
    state: MainUiState,
    selectedHero: E7Hero?,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val optimizer = state.optimizer
    val requiredPieces = optimizer.requiredSets.sumOf(GearOptimizer::setPieces)
    val canStart = selectedHero?.stats != null &&
        state.data.gears.isNotEmpty() &&
        requiredPieces <= 6
    val startLabel = when {
        selectedHero?.stats == null -> "英雄基础属性不可用"
        state.data.gears.isEmpty() -> "尚未导入装备"
        requiredPieces > 6 -> "必选套装超过 6 件"
        else -> "按偏好开始配装"
    }
    BottomAppBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (optimizer.phase == OptimizerPhase.RUNNING) {
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shapes = ButtonDefaults.shapes(
                    shape = MaterialTheme.shapes.extraLarge,
                    pressedShape = MaterialTheme.shapes.medium,
                ),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_stop),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("停止计算")
            }
        } else {
            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = canStart,
                shapes = ButtonDefaults.shapes(
                    shape = MaterialTheme.shapes.extraLarge,
                    pressedShape = MaterialTheme.shapes.medium,
                ),
            ) {
                Icon(
                    painter = painterResource(
                        if (canStart) R.drawable.ic_play else R.drawable.ic_priority_high,
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(startLabel, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OptimizerRunningState() {
    SectionSurface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LoadingIndicator(modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("正在组合六个部位", fontWeight = FontWeight.SemiBold)
                Text(
                    "计算在后台执行，可随时停止。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun OptimizerMessageCard(title: String, detail: String, error: Boolean) {
    SectionSurface(
        color = if (error) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
    ) {
        Text(
            title,
            fontWeight = FontWeight.Bold,
            color = if (error) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
        )
        Spacer(Modifier.height(3.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OptimizedBuildCard(
    build: OptimizedBuild,
    rank: Int,
    metric: OptimizerMetric,
    selectedPlan: EquipmentPlan?,
    heroId: Long,
    onApply: () -> Unit,
) {
    var expanded by rememberSaveable(
        build.items.joinToString("-") { it.id.toString() },
    ) { mutableStateOf(rank == 1) }
    val formatter = remember { NumberFormat.getIntegerInstance(Locale.CHINA) }
    val cornerSize by animateDpAsState(
        targetValue = if (expanded) 12.dp else 24.dp,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Dp>(),
        label = "optimizer result shape",
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(cornerSize),
        colors = CardDefaults.cardColors(
            containerColor = if (rank == 1) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "#$rank",
                    modifier = Modifier.width(42.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                )
                Column(modifier = Modifier.weight(1f)) {
                    OptimizedSetRow(build)
                    Text(
                        "${metric.label} ${formatter.format(build.rankingValue)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(if (expanded) 90f else 0f),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(12.dp))
            CompactStatsGrid(build.stats)
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    build.items.forEachIndexed { index, gear ->
                        DetailedGearRow(gear.slot, gear)
                        if (index != build.items.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    val alreadyApplied = selectedPlan?.containsBuild(
                        heroId = heroId,
                        gearIds = build.items.map(E7Gear::id),
                    ) == true
                    Button(
                        onClick = onApply,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selectedPlan != null && !alreadyApplied,
                        shapes = ButtonDefaults.shapes(
                            shape = MaterialTheme.shapes.extraLarge,
                            pressedShape = MaterialTheme.shapes.medium,
                        ),
                    ) {
                        Text(
                            when {
                                selectedPlan == null -> "请先创建配装方案"
                                alreadyApplied -> "已应用到 ${selectedPlan.name}"
                                else -> "应用到 ${selectedPlan.name}"
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OptimizerEmptyState(title: String, detail: String) {
    SectionSurface {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SummaryDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(32.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun OptimizedSetRow(build: OptimizedBuild) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(build.completedSets, key = { it }) { setCode ->
            val name = build.items.firstOrNull { it.setCode == setCode }
                ?.setName?.removeSuffix("套装") ?: setCode
            Row(verticalAlignment = Alignment.CenterVertically) {
                gearSetIconRes(setCode)?.let { resId ->
                    GearAssetIcon(
                        resId = resId,
                        contentDescription = name,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = name,
                    maxLines = 1,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun optimizerStatType(stat: OptimizerStat): String = when (stat) {
    OptimizerStat.ATTACK -> "Attack"
    OptimizerStat.HEALTH -> "Health"
    OptimizerStat.DEFENSE -> "Defense"
    OptimizerStat.SPEED -> "Speed"
    OptimizerStat.CRIT_CHANCE -> "CriticalHitChancePercent"
    OptimizerStat.CRIT_DAMAGE -> "CriticalHitDamagePercent"
    OptimizerStat.EFFECTIVENESS -> "EffectivenessPercent"
    OptimizerStat.RESISTANCE -> "EffectResistancePercent"
}

private fun EquippedHeroBuild.combatSummaryText(): String = listOfNotNull(
    stats?.combatPower?.let { "战力 ${formatNumber(it)}" },
    stats?.speed?.let { "速度 $it" },
).joinToString(" · ").ifBlank { "暂无最终面板" }

private fun formatNumber(value: Int): String =
    NumberFormat.getIntegerInstance(Locale.CHINA).format(value)

private fun GearInventoryFilter.activeFilterCount(): Int =
    setCodes.size + mainStatTypes.size + substatTypes.size + minimumScore.takeIf { it > 0 }?.let { 1 }.orZero()

private fun Int?.orZero(): Int = this ?: 0

private fun statFilterLabel(type: String): String = when (type) {
    "Attack" -> "攻击"
    "AttackPercent" -> "攻击%"
    "Health" -> "生命"
    "HealthPercent" -> "生命%"
    "Defense" -> "防御"
    "DefensePercent" -> "防御%"
    "Speed" -> "速度"
    "CriticalHitChancePercent" -> "暴击率"
    "CriticalHitDamagePercent" -> "暴击伤害"
    "EffectivenessPercent" -> "效果命中"
    "EffectResistancePercent" -> "效果抗性"
    "DualAttackChancePercent" -> "夹攻率"
    else -> type
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Modifier.optimizerSharedBounds(
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

private const val MAX_STAT_DIGITS = 7
private val EQUIPMENT_SLOTS = listOf(
    GearSlot.WEAPON,
    GearSlot.HELMET,
    GearSlot.ARMOR,
    GearSlot.NECKLACE,
    GearSlot.RING,
    GearSlot.BOOTS,
)
