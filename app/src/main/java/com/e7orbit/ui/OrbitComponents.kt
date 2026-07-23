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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.e7orbit.R
import com.e7orbit.ui.theme.OrbitSuccess
import com.e7orbit.ui.theme.OrbitWarning

@Composable
internal fun SectionTitle(
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        detail?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SectionSurface(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = color,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content,
        )
    }
}

@Composable
internal fun StatusPill(
    label: String,
    positive: Boolean,
    modifier: Modifier = Modifier,
) {
    val container = if (positive) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val content = if (positive) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    Surface(
        modifier = modifier,
        color = container,
        contentColor = content,
        shape = CircleShape,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun ReadinessRow(
    title: String,
    ready: Boolean,
    detail: String = if (ready) "已就绪" else "需要处理",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    color = if (ready) OrbitSuccess else OrbitWarning,
                    shape = CircleShape,
                ),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = detail,
            color = if (ready) OrbitSuccess else OrbitWarning,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun MetricRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = value,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun ToggleSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
internal fun TaskGroupItem(
    title: String,
    subtitle: String,
    status: String,
    statusPositive: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(title, fontWeight = FontWeight.SemiBold)
        },
        supportingContent = { Text(subtitle) },
        leadingContent = leading,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = status,
                    color = if (statusPositive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
internal fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
internal fun TaskActionBar(
    active: Boolean,
    paused: Boolean,
    canStart: Boolean,
    startLabel: String,
    onPrepare: () -> Unit,
    onPauseOrResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        if (active) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(
                    onClick = onPauseOrResume,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) {
                    Text(if (paused) "继续" else "暂停", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
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
                    .padding(12.dp)
                    .height(56.dp),
                shape = CircleShape,
            ) {
                Text(startLabel, fontWeight = FontWeight.Bold)
            }
        }
    }
}
