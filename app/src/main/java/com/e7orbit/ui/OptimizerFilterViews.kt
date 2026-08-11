package com.e7orbit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.e7orbit.R
import com.e7orbit.optimizer.GearInventoryFilter
import com.e7orbit.optimizer.GearInventorySort
import com.e7orbit.optimizer.GearSortDirection
import com.e7orbit.optimizer.GearSortField
import com.e7orbit.optimizer.HeroBuildSort
import com.e7orbit.optimizer.HeroBuildSortField

internal fun statFilterLabel(type: String): String = when (type) {
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

internal fun GearInventoryFilter.activeFilterCount(): Int =
    setCodes.size + mainStatTypes.size + substatTypes.size + minimumScore.takeIf { it > 0 }?.let { 1 }.orZero()

private fun Int?.orZero(): Int = this ?: 0

internal fun GearSortDirection.toggled(): GearSortDirection =
    if (this == GearSortDirection.DESCENDING) {
        GearSortDirection.ASCENDING
    } else {
        GearSortDirection.DESCENDING
    }

@Composable
internal fun HeroBuildSortControls(
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
internal fun CompactDropdown(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(modifier = modifier) {
        FilterChip(
            selected = false,
            onClick = { expanded = true },
            enabled = enabled,
            label = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    leadingContent?.invoke()
                    Text(
                        label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
internal fun GearFilterPanel(
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
internal fun SortDirectionChip(
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

@Composable
internal fun GearFilterGroup(
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
