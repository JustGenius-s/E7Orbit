package com.e7orbit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.e7orbit.R
import java.text.DateFormat
import java.util.Date


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
