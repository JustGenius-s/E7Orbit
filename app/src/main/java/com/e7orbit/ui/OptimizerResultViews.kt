package com.e7orbit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.e7orbit.R
import com.e7orbit.data.E7Gear
import com.e7orbit.data.E7Hero
import com.e7orbit.optimizer.EquippedHeroBuild
import com.e7orbit.optimizer.EquipmentPlan
import com.e7orbit.optimizer.GearOptimizer
import com.e7orbit.optimizer.ImprintRank
import com.e7orbit.optimizer.OptimizedBuild
import com.e7orbit.optimizer.OptimizerMetric
import com.e7orbit.optimizer.OptimizerStat
import com.e7orbit.optimizer.containsBuild
import com.e7orbit.ui.theme.OrbitPolygonShapes
import com.e7orbit.ui.theme.asShape
import java.text.NumberFormat
import java.util.Locale

internal fun optimizerStatType(stat: OptimizerStat): String = when (stat) {
    OptimizerStat.ATTACK -> "Attack"
    OptimizerStat.HEALTH -> "Health"
    OptimizerStat.DEFENSE -> "Defense"
    OptimizerStat.SPEED -> "Speed"
    OptimizerStat.CRIT_CHANCE -> "CriticalHitChancePercent"
    OptimizerStat.CRIT_DAMAGE -> "CriticalHitDamagePercent"
    OptimizerStat.EFFECTIVENESS -> "EffectivenessPercent"
    OptimizerStat.RESISTANCE -> "EffectResistancePercent"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ImprintRankSelector(
    build: EquippedHeroBuild,
    selectedRank: ImprintRank,
    enabled: Boolean,
    onRankSelected: (ImprintRank) -> Unit,
) {
    val grades = build.hero?.memoryImprint?.concentration?.grades.orEmpty()
    if (grades.isEmpty()) {
        Text(
            "该英雄暂无自身刻印数据",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val gradeByRank = grades.associateBy { ImprintRank.of(it.rank) }
    val cookieShape = OrbitPolygonShapes.ImprintRankBadge.asShape
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ImprintRank.entries.forEach { rank ->
            val selected = rank == selectedRank
            val grade = gradeByRank[rank]
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                // 选中时 cookie 背景旋转 90°，图标保持不动。
                val rotation by animateFloatAsState(
                    targetValue = if (selected) 90f else 0f,
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                    label = "imprint rank rotation",
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        // clip 让 clickable 的波纹也按 cookie 形状裁剪，不再出现方块背景。
                        .clip(cookieShape)
                        .clickable(enabled = enabled) { onRankSelected(rank) },
                    contentAlignment = Alignment.Center,
                ) {
                    // 背景随 cookie 一起旋转；图标在其上层不受影响。
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer { rotationZ = rotation }
                            .then(
                                if (selected) {
                                    Modifier.background(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        cookieShape,
                                    )
                                } else {
                                    Modifier.border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        shape = cookieShape,
                                    )
                                },
                            ),
                    )
                    imprintRankIconRes(rank.label)?.let { resId ->
                        Image(
                            painter = painterResource(resId),
                            contentDescription = rank.label,
                            modifier = Modifier.width(32.dp).height(20.dp),
                            contentScale = ContentScale.Fit,
                            alpha = if (grade != null || selected) 1f else 0.45f,
                        )
                    } ?: Text(
                        rank.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text(
                    text = grade?.value?.imprintGradeLabel() ?: "—",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

private fun String.imprintGradeLabel(): String = when {
    startsWith("Effectiveness", ignoreCase = true) -> replaceFirst("Effectiveness", "命中", ignoreCase = true)
    startsWith("Effect Resistance", ignoreCase = true) -> replaceFirst("Effect Resistance", "抗性", ignoreCase = true)
    startsWith("Critical Hit Chance", ignoreCase = true) -> replaceFirst("Critical Hit Chance", "暴击", ignoreCase = true)
    startsWith("Critical Hit Damage", ignoreCase = true) -> replaceFirst("Critical Hit Damage", "暴伤", ignoreCase = true)
    startsWith("Attack", ignoreCase = true) -> replaceFirst("Attack", "攻击", ignoreCase = true)
    startsWith("Health", ignoreCase = true) -> replaceFirst("Health", "生命", ignoreCase = true)
    startsWith("Defense", ignoreCase = true) -> replaceFirst("Defense", "防御", ignoreCase = true)
    startsWith("Speed", ignoreCase = true) -> replaceFirst("Speed", "速度", ignoreCase = true)
    else -> this
}

@Composable
internal fun MinimumStatField(
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
internal fun OptimizerActionBar(
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
internal fun OptimizerRunningState() {
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
internal fun OptimizerMessageCard(title: String, detail: String, error: Boolean) {
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
internal fun OptimizedBuildCard(
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
internal fun OptimizerEmptyState(title: String, detail: String) {
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
internal fun SummaryMetric(
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
internal fun SummaryDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(32.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
internal fun OptimizerOverviewSummary(
    heroCount: Int,
    completeBuilds: Int,
    gearCount: Int,
    equippedCount: Int,
    content: com.e7orbit.optimizer.OptimizerContent,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (content == com.e7orbit.optimizer.OptimizerContent.HEROES) {
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
internal fun OptimizedSetRow(build: OptimizedBuild) {
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
