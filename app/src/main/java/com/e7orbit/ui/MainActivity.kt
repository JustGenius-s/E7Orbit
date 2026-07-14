package com.e7orbit.ui

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.e7orbit.AppGraph
import com.e7orbit.capture.MediaProjectionCaptureService
import com.e7orbit.model.AutomationPhase
import com.e7orbit.model.AutomationStatus
import com.e7orbit.model.RunConfig
import com.e7orbit.model.RunSummary
import com.e7orbit.ui.theme.E7OrbitTheme
import com.e7orbit.ui.theme.OrbitBackground
import com.e7orbit.ui.theme.OrbitError
import com.e7orbit.ui.theme.OrbitOnSurfaceMuted
import com.e7orbit.ui.theme.OrbitPrimary
import com.e7orbit.ui.theme.OrbitSecondary
import com.e7orbit.ui.theme.OrbitSuccess
import com.e7orbit.ui.theme.OrbitSurface
import com.e7orbit.ui.theme.OrbitWarning
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
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
                viewModel.prepareRun()
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
                    onEnableAccessibility = viewModel::openAccessibilitySettings,
                    onPrepare = ::requestProjection,
                    onPauseOrResume = viewModel::pauseOrResume,
                    onStop = viewModel::stop,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshEnvironment()
    }

    private fun requestProjection() {
        if (AppGraph.projectionCapture.isReady.value) {
            viewModel.prepareRun()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}

@Composable
private fun OrbitDashboard(
    state: MainUiState,
    onBuyCovenantChanged: (Boolean) -> Unit,
    onBuyMysticChanged: (Boolean) -> Unit,
    onMaxRefreshChanged: (Int) -> Unit,
    onThresholdChanged: (Double) -> Unit,
    onEnableAccessibility: () -> Unit,
    onPrepare: () -> Unit,
    onPauseOrResume: () -> Unit,
    onStop: () -> Unit,
) {
    val background = Brush.linearGradient(
        colors = listOf(
            Color(0xFFF8F3EA),
            Color(0xFFF1E9F8),
            Color(0xFFF6EBDD),
        ),
    )
    Scaffold(
        containerColor = OrbitBackground,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(background)
                .padding(contentPadding)
                .padding(horizontal = 32.dp, vertical = 20.dp),
        ) {
            Header(status = state.automation)
            Spacer(Modifier.height(18.dp))
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
                    AutomationCard(
                        config = state.config,
                        canStart = state.environment.canPrepare &&
                            state.config.hasPurchaseTarget &&
                            !state.automation.isRunning,
                        automation = state.automation,
                        onBuyCovenantChanged = onBuyCovenantChanged,
                        onBuyMysticChanged = onBuyMysticChanged,
                        onMaxRefreshChanged = onMaxRefreshChanged,
                        onThresholdChanged = onThresholdChanged,
                        onPrepare = onPrepare,
                        onPauseOrResume = onPauseOrResume,
                        onStop = onStop,
                    )
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
    }
}

@Composable
private fun Header(status: AutomationStatus) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(OrbitSecondary, OrbitPrimary, Color(0xFFF0A15A)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("E7", fontWeight = FontWeight.Black, color = Color.White)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = "E7 Orbit",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "国服 · MuMu 12 · 1920×1080",
                color = OrbitOnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.weight(1f))
        StatusPill(status)
    }
}

@Composable
private fun StatusPill(status: AutomationStatus) {
    val color = when (status.phase) {
        AutomationPhase.COMPLETED -> OrbitSuccess
        AutomationPhase.ERROR -> OrbitError
        AutomationPhase.PAUSED -> OrbitWarning
        AutomationPhase.IDLE -> OrbitOnSurfaceMuted
        else -> OrbitSecondary
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(status.phase.label(), color = color, fontWeight = FontWeight.SemiBold)
    }
}

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
        Text(
            "秘密商店自动化",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "进入秘密商店后自动识别、二次确认购买并刷新。",
            color = OrbitOnSurfaceMuted,
        )
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
                color = OrbitSecondary,
                fontWeight = FontWeight.Bold,
            )
        }
        Slider(
            value = config.matchThreshold.toFloat(),
            onValueChange = { onThresholdChanged(it.toDouble()) },
            valueRange = 0.85f..0.98f,
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
                ) {
                    Text(if (automation.phase == AutomationPhase.PAUSED) "继续" else "暂停")
                }
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OrbitError),
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
            ) {
                Text("准备运行", fontWeight = FontWeight.Bold)
            }
        }
        if (automation.phase != AutomationPhase.IDLE) {
            Spacer(Modifier.height(10.dp))
            Text(
                automation.message,
                color = if (automation.phase == AutomationPhase.ERROR) {
                    OrbitError
                } else {
                    OrbitOnSurfaceMuted
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
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
        CheckRow(
            "1920×1080 横屏",
            environment.resolutionReady,
            "${environment.width}×${environment.height}",
        )
        CheckRow("OpenCV 已就绪", environment.openCvReady)
        CheckRow(
            "识图模板",
            environment.templatesReady,
            if (environment.templatesReady) {
                "已加载"
            } else {
                "缺少 ${environment.missingTemplates.size} 项"
            },
        )
        if (!environment.accessibilityEnabled) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onEnableAccessibility,
                modifier = Modifier.fillMaxWidth(),
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
private fun OrbitCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = OrbitSurface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = androidx.compose.foundation.BorderStroke(
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
            Text(subtitle, color = OrbitOnSurfaceMuted, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        Text(label, color = OrbitOnSurfaceMuted, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

private fun AutomationPhase.label(): String = when (this) {
    AutomationPhase.IDLE -> "待机"
    AutomationPhase.WAITING_FOR_SERVICE -> "等待服务"
    AutomationPhase.WAITING_FOR_SHOP -> "等待秘密商店"
    AutomationPhase.SCANNING_TOP -> "扫描上半页"
    AutomationPhase.VERIFYING_PURCHASE -> "确认购买"
    AutomationPhase.SCANNING_BOTTOM -> "扫描下半页"
    AutomationPhase.REFRESHING -> "刷新中"
    AutomationPhase.WAITING_FOR_REFRESH -> "等待加载"
    AutomationPhase.PAUSED -> "已暂停"
    AutomationPhase.COMPLETED -> "已完成"
    AutomationPhase.ERROR -> "异常停止"
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1000
    return "%02d:%02d:%02d".format(
        totalSeconds / 3600,
        (totalSeconds % 3600) / 60,
        totalSeconds % 60,
    )
}
