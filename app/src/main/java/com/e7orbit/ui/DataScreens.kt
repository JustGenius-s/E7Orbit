package com.e7orbit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.e7orbit.R
import com.e7orbit.data.E7Artifact
import com.e7orbit.data.E7Hero
import com.e7orbit.data.E7HeroStats
import com.e7orbit.data.HeroRtaAnalysis
import com.e7orbit.data.RtaTier
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun DataBrowserScreen(
    data: DataUiState,
    modifier: Modifier = Modifier,
    onSectionChanged: (DataSection) -> Unit,
    onQueryChanged: (String) -> Unit,
    onSelectHero: (String) -> Unit,
    onSelectArtifact: (String) -> Unit,
    onLoad: () -> Unit,
) {
    var selectedFilter by rememberSaveable(data.section) { mutableStateOf("全部") }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        OutlinedTextField(
            value = data.query,
            onValueChange = onQueryChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = CircleShape,
            label = { Text(if (data.section == DataSection.HEROES) "搜索英雄" else "搜索神器") },
            placeholder = { Text("名称或编码") },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = null,
                )
            },
            trailingIcon = if (data.query.isNotEmpty()) {
                {
                    IconButton(onClick = { onQueryChanged("") }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = "清除搜索",
                        )
                    }
                }
            } else null,
        )
        Spacer(Modifier.height(12.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            DataSection.entries.forEachIndexed { index, section ->
                SegmentedButton(
                    selected = data.section == section,
                    onClick = { onSectionChanged(section) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = DataSection.entries.size,
                    ),
                    label = { Text(if (section == DataSection.HEROES) "英雄" else "神器") },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        FilterRow(
            section = data.section,
            selected = selectedFilter,
            onSelected = { selectedFilter = it },
        )
        Spacer(Modifier.height(8.dp))

        when (data.loadState) {
            DataLoadState.IDLE -> DataEmptyState(
                title = "数据尚未加载",
                detail = "从官方 Stove 和 Fribbels 读取英雄与神器资料。",
                action = "加载数据",
                onAction = onLoad,
            )

            DataLoadState.LOADING -> DataLoadingState()
            DataLoadState.ERROR -> DataEmptyState(
                title = "数据读取失败",
                detail = data.errorMessage ?: "公开数据暂时不可用",
                action = "重新尝试",
                onAction = onLoad,
                error = true,
            )

            DataLoadState.READY -> when (data.section) {
                DataSection.HEROES -> HeroList(
                    data = data,
                    filter = selectedFilter,
                    onSelect = onSelectHero,
                )

                DataSection.ARTIFACTS -> ArtifactList(
                    data = data,
                    filter = selectedFilter,
                    onSelect = onSelectArtifact,
                )
            }
        }
    }
}

@Composable
private fun FilterRow(
    section: DataSection,
    selected: String,
    onSelected: (String) -> Unit,
) {
    val filters = if (section == DataSection.HEROES) {
        listOf("全部", "火", "冰", "木", "光", "暗")
    } else {
        listOf("全部", "骑士", "战士", "射手", "法师", "盗贼", "奶妈")
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(filters, key = { it }) { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelected(filter) },
                label = { Text(filter) },
            )
        }
    }
}

@Composable
private fun ColumnScope.HeroList(
    data: DataUiState,
    filter: String,
    onSelect: (String) -> Unit,
) {
    val filtered = remember(data.heroes, data.query, filter) {
        data.heroes.filter { hero ->
            val matchesQuery = data.query.isBlank() ||
                hero.name.contains(data.query, ignoreCase = true) ||
                hero.code.contains(data.query, ignoreCase = true)
            val matchesFilter = filter == "全部" || hero.attributeLabel() == filter
            matchesQuery && matchesFilter
        }
    }
    DataResultHeader(filtered.size, data.heroes.size)
    Spacer(Modifier.height(8.dp))
    if (filtered.isEmpty()) {
        NoResultsState("没有匹配的英雄")
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(
            items = filtered,
            key = { it.code },
            contentType = { "hero" },
        ) { hero ->
            ListItem(
                headlineContent = {
                    Text(hero.name, fontWeight = FontWeight.SemiBold)
                },
                supportingContent = {
                    Text(
                        "${hero.attributeLabel()} · ${hero.roleLabel()} · ${hero.code}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = {
                    RemoteImage(
                        url = hero.assets.iconUrl,
                        contentDescription = "${hero.name}头像",
                        modifier = Modifier
                            .size(48.dp)
                            .clip(MaterialTheme.shapes.medium),
                    )
                },
                trailingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                modifier = Modifier.clickable { onSelect(hero.code) },
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 80.dp, end = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun ColumnScope.ArtifactList(
    data: DataUiState,
    filter: String,
    onSelect: (String) -> Unit,
) {
    val filtered = remember(data.artifacts, data.query, filter) {
        data.artifacts.filter { artifact ->
            val matchesQuery = data.query.isBlank() ||
                artifact.name.contains(data.query, ignoreCase = true) ||
                artifact.code.contains(data.query, ignoreCase = true)
            val matchesFilter = filter == "全部" || artifact.role?.roleLabel() == filter
            matchesQuery && matchesFilter
        }
    }
    DataResultHeader(filtered.size, data.artifacts.size)
    Spacer(Modifier.height(8.dp))
    if (filtered.isEmpty()) {
        NoResultsState("没有匹配的神器")
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(
            items = filtered,
            key = { it.code },
            contentType = { "artifact" },
        ) { artifact ->
            ListItem(
                headlineContent = {
                    Text(artifact.name, fontWeight = FontWeight.SemiBold)
                },
                supportingContent = {
                    Text(
                        listOfNotNull(artifact.rarity, artifact.role?.roleLabel(), artifact.code)
                            .joinToString(" · "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                artifact.name.take(1),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                },
                trailingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                modifier = Modifier.clickable { onSelect(artifact.code) },
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 80.dp, end = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun DataResultHeader(count: Int, total: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$count 个结果",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "共 $total 项",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DataLoadingState() {
    SectionSurface(modifier = Modifier.fillMaxWidth()) {
        Text(
            "正在读取数据",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        repeat(6) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(vertical = 4.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        }
    }
}

@Composable
private fun DataEmptyState(
    title: String,
    detail: String,
    action: String,
    onAction: () -> Unit,
    error: Boolean = false,
) {
    SectionSurface {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            detail,
            color = if (error) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        if (error) {
            OutlinedButton(onClick = onAction) { Text(action) }
        } else {
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun ColumnScope.NoResultsState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun HeroDetailScreen(
    hero: E7Hero?,
    rta: HeroRtaUiState,
    modifier: Modifier = Modifier,
    onSeasonChanged: (String) -> Unit,
    onTierChanged: (RtaTier) -> Unit,
    onRetryRta: () -> Unit,
) {
    var selectedTab by rememberSaveable(hero?.code) { mutableIntStateOf(0) }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (hero == null) {
            item { DataMissingDetail("英雄数据不可用") }
            return@LazyColumn
        }
        item {
            SectionSurface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                contentPadding = PaddingValues(16.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RemoteImage(
                        url = hero.assets.iconUrl,
                        contentDescription = "${hero.name}头像",
                        modifier = Modifier
                            .size(72.dp)
                            .clip(MaterialTheme.shapes.medium),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            hero.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${hero.attributeLabel()} · ${hero.roleLabel()}" +
                                (hero.rarity?.let { " · $it 星" } ?: ""),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            hero.code,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                listOf("概览", "RTA").forEachIndexed { index, label ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(label) },
                    )
                }
            }
        }
        if (selectedTab == 0) {
            item {
                SectionTitle("六星满觉基础属性")
                Spacer(Modifier.height(8.dp))
                SectionSurface {
                    MetricRow("星座", hero.zodiac?.takeIf(String::isNotBlank) ?: "—")
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    HeroStats(hero.stats)
                }
            }
        } else {
            item {
                HeroRtaSection(
                    state = rta,
                    onSeasonChanged = onSeasonChanged,
                    onTierChanged = onTierChanged,
                    onRetry = onRetryRta,
                )
            }
        }
    }
}

@Composable
private fun HeroRtaSection(
    state: HeroRtaUiState,
    onSeasonChanged: (String) -> Unit,
    onTierChanged: (RtaTier) -> Unit,
    onRetry: () -> Unit,
) {
    Text(
        text = "赛季",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
    if (state.seasons.isNotEmpty()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.seasons, key = { it.code }) { season ->
                FilterChip(
                    selected = state.selectedSeasonCode == season.code,
                    onClick = { onSeasonChanged(season.code) },
                    label = {
                        Text(
                            if (season.isCurrent) "${season.name} · 当前" else season.name,
                            maxLines = 1,
                        )
                    },
                )
            }
        }
        state.seasons.firstOrNull { it.code == state.selectedSeasonCode }?.let { season ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${season.startDate.rtaDate()} 至 ${season.endDate.rtaDate()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        text = "段位",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(RtaTier.entries, key = { it.code }) { tier ->
            FilterChip(
                selected = state.selectedTier == tier,
                onClick = { onTierChanged(tier) },
                label = { Text(tier.label) },
            )
        }
    }
    Spacer(Modifier.height(12.dp))

    when (state.loadState) {
        DataLoadState.IDLE,
        DataLoadState.LOADING,
        -> SectionSurface {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text(
                text = "正在读取官方 RTA 数据",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DataLoadState.ERROR -> SectionSurface {
            Text(
                text = state.errorMessage ?: "官方 RTA 数据暂时不可用",
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRetry) { Text("重新尝试") }
        }

        DataLoadState.READY -> state.analysis?.let { analysis ->
            if (analysis.hasRtaData()) {
                HeroRtaAnalysisContent(analysis)
            } else {
                SectionSurface {
                    Text(
                        text = "该赛季与段位暂无数据",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } ?: SectionSurface {
            Text(
                text = "该赛季与段位暂无数据",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HeroRtaAnalysisContent(analysis: HeroRtaAnalysis) {
    SectionSurface {
        MetricRow("统计样本", "%,d".format(analysis.sampleSize))
        MetricRow("英雄胜率", analysis.winRate.rtaPercent())
        MetricRow("胜率排名", analysis.winRateRank?.let { "第 $it 名" } ?: "—")
    }

    Spacer(Modifier.height(16.dp))
    SectionTitle("常用装备组合")
    Spacer(Modifier.height(8.dp))
    SectionSurface {
        if (analysis.equipmentSets.isEmpty()) {
            Text("暂无装备组合数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            analysis.equipmentSets.take(5).forEachIndexed { index, equipment ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                MetricRow(
                    label = "#${equipment.rank} ${equipment.setCodes.joinToString(" + ") { it.rtaSetLabel() }}",
                    value = "使用 ${equipment.usageRate.rtaPercent()} · 胜率 ${equipment.winRate.rtaPercent()}",
                )
            }
        }
    }

    Spacer(Modifier.height(24.dp))
    SectionTitle("顺位分布")
    Spacer(Modifier.height(8.dp))
    SectionSurface {
        Text(
            text = "选取",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (analysis.pickPositions.isEmpty()) {
            Text("暂无选取数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            analysis.pickPositions.sortedBy { it.position }.forEach { position ->
                MetricRow("第 ${position.position} 顺位", position.rate.rtaPercent())
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text = "禁用",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (analysis.banPositions.isEmpty()) {
            Text("暂无禁用数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            analysis.banPositions.sortedBy { it.position }.forEach { position ->
                MetricRow("第 ${position.position} 顺位", position.rate.rtaPercent())
            }
        }
    }
}

@Composable
private fun HeroStats(stats: E7HeroStats?) {
    if (stats == null) {
        Text("暂无 Fribbels 属性数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    MetricRow("攻击", stats.attack.displayOrDash())
    MetricRow("生命", stats.health.displayOrDash())
    MetricRow("防御", stats.defense.displayOrDash())
    MetricRow("速度", stats.speed.displayOrDash())
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    MetricRow("暴击", stats.criticalChance.percentOrDash())
    MetricRow("暴伤", stats.criticalDamage.percentOrDash())
    MetricRow("效果命中", stats.effectiveness.percentOrDash())
    MetricRow("效果抗性", stats.effectResistance.percentOrDash())
    MetricRow("战斗力", stats.combatPower.displayOrDash())
}

@Composable
internal fun ArtifactDetailScreen(
    artifact: E7Artifact?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (artifact == null) {
            item { DataMissingDetail("神器数据不可用") }
            return@LazyColumn
        }
        item {
            SectionSurface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentPadding = PaddingValues(20.dp),
            ) {
                Surface(
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            artifact.name.take(1),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    artifact.name,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    listOfNotNull(artifact.rarity, artifact.role?.roleLabel(), artifact.code)
                        .joinToString(" · "),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        item {
            SectionTitle("满级加成")
            Spacer(Modifier.height(8.dp))
            SectionSurface {
                MetricRow("攻击", artifact.attack.displayOrDash())
                MetricRow("生命", artifact.health.displayOrDash())
                MetricRow("防御", artifact.defense.displayOrDash())
                MetricRow("适用职业", artifact.role?.roleLabel() ?: "—")
            }
        }
        artifact.description?.takeIf(String::isNotBlank)?.let { description ->
            item {
                SectionTitle("效果描述")
                Spacer(Modifier.height(8.dp))
                SectionSurface {
                    Text(
                        description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun DataMissingDetail(message: String) {
    SectionSurface {
        Text(message, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("返回列表后重新选择。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SettingsScreen(
    state: MainUiState,
    modifier: Modifier = Modifier,
    onEnableAccessibility: () -> Unit,
    onRefreshEnvironment: () -> Unit,
    onRefreshData: () -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
    ) {
        item {
            SettingsSectionHeader("自动化")
        }
        item {
            ListItem(
                headlineContent = { Text("无障碍服务") },
                supportingContent = { Text("自动化操作权限") },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (state.environment.accessibilityEnabled) "已开启" else "未开启",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_right),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                modifier = Modifier.clickable(onClick = onEnableAccessibility),
            )
        }
        item { SettingsDivider() }
        item {
            ListItem(
                headlineContent = { Text("运行环境") },
                supportingContent = {
                    val game = if (state.environment.gameInstalled) "游戏已安装" else "未检测到游戏"
                    val vision = if (state.environment.openCvReady) "识图已就绪" else "识图未就绪"
                    Text("$game · $vision")
                },
                trailingContent = {
                    IconButton(onClick = onRefreshEnvironment) {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = "重新检查运行环境",
                        )
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        }
        item { SettingsDivider() }
        item {
            ListItem(
                headlineContent = { Text("屏幕捕获") },
                supportingContent = { Text("屏幕内容识别权限") },
                trailingContent = {
                    Text(
                        text = if (state.environment.projectionReady) "已授权" else "运行时授权",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        }
        item { SettingsSectionHeader("数据") }
        item {
            ListItem(
                headlineContent = { Text("英雄与神器") },
                supportingContent = {
                    val updatedAt = state.data.fetchedAtEpochMs.takeIf { it > 0L }?.let {
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(it))
                    } ?: "尚未更新"
                    Text("${state.data.heroes.size} 位英雄 · ${state.data.artifacts.size} 件神器 · $updatedAt")
                },
                trailingContent = {
                    IconButton(
                        onClick = onRefreshData,
                        enabled = state.data.loadState != DataLoadState.LOADING,
                    ) {
                        if (state.data.loadState == DataLoadState.LOADING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh),
                                contentDescription = "刷新公开数据",
                            )
                        }
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        }
        item { SettingsSectionHeader("关于") }
        item {
            ListItem(
                headlineContent = { Text("E7 Orbit") },
                supportingContent = { Text("自动化仅在用户明确启动后运行") },
                trailingContent = {
                    Text(
                        text = "0.1.0",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

private fun E7Hero.attributeLabel(): String = when (attribute.lowercase()) {
    "fire" -> "火"
    "ice", "water" -> "冰"
    "earth", "wind" -> "木"
    "light" -> "光"
    "dark" -> "暗"
    else -> attribute
}

private fun E7Hero.roleLabel(): String = role.roleLabel()

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

private fun String.rtaDate(): String = take(10).ifBlank { "—" }

private fun Double?.rtaPercent(): String = this?.let { value ->
    "%.2f".format(Locale.US, value).trimEnd('0').trimEnd('.') + "%"
} ?: "—"

private fun HeroRtaAnalysis.hasRtaData(): Boolean =
    sampleSize > 0 ||
        winRate != null ||
        equipmentSets.isNotEmpty() ||
        pickPositions.isNotEmpty() ||
        banPositions.isNotEmpty()

private fun String.rtaSetLabel(): String = when (this) {
    "set_speed" -> "速度"
    "set_immune" -> "免疫"
    "set_max_hp" -> "生命"
    "set_acc" -> "命中"
    "set_shield" -> "护盾"
    "set_att" -> "攻击"
    "set_def" -> "防御"
    "set_cri" -> "暴击"
    "set_cri_dmg" -> "暴伤"
    "set_res" -> "抗性"
    "set_counter" -> "反击"
    "set_vampire" -> "吸血"
    "set_revenge" -> "复仇"
    "set_penetrate" -> "穿透"
    "set_torrent" -> "激流"
    "set_rage" -> "愤怒"
    "set_injury" -> "伤口"
    else -> removePrefix("set_")
}
