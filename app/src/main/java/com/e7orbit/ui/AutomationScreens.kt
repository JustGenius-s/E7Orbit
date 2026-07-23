package com.e7orbit.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.e7orbit.R
import com.e7orbit.model.AutomationPhase
import com.e7orbit.model.HuntDifficulty
import com.e7orbit.model.HuntDungeon
import com.e7orbit.model.HuntEnergyRefill
import com.e7orbit.model.HuntPhase
import com.e7orbit.model.RunSummary

@Composable
internal fun HomeScreen(
    state: MainUiState,
    modifier: Modifier = Modifier,
    onOpenTasks: () -> Unit,
    onOpenTask: (AutomationTask) -> Unit,
    onPauseOrResumeShop: () -> Unit,
    onStopShop: () -> Unit,
    onPauseOrResumeHunt: () -> Unit,
    onStopHunt: () -> Unit,
    onEnableAccessibility: () -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(contentType = "hero") {
            HomeStatusHero(
                state = state,
                onOpenTasks = onOpenTasks,
                onOpenTask = onOpenTask,
                onPauseOrResumeShop = onPauseOrResumeShop,
                onStopShop = onStopShop,
                onPauseOrResumeHunt = onPauseOrResumeHunt,
                onStopHunt = onStopHunt,
            )
        }
        item(contentType = "section") {
            SectionTitle(
                title = "运行环境",
                detail = "启动任务前会再次检查这些项目",
            )
            Spacer(Modifier.height(8.dp))
            EnvironmentSection(
                state = state,
                onEnableAccessibility = onEnableAccessibility,
            )
        }
        item(contentType = "section") {
            SectionTitle(title = "最近一次运行")
            Spacer(Modifier.height(8.dp))
            LastRunSection(state.lastSummary)
        }
    }
}

@Composable
private fun HomeStatusHero(
    state: MainUiState,
    onOpenTasks: () -> Unit,
    onOpenTask: (AutomationTask) -> Unit,
    onPauseOrResumeShop: () -> Unit,
    onStopShop: () -> Unit,
    onPauseOrResumeHunt: () -> Unit,
    onStopHunt: () -> Unit,
) {
    val shopActive = state.automation.isRunning || state.automation.phase == AutomationPhase.PAUSED
    val huntActive = state.huntAutomation.isRunning || state.huntAutomation.phase == HuntPhase.PAUSED
    val activeTask = when {
        shopActive -> AutomationTask.SHOP
        huntActive -> AutomationTask.HUNT
        else -> null
    }
    val paused = state.automation.phase == AutomationPhase.PAUSED ||
        state.huntAutomation.phase == HuntPhase.PAUSED
    val readyCount = listOf(
        state.environment.accessibilityEnabled,
        state.environment.gameInstalled,
        state.environment.openCvReady,
    ).count { it }

    SectionSurface(
        color = if (activeTask == null) {
            MaterialTheme.colorScheme.primaryContainer
        } else if (paused) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentPadding = PaddingValues(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_orbit_art),
                contentDescription = null,
                modifier = Modifier
                    .size(76.dp)
                    .clip(MaterialTheme.shapes.large),
                contentScale = ContentScale.Crop,
            )
            Column(modifier = Modifier.weight(1f)) {
                StatusPill(
                    label = when {
                        activeTask == null && readyCount == 3 -> "可以开始"
                        activeTask == null -> "$readyCount / 3 项就绪"
                        paused -> "已暂停"
                        else -> "运行中"
                    },
                    positive = activeTask != null || readyCount == 3,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = when (activeTask) {
                        AutomationTask.SHOP -> "自动神秘商店"
                        AutomationTask.HUNT -> "自动讨伐"
                        null -> "选择一个自动化任务"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when (activeTask) {
                        AutomationTask.SHOP -> state.automation.message
                        AutomationTask.HUNT -> state.huntAutomation.message
                        null -> "配置目标后，E7 Orbit 会启动游戏并显示悬浮控制器。"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        if (activeTask == null) {
            Button(
                onClick = onOpenTasks,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = CircleShape,
            ) {
                Text("浏览自动化任务", fontWeight = FontWeight.Bold)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(
                    onClick = {
                        if (activeTask == AutomationTask.SHOP) onPauseOrResumeShop()
                        else onPauseOrResumeHunt()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (paused) "继续" else "暂停")
                }
                OutlinedButton(
                    onClick = {
                        if (activeTask == AutomationTask.SHOP) onStopShop() else onStopHunt()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("停止")
                }
                OutlinedButton(
                    onClick = { onOpenTask(activeTask) },
                    contentPadding = PaddingValues(horizontal = 14.dp),
                ) {
                    Text("详情")
                }
            }
        }
    }
}

@Composable
private fun EnvironmentSection(
    state: MainUiState,
    onEnableAccessibility: () -> Unit,
) {
    SectionSurface {
        ReadinessRow("无障碍服务", state.environment.accessibilityEnabled)
        GroupDivider()
        ReadinessRow("国服游戏", state.environment.gameInstalled, if (state.environment.gameInstalled) "已安装" else "未安装")
        GroupDivider()
        ReadinessRow("识图引擎", state.environment.openCvReady)
        if (!state.environment.accessibilityEnabled) {
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(
                onClick = onEnableAccessibility,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("开启无障碍服务")
            }
        }
    }
}

@Composable
private fun LastRunSection(summary: RunSummary) {
    SectionSurface {
        MetricRow("完成刷新", "${summary.completedRefreshes} 次")
        MetricRow("扫描商店", "${summary.shopPagesScanned} 页")
        MetricRow("耗费金币", "%,d".format(summary.goldSpent))
        MetricRow("誓约书签", "${summary.covenantBookmarksBought} 次")
        MetricRow("神秘奖牌", "${summary.mysticMedalsBought} 次")
        MetricRow("耗时", formatDuration(summary.elapsedMs))
        MetricRow("停止原因", summary.stopReason)
    }
}

@Composable
internal fun TaskListScreen(
    state: MainUiState,
    modifier: Modifier = Modifier,
    onOpenTask: (AutomationTask) -> Unit,
) {
    val shopActive = state.automation.isRunning || state.automation.phase == AutomationPhase.PAUSED
    val huntActive = state.huntAutomation.isRunning || state.huntAutomation.phase == HuntPhase.PAUSED
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Text(
                text = "选择任务",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "任务按用途分组，运行期间其他任务会保持锁定。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            SectionTitle("资源获取")
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                TaskGroupItem(
                    title = "神秘商店",
                    subtitle = "刷新商店并验证购买书签与奖牌",
                    status = taskStatus(
                        active = shopActive,
                        paused = state.automation.phase == AutomationPhase.PAUSED,
                        ready = state.environment.canPrepare && state.automation.templatesReady,
                    ),
                    statusPositive = shopActive,
                    onClick = { onOpenTask(AutomationTask.SHOP) },
                    leading = {
                        Image(
                            painter = painterResource(R.drawable.ic_secret_shop),
                            contentDescription = null,
                            modifier = Modifier
                                .size(54.dp)
                                .clip(MaterialTheme.shapes.medium),
                            contentScale = ContentScale.Crop,
                        )
                    },
                )
            }
        }
        item {
            SectionTitle("战斗")
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                TaskGroupItem(
                    title = "讨伐",
                    subtitle = "选择地下城并执行托管战斗",
                    status = taskStatus(
                        active = huntActive,
                        paused = state.huntAutomation.phase == HuntPhase.PAUSED,
                        ready = state.environment.canPrepare && state.huntAutomation.templatesReady,
                    ),
                    statusPositive = huntActive,
                    onClick = { onOpenTask(AutomationTask.HUNT) },
                    leading = {
                        Image(
                            painter = painterResource(R.drawable.hunt_dungeon_wyvern),
                            contentDescription = null,
                            modifier = Modifier
                                .size(54.dp)
                                .clip(MaterialTheme.shapes.medium),
                            contentScale = ContentScale.Crop,
                        )
                    },
                )
            }
        }
    }
}

private fun taskStatus(active: Boolean, paused: Boolean, ready: Boolean): String = when {
    paused -> "已暂停"
    active -> "运行中"
    ready -> "可用"
    else -> "需设置"
}

@Composable
internal fun ShopTaskScreen(
    state: MainUiState,
    modifier: Modifier = Modifier,
    onBuyCovenantChanged: (Boolean) -> Unit,
    onBuyMysticChanged: (Boolean) -> Unit,
    onMaxRefreshChanged: (Int) -> Unit,
    onThresholdChanged: (Double) -> Unit,
    onPrepare: () -> Unit,
    onPauseOrResume: () -> Unit,
    onStop: () -> Unit,
) {
    val active = state.automation.isRunning || state.automation.phase == AutomationPhase.PAUSED
    val canStart = state.environment.canPrepare &&
        state.automation.templatesReady &&
        state.config.hasPurchaseTarget &&
        !active &&
        !state.huntAutomation.isRunning &&
        state.huntAutomation.phase != HuntPhase.PAUSED

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item { ShopTaskHeader(state = state, active = active) }
            item {
                SectionTitle("购买目标", "运行中不可修改配置")
                Spacer(Modifier.height(8.dp))
                SectionSurface {
                    ToggleSettingRow(
                        title = "购买誓约书签",
                        subtitle = "商品与购买按钮同时匹配后才会执行",
                        checked = state.config.buyCovenantBookmarks,
                        enabled = !active,
                        onCheckedChange = onBuyCovenantChanged,
                    )
                    GroupDivider()
                    ToggleSettingRow(
                        title = "购买神秘奖牌",
                        subtitle = "确认弹窗会再次验证商品类型",
                        checked = state.config.buyMysticMedals,
                        enabled = !active,
                        onCheckedChange = onBuyMysticChanged,
                    )
                }
            }
            item {
                SectionTitle("运行参数")
                Spacer(Modifier.height(8.dp))
                SectionSurface {
                    OutlinedTextField(
                        value = state.config.maxRefreshes.toString(),
                        onValueChange = { raw ->
                            raw.filter(Char::isDigit).toIntOrNull()?.let(onMaxRefreshChanged)
                        },
                        enabled = !active,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("最大刷新次数") },
                        supportingText = { Text("范围 1–10,000；达到上限后安全停止") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("识别阈值", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${(state.config.matchThreshold * 100).toInt()}%",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Slider(
                        value = state.config.matchThreshold.toFloat(),
                        onValueChange = { onThresholdChanged(it.toDouble()) },
                        enabled = !active,
                        valueRange = 0.85f..0.98f,
                    )
                }
            }
            item {
                SectionTitle("启动检查")
                Spacer(Modifier.height(8.dp))
                SectionSurface {
                    ReadinessRow("运行环境", state.environment.canPrepare)
                    GroupDivider()
                    ReadinessRow("识图模板", state.automation.templatesReady)
                    GroupDivider()
                    ReadinessRow("购买目标", state.config.hasPurchaseTarget, if (state.config.hasPurchaseTarget) "已选择" else "至少选择一项")
                }
            }
        }
        TaskActionBar(
            active = active,
            paused = state.automation.phase == AutomationPhase.PAUSED,
            canStart = canStart,
            startLabel = "开始神秘商店",
            onPrepare = onPrepare,
            onPauseOrResume = onPauseOrResume,
            onStop = onStop,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ShopTaskHeader(state: MainUiState, active: Boolean) {
    SectionSurface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentPadding = PaddingValues(20.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_secret_shop),
                contentDescription = null,
                modifier = Modifier
                    .size(72.dp)
                    .clip(MaterialTheme.shapes.large),
                contentScale = ContentScale.Crop,
            )
            Column(modifier = Modifier.weight(1f)) {
                StatusPill(
                    label = if (active) {
                        if (state.automation.phase == AutomationPhase.PAUSED) "已暂停" else "运行中"
                    } else {
                        "资源任务"
                    },
                    positive = active,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "自动神秘商店",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
        if (state.automation.phase != AutomationPhase.IDLE) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = state.automation.message,
                color = if (state.automation.phase == AutomationPhase.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
            )
        }
    }
}

@Composable
internal fun HuntTaskScreen(
    state: MainUiState,
    modifier: Modifier = Modifier,
    onDungeonChanged: (HuntDungeon) -> Unit,
    onDifficultyChanged: (HuntDifficulty) -> Unit,
    onManagedBattleChanged: (Boolean) -> Unit,
    onRunCountChanged: (Int) -> Unit,
    onEnergyRefillChanged: (HuntEnergyRefill) -> Unit,
    onPrepare: () -> Unit,
    onPauseOrResume: () -> Unit,
    onStop: () -> Unit,
) {
    val active = state.huntAutomation.isRunning || state.huntAutomation.phase == HuntPhase.PAUSED
    val canStart = state.environment.canPrepare &&
        state.huntAutomation.templatesReady &&
        !active &&
        !state.automation.isRunning &&
        state.automation.phase != AutomationPhase.PAUSED

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item { HuntTaskHeader(state, active) }
            item {
                SectionTitle("地下城", "横向滑动查看更多")
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(end = 16.dp),
                ) {
                    items(HuntDungeon.entries, key = { it.name }) { dungeon ->
                        DungeonOption(
                            dungeon = dungeon,
                            selected = state.huntConfig.dungeon == dungeon,
                            enabled = !active,
                            onClick = { onDungeonChanged(dungeon) },
                        )
                    }
                }
            }
            item {
                SectionTitle("战斗设置")
                Spacer(Modifier.height(8.dp))
                SectionSurface {
                    Text("讨伐难度", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        HuntDifficulty.entries.forEachIndexed { index, difficulty ->
                            SegmentedButton(
                                selected = state.huntConfig.difficulty == difficulty,
                                onClick = { onDifficultyChanged(difficulty) },
                                enabled = !active && difficulty.isSupported,
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = HuntDifficulty.entries.size,
                                ),
                                label = { Text(difficulty.displayName(includeAvailability = true)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ToggleSettingRow(
                        title = "托管战斗",
                        subtitle = "当前仅支持托管战斗",
                        checked = state.huntConfig.managedBattle,
                        enabled = false,
                        onCheckedChange = onManagedBattleChanged,
                    )
                }
            }
            item {
                SectionTitle("运行参数")
                Spacer(Modifier.height(8.dp))
                SectionSurface {
                    OutlinedTextField(
                        value = state.huntConfig.runCount.toString(),
                        onValueChange = { raw ->
                            raw.filter(Char::isDigit).toIntOrNull()?.let(onRunCountChanged)
                        },
                        enabled = !active,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("讨伐次数") },
                        supportingText = { Text("当前支持单批 1–30 次") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("行动力补充", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    HuntEnergyRefill.entries.forEach { refill ->
                        EnergyRefillOption(
                            refill = refill,
                            selected = state.huntConfig.energyRefill == refill,
                            enabled = !active && refill.isSupported,
                            onClick = { onEnergyRefillChanged(refill) },
                        )
                        if (refill != HuntEnergyRefill.entries.last()) {
                            GroupDivider()
                        }
                    }
                }
            }
            item {
                SectionTitle("启动检查")
                Spacer(Modifier.height(8.dp))
                SectionSurface {
                    ReadinessRow("运行环境", state.environment.canPrepare)
                    GroupDivider()
                    ReadinessRow("讨伐模板", state.huntAutomation.templatesReady)
                }
            }
        }
        TaskActionBar(
            active = active,
            paused = state.huntAutomation.phase == HuntPhase.PAUSED,
            canStart = canStart,
            startLabel = "开始讨伐",
            onPrepare = onPrepare,
            onPauseOrResume = onPauseOrResume,
            onStop = onStop,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun HuntTaskHeader(state: MainUiState, active: Boolean) {
    SectionSurface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentPadding = PaddingValues(20.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(state.huntConfig.dungeon.imageResource()),
                contentDescription = null,
                modifier = Modifier
                    .size(76.dp)
                    .clip(MaterialTheme.shapes.large),
                contentScale = ContentScale.Crop,
            )
            Column(modifier = Modifier.weight(1f)) {
                StatusPill(
                    label = if (active) {
                        if (state.huntAutomation.phase == HuntPhase.PAUSED) "已暂停" else "运行中"
                    } else {
                        "战斗任务"
                    },
                    positive = active,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "${state.huntConfig.dungeon.displayName}讨伐",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
        if (state.huntAutomation.phase != HuntPhase.IDLE) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = state.huntAutomation.message,
                color = if (state.huntAutomation.phase == HuntPhase.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            )
        }
    }
}

@Composable
private fun DungeonOption(
    dungeon: HuntDungeon,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.width(126.dp),
        shape = if (selected) MaterialTheme.shapes.extraLarge else MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null,
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Image(
                painter = painterResource(dungeon.imageResource()),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(MaterialTheme.shapes.small),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = dungeon.displayName,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun EnergyRefillOption(
    refill: HuntEnergyRefill,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = refill.displayName(includeAvailability = true),
                color = if (enabled || selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
        }
    }
}

internal fun HuntDungeon.imageResource(): Int = when (this) {
    HuntDungeon.WYVERN -> R.drawable.hunt_dungeon_wyvern
    HuntDungeon.GOLEM -> R.drawable.hunt_dungeon_golem
    HuntDungeon.BANSHEE -> R.drawable.hunt_dungeon_banshee
    HuntDungeon.AZIMANAK -> R.drawable.hunt_dungeon_azimanak
    HuntDungeon.CAIDES -> R.drawable.hunt_dungeon_caides
}

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
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    return "%02d:%02d:%02d".format(
        totalSeconds / 3_600L,
        (totalSeconds % 3_600L) / 60L,
        totalSeconds % 60L,
    )
}
