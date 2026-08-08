package com.e7orbit.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.e7orbit.R
import com.e7orbit.data.GearImportPhase
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
    onStartVpnCapture: () -> Unit,
    onStopVpnCapture: () -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
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
        item(contentType = "vpn") {
            GearScanCard(
                state = state.vpnCapture,
                onStart = onStartVpnCapture,
                onStop = onStopVpnCapture,
            )
        }
        if (!state.environment.canPrepare) {
            item(contentType = "notice") {
                EnvironmentNotice(
                state = state,
                onEnableAccessibility = onEnableAccessibility,
            )
            }
        }
        if (state.lastSummary.completedAtEpochMs > 0L) {
            item(contentType = "section") {
                SectionTitle(title = "最近运行")
                Spacer(Modifier.height(8.dp))
                LastRunSection(state.lastSummary)
            }
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
    SectionSurface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(16.dp),
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
                    .size(56.dp)
                    .clip(MaterialTheme.shapes.medium),
                contentScale = ContentScale.Crop,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        activeTask == null && state.environment.canPrepare -> "可以开始"
                        activeTask == null -> "需要设置"
                        paused -> "已暂停"
                        else -> "运行中"
                    },
                    color = if (activeTask != null || state.environment.canPrepare) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when (activeTask) {
                        AutomationTask.SHOP -> "神秘商店"
                        AutomationTask.HUNT -> "讨伐"
                        null -> "自动化任务"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when (activeTask) {
                        AutomationTask.SHOP -> state.automation.message
                        AutomationTask.HUNT -> state.huntAutomation.message
                        null -> "选择任务并配置运行参数"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        if (activeTask == null) {
            Button(
                onClick = onOpenTasks,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("选择任务", fontWeight = FontWeight.Bold)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
private fun GearScanCard(
    state: VpnCaptureUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    SectionSurface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "装备抓包",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        state.running -> "抓包中 · 装备流 ${formatBytes(state.capturedBytes)}(${state.capturedSegments} 段) · 流量 ${formatBytes(state.bytes)}"
                        state.importPhase == GearImportPhase.PARSING -> "正在解析装备数据"
                        state.errorMessage != null -> "上次失败: ${state.errorMessage}"
                        state.importedGearCount > 0 -> "已导入 ${state.importedGearCount} 件装备 · ${state.importedHeroCount} 个英雄"
                        else -> "先开启抓包，再进入游戏打开背包"
                    },
                    color = if (state.errorMessage != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.width(12.dp))
            if (state.running) {
                OutlinedButton(onClick = onStop) {
                    Text("停止")
                }
            } else {
                Button(onClick = onStart) {
                    Text("开启抓包")
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

@Composable
private fun EnvironmentNotice(
    state: MainUiState,
    onEnableAccessibility: () -> Unit,
) {
    val issues = buildList {
        if (!state.environment.accessibilityEnabled) add("无障碍服务未开启")
        if (!state.environment.gameInstalled) add("未检测到国服游戏")
        if (!state.environment.openCvReady) add("识图引擎未就绪")
    }
    SectionSurface(color = MaterialTheme.colorScheme.tertiaryContainer) {
        Text(
            text = "开始前需要处理",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = issues.joinToString(" · "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!state.environment.accessibilityEnabled) {
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(
                onClick = onEnableAccessibility,
            ) {
                Text("打开无障碍设置")
            }
        }
    }
}

@Composable
private fun LastRunSection(summary: RunSummary) {
    SectionSurface {
        MetricRow("刷新", "${summary.completedRefreshes} 次 · 扫描 ${summary.shopPagesScanned} 页")
        MetricRow(
            "购买",
            "书签 ${summary.covenantBookmarksBought} · 奖牌 ${summary.mysticMedalsBought}",
        )
        MetricRow("耗时", formatDuration(summary.elapsedMs))
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text = summary.stopReason,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun TaskListScreen(
    state: MainUiState,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onOpenTask: (AutomationTask) -> Unit,
) {
    val shopActive = state.automation.isRunning || state.automation.phase == AutomationPhase.PAUSED
    val huntActive = state.huntAutomation.isRunning || state.huntAutomation.phase == HuntPhase.PAUSED
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = AutomationTask.SHOP.name) {
            TaskGroupItem(
                modifier = Modifier.taskSharedBounds(
                    task = AutomationTask.SHOP,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                ),
                title = "神秘商店",
                subtitle = "自动刷新并购买书签、奖牌",
                status = taskStatus(
                    active = shopActive,
                    paused = state.automation.phase == AutomationPhase.PAUSED,
                    ready = state.environment.canPrepare && state.automation.templatesReady,
                    error = state.automation.phase == AutomationPhase.ERROR,
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
        item(key = AutomationTask.HUNT.name) {
            TaskGroupItem(
                modifier = Modifier.taskSharedBounds(
                    task = AutomationTask.HUNT,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                ),
                title = "讨伐",
                subtitle = "选择地下城并执行托管战斗",
                status = taskStatus(
                    active = huntActive,
                    paused = state.huntAutomation.phase == HuntPhase.PAUSED,
                    ready = state.environment.canPrepare && state.huntAutomation.templatesReady,
                    error = state.huntAutomation.phase == HuntPhase.ERROR,
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

private fun taskStatus(
    active: Boolean,
    paused: Boolean,
    ready: Boolean,
    error: Boolean,
): String = when {
    error -> "出错"
    paused -> "已暂停"
    active -> "运行中"
    ready -> "可用"
    else -> "需设置"
}

@Composable
private fun TaskBlockedReason(reason: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = MaterialTheme.colorScheme.tertiary,
                    shape = CircleShape,
                ),
        )
        Text(
            text = "无法开始：$reason",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Modifier.taskSharedBounds(
    task: AutomationTask,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
): Modifier {
    val baseModifier = this
    return with(sharedTransitionScope) {
        baseModifier.sharedBounds(
            sharedContentState = rememberSharedContentState("automation-task-${task.name}"),
            animatedVisibilityScope = animatedVisibilityScope,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        )
    }
}

@Composable
internal fun ShopTaskScreen(
    state: MainUiState,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
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
    val blockedReason = when {
        active -> null
        state.huntAutomation.isRunning || state.huntAutomation.phase == HuntPhase.PAUSED ->
            "讨伐任务正在运行"
        !state.environment.canPrepare -> "请先完成运行环境设置"
        !state.automation.templatesReady -> "识图模板未就绪"
        !state.config.hasPurchaseTarget -> "至少选择一项购买目标"
        else -> null
    }

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                ShopTaskHeader(
                    state = state,
                    active = active,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                )
            }
            item {
                SectionTitle(
                    title = "购买目标",
                    detail = if (active) "运行中不可修改" else null,
                )
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
            blockedReason?.let { reason ->
                item { TaskBlockedReason(reason) }
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
private fun ShopTaskHeader(
    state: MainUiState,
    active: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    TaskDetailCard(
        modifier = Modifier.taskSharedBounds(
            task = AutomationTask.SHOP,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
        ),
        title = "神秘商店",
        subtitle = if (state.automation.phase == AutomationPhase.IDLE) {
            "自动刷新并购买书签、奖牌"
        } else {
            state.automation.message
        },
        status = when {
            state.automation.phase == AutomationPhase.ERROR -> "出错"
            state.automation.phase == AutomationPhase.PAUSED -> "已暂停"
            active -> "运行中"
            state.environment.canPrepare && state.automation.templatesReady -> "可用"
            else -> "需设置"
        },
        statusPositive = active,
        leading = {
            Image(
                painter = painterResource(R.drawable.ic_secret_shop),
                contentDescription = null,
                modifier = Modifier
                    .size(72.dp)
                    .clip(MaterialTheme.shapes.large),
                contentScale = ContentScale.Crop,
            )
        },
    )
}

@Composable
internal fun HuntTaskScreen(
    state: MainUiState,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
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
    val blockedReason = when {
        active -> null
        state.automation.isRunning || state.automation.phase == AutomationPhase.PAUSED ->
            "神秘商店任务正在运行"
        !state.environment.canPrepare -> "请先完成运行环境设置"
        !state.huntAutomation.templatesReady -> "讨伐识图模板未就绪"
        else -> null
    }

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                HuntTaskHeader(
                    state = state,
                    active = active,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                )
            }
            item {
                SectionTitle("地下城")
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                                label = {
                                    Text(difficulty.displayName(includeAvailability = true))
                                },
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
                    HuntEnergyRefill.entries.forEachIndexed { index, refill ->
                        EnergyRefillOption(
                            refill = refill,
                            selected = state.huntConfig.energyRefill == refill,
                            enabled = !active && refill.isSupported,
                            onClick = { onEnergyRefillChanged(refill) },
                        )
                        if (index < HuntEnergyRefill.entries.lastIndex) {
                            GroupDivider()
                        }
                    }
                }
            }
            blockedReason?.let { reason ->
                item { TaskBlockedReason(reason) }
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
private fun HuntTaskHeader(
    state: MainUiState,
    active: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    TaskDetailCard(
        modifier = Modifier.taskSharedBounds(
            task = AutomationTask.HUNT,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
        ),
        title = "讨伐",
        subtitle = if (state.huntAutomation.phase == HuntPhase.IDLE) {
            "${state.huntConfig.dungeon.displayName} · 托管战斗"
        } else {
            state.huntAutomation.message
        },
        status = when {
            state.huntAutomation.phase == HuntPhase.ERROR -> "出错"
            state.huntAutomation.phase == HuntPhase.PAUSED -> "已暂停"
            active -> "运行中"
            state.environment.canPrepare && state.huntAutomation.templatesReady -> "可用"
            else -> "需设置"
        },
        statusPositive = active,
        leading = {
            Image(
                painter = painterResource(state.huntConfig.dungeon.imageResource()),
                contentDescription = null,
                modifier = Modifier
                    .size(72.dp)
                    .clip(MaterialTheme.shapes.large),
                contentScale = ContentScale.Crop,
            )
        },
    )
}

@Composable
private fun DungeonOption(
    dungeon: HuntDungeon,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.width(126.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
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
    ListItem(
        headlineContent = {
            Text(refill.displayName(includeAvailability = true))
        },
        leadingContent = {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        modifier = Modifier.selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onClick,
        ),
    )
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
