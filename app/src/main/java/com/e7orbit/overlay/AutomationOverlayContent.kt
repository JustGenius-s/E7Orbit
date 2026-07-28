package com.e7orbit.overlay

import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.e7orbit.R
import com.e7orbit.automation.TaskKind
import com.e7orbit.model.AutomationPhase
import com.e7orbit.model.AutomationStatus
import com.e7orbit.model.HuntPhase
import com.e7orbit.model.HuntStatus
import com.e7orbit.ui.theme.OrbitSuccess
import com.e7orbit.ui.theme.OrbitWarning
import java.text.NumberFormat

@Composable
internal fun AutomationOverlayContent(
    state: AutomationOverlayUiState,
    morph: Float,
    dockSide: OverlayDockSide,
    stopConfirmationPending: Boolean,
    onPrimaryClick: () -> Unit,
    onReturnToApp: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val phaseColor = state.phaseColor()
    val progress by animateFloatAsState(
        targetValue = state.progress,
        animationSpec = tween(OverlayUiTokens.PROGRESS_DURATION_MS.toInt()),
        label = "overlay progress",
    )
    val compactAlpha = (2f - morph).coerceIn(0f, 1f)
    val expandedAlpha = (morph - 1f).coerceIn(0f, 1f)
    val edgeAlignment = if (dockSide == OverlayDockSide.START) {
        Alignment.CenterStart
    } else {
        Alignment.CenterEnd
    }
    val dragModifier = Modifier.overlayDrag(
        onDragStart = onDragStart,
        onDrag = onDrag,
        onDragEnd = onDragEnd,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        if (morph <= OverlayPresentation.COMPACT.morph) {
            MorphingEdgeBubble(
                state = state,
                progress = progress,
                phaseColor = phaseColor,
                dockSide = dockSide,
                morph = morph.coerceIn(0f, 1f),
                onClick = onPrimaryClick,
                modifier = dragModifier,
            )
        } else if (compactAlpha > 0f) {
            StatusBubble(
                state = state,
                progress = progress,
                phaseColor = phaseColor,
                expanded = false,
                onClick = onPrimaryClick,
                modifier = dragModifier
                    .then(Modifier.align(edgeAlignment))
                    .graphicsLayer { alpha = compactAlpha },
            )
        }

        if (expandedAlpha > 0f) {
            ExpandedControls(
                state = state,
                progress = progress,
                phaseColor = phaseColor,
                dockSide = dockSide,
                stopConfirmationPending = stopConfirmationPending,
                onBubbleClick = onPrimaryClick,
                onReturnToApp = onReturnToApp,
                onPauseResume = onPauseResume,
                onStop = onStop,
                modifier = dragModifier.graphicsLayer { alpha = expandedAlpha },
            )
        }
    }
}

@Composable
internal fun AutomationOverlayEdgeTouchTarget(
    onClick: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .overlayDrag(
                onDragStart = onDragStart,
                onDrag = onDrag,
                onDragEnd = onDragEnd,
            )
            .clickable(
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                contentDescription = "打开自动化控制"
            },
    )
}

@Composable
private fun MorphingEdgeBubble(
    state: AutomationOverlayUiState,
    progress: Float,
    phaseColor: Color,
    dockSide: OverlayDockSide,
    morph: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alignment = if (dockSide == OverlayDockSide.START) {
        Alignment.CenterStart
    } else {
        Alignment.CenterEnd
    }
    val edgeRadius = 6f
    val bubbleRadius =
        (OverlayUiTokens.COMPACT_SIZE_DP / 2f) - OverlayUiTokens.COMPACT_SURFACE_INSET_DP
    val outerRadius = (bubbleRadius * morph).dp
    val innerRadius = (edgeRadius + (bubbleRadius - edgeRadius) * morph).dp
    val shape = if (dockSide == OverlayDockSide.START) {
        RoundedCornerShape(
            topStart = outerRadius,
            bottomStart = outerRadius,
            topEnd = innerRadius,
            bottomEnd = innerRadius,
        )
    } else {
        RoundedCornerShape(
            topStart = innerRadius,
            bottomStart = innerRadius,
            topEnd = outerRadius,
            bottomEnd = outerRadius,
        )
    }
    val description = state.accessibilityDescription(
        presentation = if (morph < 0.5f) {
            OverlayPresentation.EDGE
        } else {
            OverlayPresentation.COMPACT
        },
        dockSide = dockSide.takeIf { morph < 0.5f },
    )
    val railColor = if (state.isActiveError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val railBorderColor = if (state.isActiveError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val surfaceSize = OverlayUiTokens.COMPACT_SIZE_DP -
        (OverlayUiTokens.COMPACT_SURFACE_INSET_DP * 2)
    val width = (
        OverlayUiTokens.EDGE_RAIL_WIDTH_DP +
            (surfaceSize - OverlayUiTokens.EDGE_RAIL_WIDTH_DP) * morph
        ).dp
    val height = (
        OverlayUiTokens.EDGE_RAIL_HEIGHT_DP +
            (surfaceSize - OverlayUiTokens.EDGE_RAIL_HEIGHT_DP) * morph
        ).dp
    val edgeInset = (OverlayUiTokens.COMPACT_SURFACE_INSET_DP * morph).dp
    val offsetX = if (dockSide == OverlayDockSide.START) edgeInset else -edgeInset
    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .align(alignment)
                .offset(x = offsetX)
                .width(width)
                .height(height)
                .semantics {
                    contentDescription = description
                    stateDescription = state.phaseLabel
                },
            shape = shape,
            color = lerp(railColor, MaterialTheme.colorScheme.surface, morph),
            border = BorderStroke(
                1.dp,
                lerp(railBorderColor, MaterialTheme.colorScheme.outlineVariant, morph),
            ),
            shadowElevation = (2f + 4f * morph).dp,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (morph < 1f) {
                    Box(modifier = Modifier.graphicsLayer { alpha = 1f - morph }) {
                        if (state.isActiveError) {
                            Text(
                                text = "!",
                                modifier = Modifier.clearAndSetSemantics { },
                                color = MaterialTheme.colorScheme.onError,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Black,
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(20.dp)
                                    .background(phaseColor, CircleShape)
                                    .clearAndSetSemantics { },
                            )
                        }
                    }
                }
                if (morph > 0f) {
                    Box(
                        modifier = Modifier.graphicsLayer { alpha = morph },
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .size((60f * morph).coerceAtLeast(1f).dp)
                                .clearAndSetSemantics { },
                            color = phaseColor,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            strokeWidth = 4.dp,
                        )
                        Text(
                            text = state.phaseLabel,
                            modifier = Modifier.clearAndSetSemantics { },
                            style = if (state.phaseLabel.length <= 2) {
                                MaterialTheme.typography.titleSmall
                            } else {
                                MaterialTheme.typography.labelMedium
                            },
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBubble(
    state: AutomationOverlayUiState,
    progress: Float,
    phaseColor: Color,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = state.accessibilityDescription(
        presentation = if (expanded) {
            OverlayPresentation.EXPANDED
        } else {
            OverlayPresentation.COMPACT
        },
        dockSide = null,
    )
    Box(
        modifier = modifier.size(OverlayUiTokens.COMPACT_SIZE_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .padding(OverlayUiTokens.COMPACT_SURFACE_INSET_DP.dp)
                .fillMaxSize()
                .semantics {
                    contentDescription = description
                    stateDescription = state.phaseLabel
                },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 6.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .size(60.dp)
                        .clearAndSetSemantics { },
                    color = phaseColor,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeWidth = 4.dp,
                )
                Text(
                    text = if (expanded) state.runCountLabel else state.phaseLabel,
                    modifier = Modifier.clearAndSetSemantics { },
                    style = when {
                        expanded -> MaterialTheme.typography.labelMedium
                        state.phaseLabel.length <= 2 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.labelMedium
                    },
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ExpandedControls(
    state: AutomationOverlayUiState,
    progress: Float,
    phaseColor: Color,
    dockSide: OverlayDockSide,
    stopConfirmationPending: Boolean,
    onBubbleClick: () -> Unit,
    onReturnToApp: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier,
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(OverlayUiTokens.COMPACT_SURFACE_INSET_DP.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dockSide == OverlayDockSide.START) {
                StatusBubble(
                    state = state,
                    progress = progress,
                    phaseColor = phaseColor,
                    expanded = true,
                    onClick = onBubbleClick,
                    modifier = modifier,
                )
                OverlayMetrics(state, Modifier.weight(1f))
                OverlayActions(
                    state = state,
                    stopConfirmationPending = stopConfirmationPending,
                    onReturnToApp = onReturnToApp,
                    onPauseResume = onPauseResume,
                    onStop = onStop,
                )
            } else {
                OverlayActions(
                    state = state,
                    stopConfirmationPending = stopConfirmationPending,
                    onReturnToApp = onReturnToApp,
                    onPauseResume = onPauseResume,
                    onStop = onStop,
                )
                OverlayMetrics(state, Modifier.weight(1f))
                StatusBubble(
                    state = state,
                    progress = progress,
                    phaseColor = phaseColor,
                    expanded = true,
                    onClick = onBubbleClick,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun OverlayMetrics(
    state: AutomationOverlayUiState,
    modifier: Modifier = Modifier,
) {
    if (state.activeMode == OverlayMode.HUNT) {
        Row(
            modifier = modifier
                .fillMaxHeight()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextMetric("完成", state.huntStatus.stats.completedRuns.toString(), Modifier.weight(1f))
            TextMetric("目标", state.huntStatus.config.runCount.toString(), Modifier.weight(1f))
            TextMetric(
                "模式",
                if (state.huntStatus.config.managedBattle) "托管" else "普通",
                Modifier.weight(1f),
            )
        }
        return
    }

    val covenantIcon = rememberAssetImage("$VISION_ASSET_ROOT/covenant_item.png")
    val mysticIcon = rememberAssetImage("$VISION_ASSET_ROOT/mystic_item.png")
    Row(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ImageMetric(
            label = "誓约书签",
            value = "+${state.shopStatus.stats.covenantBookmarksGained}",
            image = covenantIcon,
            fallback = "B",
            modifier = Modifier.weight(1f),
        )
        ImageMetric(
            label = "神秘奖牌",
            value = "+${state.shopStatus.stats.mysticMedalsGained}",
            image = mysticIcon,
            fallback = "M",
            modifier = Modifier.weight(1f),
        )
        ImageMetric(
            label = "金币消耗",
            value = "-${NumberFormat.getIntegerInstance().format(state.shopStatus.stats.goldSpent)}",
            image = null,
            fallback = "G",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TextMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .semantics(mergeDescendants = true) {
                contentDescription = "$label $value"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ImageMetric(
    label: String,
    value: String,
    image: ImageBitmap?,
    fallback: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label $value"
        },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (image == null) {
            Text(
                text = fallback,
                modifier = Modifier.width(22.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        } else {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(2.dp))
        Text(
            text = value,
            modifier = Modifier.wrapContentWidth(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OverlayActions(
    state: AutomationOverlayUiState,
    stopConfirmationPending: Boolean,
    onReturnToApp: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OverlayActionButton(
            icon = R.drawable.ic_nav_home,
            label = "返回 E7 Orbit",
            onClick = onReturnToApp,
        )
        OverlayActionButton(
            icon = when {
                state.isActiveTerminal -> R.drawable.ic_refresh
                state.isActivePaused -> R.drawable.ic_play
                else -> R.drawable.ic_pause
            },
            label = when {
                state.isActiveTerminal -> "重新运行"
                state.isActivePaused -> "继续"
                else -> "暂停"
            },
            onClick = onPauseResume,
        )
        OverlayActionButton(
            icon = when {
                state.isActiveTerminal -> R.drawable.ic_close
                stopConfirmationPending -> R.drawable.ic_priority_high
                else -> R.drawable.ic_stop
            },
            label = when {
                state.isActiveTerminal -> "关闭结果"
                stopConfirmationPending -> "再次点按停止"
                else -> "停止"
            },
            onClick = onStop,
            danger = !state.isActiveTerminal,
        )
    }
}

@Composable
private fun OverlayActionButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (danger) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            modifier = Modifier.size(21.dp),
        )
    }
}

private fun Modifier.overlayDrag(
    onDragStart: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
): Modifier = pointerInput(onDragStart, onDrag, onDragEnd) {
    detectDragGestures(
        onDragStart = { onDragStart() },
        onDragEnd = onDragEnd,
        onDragCancel = onDragEnd,
    ) { _, dragAmount ->
        onDrag(dragAmount.x, dragAmount.y)
    }
}

@Composable
private fun rememberAssetImage(path: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(path) {
        runCatching {
            context.assets.open(path).use(BitmapFactory::decodeStream).asImageBitmap()
        }.getOrNull()
    }
}

@Composable
private fun AutomationOverlayUiState.phaseColor(): Color = when {
    isActiveCompleted -> OrbitSuccess
    isActiveError -> MaterialTheme.colorScheme.error
    isActivePaused -> OrbitWarning
    else -> MaterialTheme.colorScheme.onSurface
}

internal data class AutomationOverlayUiState(
    val shopStatus: AutomationStatus = AutomationStatus(),
    val huntStatus: HuntStatus = HuntStatus(),
    val activeMode: OverlayMode = OverlayMode.SHOP,
) {
    val activeTaskKind: TaskKind
        get() = if (activeMode == OverlayMode.SHOP) TaskKind.SHOP else TaskKind.HUNT

    val isActiveTerminal: Boolean
        get() = when (activeMode) {
            OverlayMode.SHOP -> shopStatus.isTerminal
            OverlayMode.HUNT -> huntStatus.isTerminal
        }

    val isActivePaused: Boolean
        get() = when (activeMode) {
            OverlayMode.SHOP -> shopStatus.phase == AutomationPhase.PAUSED
            OverlayMode.HUNT -> huntStatus.phase == HuntPhase.PAUSED
        }

    val isActiveCompleted: Boolean
        get() = when (activeMode) {
            OverlayMode.SHOP -> shopStatus.phase == AutomationPhase.COMPLETED
            OverlayMode.HUNT -> huntStatus.phase == HuntPhase.COMPLETED
        }

    val isActiveError: Boolean
        get() = when (activeMode) {
            OverlayMode.SHOP -> shopStatus.phase == AutomationPhase.ERROR
            OverlayMode.HUNT -> huntStatus.phase == HuntPhase.ERROR
        }

    val progress: Float
        get() = when (activeMode) {
            OverlayMode.SHOP -> shopStatus.phase.overlayProgress()
            OverlayMode.HUNT -> huntStatus.phase.overlayProgress()
        }

    val phaseLabel: String
        get() = when (activeMode) {
            OverlayMode.SHOP -> shopStatus.phase.overlayLabel()
            OverlayMode.HUNT -> huntStatus.phase.overlayLabel()
        }

    val runCountLabel: String
        get() = when (activeMode) {
            OverlayMode.SHOP ->
                "${shopStatus.stats.completedRefreshes}/${shopStatus.config.maxRefreshes}"
            OverlayMode.HUNT ->
                "${huntStatus.stats.completedRuns}/${huntStatus.config.runCount}"
        }

    fun accessibilityDescription(
        presentation: OverlayPresentation,
        dockSide: OverlayDockSide?,
    ): String {
        val status = when (activeMode) {
            OverlayMode.SHOP -> buildString {
                append("E7 Orbit，已执行 ${shopStatus.stats.completedRefreshes} 次")
                append("，誓约书签增加 ${shopStatus.stats.covenantBookmarksGained}")
                append("，神秘奖牌增加 ${shopStatus.stats.mysticMedalsGained}")
                append("，金币消耗 ${shopStatus.stats.goldSpent}")
            }
            OverlayMode.HUNT ->
                "E7 Orbit，${huntStatus.config.dungeon.displayName} " +
                    "${huntStatus.stats.completedRuns}/${huntStatus.config.runCount}"
        }
        return status + when (presentation) {
            OverlayPresentation.EDGE -> {
                val side = if (dockSide == OverlayDockSide.START) "左侧" else "右侧"
                "，已贴在${side}收起，点按显示悬浮球"
            }
            OverlayPresentation.COMPACT -> "，点按展开控制面板"
            OverlayPresentation.EXPANDED -> "，点按收起控制面板"
        }
    }

    companion object {
        fun from(
            shopStatus: AutomationStatus,
            huntStatus: HuntStatus,
        ): AutomationOverlayUiState {
            val activeMode = when {
                huntStatus.isRunning || huntStatus.phase == HuntPhase.PAUSED -> OverlayMode.HUNT
                shopStatus.isRunning || shopStatus.phase == AutomationPhase.PAUSED -> OverlayMode.SHOP
                huntStatus.phase != HuntPhase.IDLE -> OverlayMode.HUNT
                else -> OverlayMode.SHOP
            }
            return AutomationOverlayUiState(shopStatus, huntStatus, activeMode)
        }
    }
}

internal enum class OverlayMode {
    SHOP,
    HUNT,
}

internal enum class OverlayPresentation(
    val morph: Float,
) {
    EDGE(0f),
    COMPACT(1f),
    EXPANDED(2f),
}

internal object OverlayUiTokens {
    const val EDGE_HANDLE_WIDTH_DP = 28
    const val EDGE_RAIL_WIDTH_DP = 12
    const val EDGE_RAIL_HEIGHT_DP = 44
    const val COMPACT_SIZE_DP = 72
    const val COMPACT_SURFACE_INSET_DP = 2
    const val HEIGHT_DP = 72
    const val EXPANDED_WIDTH_DP = 480
    const val SCREEN_MARGIN_DP = 8
    const val PRESENTATION_DURATION_MS = 240L
    const val PROGRESS_DURATION_MS = 260L
    const val STOP_CONFIRMATION_MS = 3_000L
    const val AUTO_DOCK_DELAY_MS = 2_400L
}

private fun AutomationPhase.overlayProgress(): Float = when (this) {
    AutomationPhase.IDLE -> 0f
    AutomationPhase.WAITING_FOR_SERVICE -> 0.04f
    AutomationPhase.WAITING_FOR_SHOP -> 0.08f
    AutomationPhase.SCANNING_TOP -> 0.22f
    AutomationPhase.PURCHASING -> 0.34f
    AutomationPhase.VERIFYING_PURCHASE -> 0.46f
    AutomationPhase.SCANNING_BOTTOM -> 0.58f
    AutomationPhase.REFRESHING -> 0.76f
    AutomationPhase.WAITING_FOR_REFRESH -> 0.92f
    AutomationPhase.PAUSED -> 0f
    AutomationPhase.COMPLETED -> 1f
    AutomationPhase.ERROR -> 1f
}

private fun AutomationPhase.overlayLabel(): String = when (this) {
    AutomationPhase.IDLE -> "待机"
    AutomationPhase.WAITING_FOR_SERVICE -> "服务"
    AutomationPhase.WAITING_FOR_SHOP -> "等待"
    AutomationPhase.SCANNING_TOP -> "上扫"
    AutomationPhase.PURCHASING -> "购买"
    AutomationPhase.VERIFYING_PURCHASE -> "确认"
    AutomationPhase.SCANNING_BOTTOM -> "下扫"
    AutomationPhase.REFRESHING -> "刷新"
    AutomationPhase.WAITING_FOR_REFRESH -> "加载"
    AutomationPhase.PAUSED -> "暂停"
    AutomationPhase.COMPLETED -> "完成"
    AutomationPhase.ERROR -> "异常"
}

private fun HuntPhase.overlayProgress(): Float = when (this) {
    HuntPhase.IDLE -> 0f
    HuntPhase.WAITING_FOR_LOBBY -> 0.05f
    HuntPhase.OPENING_BATTLE -> 0.12f
    HuntPhase.OPENING_HUNT -> 0.20f
    HuntPhase.SELECTING_BOSS -> 0.28f
    HuntPhase.SELECTING_DIFFICULTY -> 0.36f
    HuntPhase.DISABLING_QUICK_BATTLE -> 0.44f
    HuntPhase.CONFIGURING_MANAGED_BATTLE -> 0.52f
    HuntPhase.STARTING_BATTLE -> 0.60f
    HuntPhase.WAITING_FOR_BATTLE_CONTROLS -> 0.68f
    HuntPhase.DELEGATING_BATTLE -> 0.76f
    HuntPhase.CONFIRMING_DELEGATION -> 0.84f
    HuntPhase.MANAGED_IN_LOBBY -> 0.94f
    HuntPhase.PAUSED -> 0f
    HuntPhase.COMPLETED -> 1f
    HuntPhase.ERROR -> 1f
}

private fun HuntPhase.overlayLabel(): String = when (this) {
    HuntPhase.IDLE -> "待机"
    HuntPhase.WAITING_FOR_LOBBY -> "大厅"
    HuntPhase.OPENING_BATTLE -> "战斗"
    HuntPhase.OPENING_HUNT -> "讨伐"
    HuntPhase.SELECTING_BOSS -> "地下城"
    HuntPhase.SELECTING_DIFFICULTY -> "难度"
    HuntPhase.DISABLING_QUICK_BATTLE -> "快战"
    HuntPhase.CONFIGURING_MANAGED_BATTLE -> "托管"
    HuntPhase.STARTING_BATTLE -> "开始"
    HuntPhase.WAITING_FOR_BATTLE_CONTROLS -> "加载"
    HuntPhase.DELEGATING_BATTLE -> "转交"
    HuntPhase.CONFIRMING_DELEGATION -> "确认"
    HuntPhase.MANAGED_IN_LOBBY -> "挂机"
    HuntPhase.PAUSED -> "暂停"
    HuntPhase.COMPLETED -> "完成"
    HuntPhase.ERROR -> "异常"
}

private const val VISION_ASSET_ROOT = "vision/cn_1920x1080"
