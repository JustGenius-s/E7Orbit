package com.e7orbit.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Image
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.e7orbit.R
import com.e7orbit.data.E7Artifact
import com.e7orbit.ui.theme.OrbitArtifactHighlight


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ArtifactDetailScreen(
    artifact: E7Artifact?,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    if (artifact == null) {
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item { DataMissingDetail("神器数据不可用") }
        }
        return
    }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val artwork: @Composable (Modifier) -> Unit = { artModifier ->
        Box(
            modifier = artModifier
                .catalogSharedBounds(
                    key = "catalog-artifact-${artifact.code}",
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                )
                .clip(MaterialTheme.shapes.extraLargeIncreased),
        ) {
            if (artifact.imageUrl != null) {
                RemoteImage(
                    url = artifact.imageUrl,
                    contentDescription = artifact.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = MaterialTheme.shapes.extraLargeIncreased,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            artifact.name.take(1),
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
            }
            HeroClassIcon(
                role = artifact.role,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                size = 34.dp,
            )
            VerticalHeroStars(
                stars = artifact.rarity,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
                iconSize = 28.dp,
            )
        }
    }

    if (isLandscape) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.Top,
            ) {
                artwork(
                    Modifier
                        .width(220.dp)
                        .height(320.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        artifact.name,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    artifact.lore?.takeIf(String::isNotBlank)?.let { lore ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            lore,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                item {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ArtifactLevelSection(
                        title = "Base Level",
                        description = artifact.description,
                        comparisonDescription = artifact.maxDescription,
                        attack = artifact.baseAttack,
                        health = artifact.baseHealth,
                    )
                }
                item {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ArtifactLevelSection(
                        title = "Max Level",
                        description = artifact.maxDescription,
                        comparisonDescription = artifact.description,
                        attack = artifact.attack,
                        health = artifact.health,
                    )
                }
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                artwork(
                    Modifier
                        .width(180.dp)
                        .height(260.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        artifact.name,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    artifact.lore?.takeIf(String::isNotBlank)?.let { lore ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            lore,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                item {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ArtifactLevelSection(
                        title = "Base Level",
                        description = artifact.description,
                        comparisonDescription = artifact.maxDescription,
                        attack = artifact.baseAttack,
                        health = artifact.baseHealth,
                    )
                }
                item {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ArtifactLevelSection(
                        title = "Max Level",
                        description = artifact.maxDescription,
                        comparisonDescription = artifact.description,
                        attack = artifact.attack,
                        health = artifact.health,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtifactLevelSection(
    title: String,
    description: String?,
    comparisonDescription: String? = null,
    attack: Int?,
    health: Int?,
) {
    Column(modifier = Modifier.padding(vertical = 14.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        description?.takeIf(String::isNotBlank)?.let { desc ->
            Spacer(Modifier.height(8.dp))
            val highlightedDescription = remember(desc, comparisonDescription) {
                buildAnnotatedString {
                    append(desc)
                    comparisonDescription
                        ?.takeIf(String::isNotBlank)
                        ?.let { otherDescription ->
                            changedDescriptionRanges(otherDescription, desc).forEach { range ->
                                addStyle(
                                    style = SpanStyle(color = OrbitArtifactHighlight),
                                    start = range.start,
                                    end = range.endExclusive,
                                )
                            }
                        }
                }
            }
            Text(
                highlightedDescription,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (attack != null || health != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (attack != null) {
                    ArtifactStatValue(R.drawable.e7_stat_attack, "攻击", attack)
                }
                if (attack != null && health != null) {
                    Spacer(Modifier.width(40.dp))
                }
                if (health != null) {
                    ArtifactStatValue(R.drawable.e7_stat_health, "生命", health)
                }
            }
        }
    }
}

@Composable
private fun ArtifactStatValue(
    iconRes: Int,
    contentDescription: String,
    value: Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$value",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun DataMissingDetail(message: String) {
    SectionSurface {
        Text(message, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("返回列表后重新选择。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
