package com.e7orbit.ui

import android.content.res.Configuration

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalCenteredHeroCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.animation.core.FastOutSlowInEasing
import com.e7orbit.R
import com.e7orbit.data.E7Artifact
import com.e7orbit.data.E7Hero
import com.e7orbit.data.E7HeroAwakening
import com.e7orbit.data.E7ImprintSection
import com.e7orbit.data.E7StatusEffect
import com.e7orbit.data.E7HeroStats
import com.e7orbit.data.E7HeroSkill
import com.e7orbit.data.HeroRtaAnalysis
import com.e7orbit.data.RtaTier
import com.e7orbit.ui.theme.OrbitArtifactHighlight
import kotlinx.coroutines.launch
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
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    var selectedAttribute by rememberSaveable(data.section) { mutableStateOf("全部") }
    var selectedRole by rememberSaveable(data.section) { mutableStateOf("全部") }
    Column(modifier = modifier.fillMaxSize()) {
        DataSearchBar(
            data = data,
            onQueryChanged = onQueryChanged,
            onSelectHero = onSelectHero,
            onSelectArtifact = onSelectArtifact,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp),
        ) {
            SectionTabs(
                section = data.section,
                onSectionChanged = onSectionChanged,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (data.section == DataSection.HEROES) {
                    FilterDropdownChip(
                        label = "属性",
                        options = HeroAttributeFilters,
                        selected = selectedAttribute,
                        onSelected = { selectedAttribute = it },
                        iconRes = ::attributeFilterIconRes,
                    )
                }
                FilterDropdownChip(
                    label = "职业",
                    options = RoleFilters,
                    selected = selectedRole,
                    onSelected = { selectedRole = it },
                    iconRes = ::roleFilterIconRes,
                )
            }
            Spacer(Modifier.height(8.dp))

            when (data.loadState) {
                DataLoadState.IDLE -> DataEmptyState(
                    title = "图鉴尚未加载",
                    detail = "从官方 Stove 和 Fribbels 读取英雄与神器资料。",
                    action = "加载图鉴",
                    onAction = onLoad,
                )

                DataLoadState.LOADING -> DataLoadingState()
                DataLoadState.ERROR -> DataEmptyState(
                    title = "图鉴读取失败",
                    detail = data.errorMessage ?: "公开图鉴暂时不可用",
                    action = "重新尝试",
                    onAction = onLoad,
                    error = true,
                )

                DataLoadState.READY -> when (data.section) {
                    DataSection.HEROES -> HeroList(
                        data = data,
                        attributeFilter = selectedAttribute,
                        roleFilter = selectedRole,
                        onSelect = onSelectHero,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                    )

                    DataSection.ARTIFACTS -> ArtifactList(
                        data = data,
                        roleFilter = selectedRole,
                        onSelect = onSelectArtifact,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                }
            }
        }
    }
}

/** M3E top search that expands into a full-screen result surface on compact devices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DataSearchBar(
    data: DataUiState,
    onQueryChanged: (String) -> Unit,
    onSelectHero: (String) -> Unit,
    onSelectArtifact: (String) -> Unit,
) {
    val searchBarState = rememberSearchBarState()
    val textFieldState = rememberSaveable(saver = TextFieldState.Saver) {
        TextFieldState(data.query)
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collect(onQueryChanged)
    }
    LaunchedEffect(data.query) {
        if (data.query != textFieldState.text.toString()) {
            textFieldState.edit { replace(0, length, data.query) }
        }
    }

    val expanded = searchBarState.currentValue == SearchBarValue.Expanded
    val placeholder = when (data.section) {
        DataSection.HEROES -> "搜索英雄"
        DataSection.ARTIFACTS -> "搜索神器"
    }
    val inputField: @Composable () -> Unit = {
        SearchBarDefaults.InputField(
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            onSearch = { scope.launch { searchBarState.animateToCollapsed() } },
            placeholder = { Text(placeholder) },
            leadingIcon = {
                if (expanded) {
                    IconButton(onClick = { scope.launch { searchBarState.animateToCollapsed() } }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "返回",
                        )
                    }
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                    )
                }
            },
            trailingIcon = if (data.query.isNotEmpty()) {
                {
                    IconButton(onClick = { textFieldState.edit { replace(0, length, "") } }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = "清除搜索",
                        )
                    }
                }
            } else null,
        )
    }
    val selectHero: (String) -> Unit = { code ->
        scope.launch {
            searchBarState.animateToCollapsed()
            onSelectHero(code)
        }
    }
    val selectArtifact: (String) -> Unit = { code ->
        scope.launch {
            searchBarState.animateToCollapsed()
            onSelectArtifact(code)
        }
    }

    AppBarWithSearch(
        state = searchBarState,
        inputField = inputField,
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
    ExpandedFullScreenSearchBar(
        state = searchBarState,
        inputField = inputField,
    ) {
        CatalogSearchResults(
            data = data,
            onSelectHero = selectHero,
            onSelectArtifact = selectArtifact,
        )
    }
}

@Composable
private fun ColumnScope.CatalogSearchResults(
    data: DataUiState,
    onSelectHero: (String) -> Unit,
    onSelectArtifact: (String) -> Unit,
) {
    when {
        data.loadState == DataLoadState.LOADING -> DataLoadingState()
        data.loadState != DataLoadState.READY -> NoResultsState("图鉴尚未加载")
        data.section == DataSection.HEROES -> {
            val results = remember(data.heroes, data.query) {
                data.heroes.filter { hero ->
                    hero.name.contains(data.query, ignoreCase = true) ||
                        hero.code.contains(data.query, ignoreCase = true)
                }
            }
            if (results.isEmpty()) {
                NoResultsState("没有匹配的英雄")
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    items(results, key = E7Hero::code) { hero ->
                        ListItem(
                            headlineContent = { Text(hero.name, fontWeight = FontWeight.SemiBold) },
                            supportingContent = {
                                Text("${hero.attributeLabel()} · ${hero.roleLabel()}")
                            },
                            leadingContent = {
                                RemoteImage(
                                    url = hero.assets.iconUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(MaterialTheme.shapes.medium),
                                )
                            },
                            modifier = Modifier.clickable { onSelectHero(hero.code) },
                        )
                    }
                }
            }
        }

        else -> {
            val results = remember(data.artifacts, data.query) {
                data.artifacts.filter { artifact ->
                    artifact.name.contains(data.query, ignoreCase = true) ||
                        artifact.code.contains(data.query, ignoreCase = true)
                }
            }
            if (results.isEmpty()) {
                NoResultsState("没有匹配的神器")
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    items(results, key = E7Artifact::code) { artifact ->
                        ListItem(
                            headlineContent = {
                                Text(artifact.name, fontWeight = FontWeight.SemiBold)
                            },
                            supportingContent = {
                                Text(artifact.role?.roleLabel().orEmpty())
                            },
                            leadingContent = {
                                RemoteImage(
                                    url = artifact.iconUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(MaterialTheme.shapes.medium),
                                )
                            },
                            modifier = Modifier.clickable { onSelectArtifact(artifact.code) },
                        )
                    }
                }
            }
        }
    }
}

/** M3 [Tabs](https://m3.material.io/components/tabs/overview) switching the catalog section. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionTabs(
    section: DataSection,
    onSectionChanged: (DataSection) -> Unit,
) {
    SecondaryTabRow(
        selectedTabIndex = section.ordinal,
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        DataSection.entries.forEach { entry ->
            Tab(
                selected = section == entry,
                onClick = { onSectionChanged(entry) },
                text = {
                    Text(
                        when (entry) {
                            DataSection.HEROES -> "英雄"
                            DataSection.ARTIFACTS -> "神器"
                        },
                    )
                },
            )
        }
    }
}

/**
 * M3 Expressive
 * [filter chips with carousel](https://m3.material.io/components/chips/guidelines): chips glide
 * in a multi-browse carousel instead of a plain scrolling row.
 */
private val HeroAttributeFilters = listOf("全部", "火焰", "寒气", "自然", "光明", "黑暗")
private val RoleFilters = listOf("全部", "骑士", "战士", "射手", "魔导士", "盗贼", "精灵师")

/**
 * M3 [filter chip that opens a menu](https://m3.material.io/components/chips/guidelines) for more
 * filtering options. The chip shows the active value with a trailing dropdown arrow and opens an
 * anchored menu of options; the active option is marked with a check in the menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterDropdownChip(
    label: String,
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
    iconRes: (String) -> Int?,
) {
    var expanded by remember { mutableStateOf(false) }
    val active = selected != options.first()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        FilterChip(
            selected = active,
            onClick = {},
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                // Reserve enough width so the menu (which matches the anchor width) never wraps
                // an option label, even before any option is checked.
                .widthIn(min = 136.dp),
            label = { Text(if (active) "$label · $selected" else label, maxLines = 1) },
            leadingIcon = if (active) iconRes(selected)?.let { resId ->
                {
                    Image(
                        painter = painterResource(resId),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            } else null,
            trailingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_drop_down),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                    leadingIcon = iconRes(option)?.let { resId ->
                        {
                            Image(
                                painter = painterResource(resId),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    },
                    trailingIcon = if (option == selected) {
                        {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    } else null,
                )
            }
        }
    }
}

@DrawableRes
private fun attributeFilterIconRes(label: String): Int? = when (label) {
    "火焰" -> R.drawable.e7_element_fire
    "寒气" -> R.drawable.e7_element_ice
    "自然" -> R.drawable.e7_element_earth
    "光明" -> R.drawable.e7_element_light
    "黑暗" -> R.drawable.e7_element_dark
    else -> null
}

@DrawableRes
private fun roleFilterIconRes(label: String): Int? = when (label) {
    "骑士" -> R.drawable.e7_class_knight
    "战士" -> R.drawable.e7_class_warrior
    "射手" -> R.drawable.e7_class_ranger
    "魔导士" -> R.drawable.e7_class_mage
    "盗贼" -> R.drawable.e7_class_assassin
    "精灵师" -> R.drawable.e7_class_manauser
    else -> null
}

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
private fun ColumnScope.HeroList(
    data: DataUiState,
    attributeFilter: String,
    roleFilter: String,
    onSelect: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val filtered = remember(data.heroes, data.query, attributeFilter, roleFilter) {
        data.heroes.filter { hero ->
            val matchesQuery = data.query.isBlank() ||
                hero.name.contains(data.query, ignoreCase = true) ||
                hero.code.contains(data.query, ignoreCase = true)
            val matchesAttribute = attributeFilter == "全部" || hero.attributeLabel() == attributeFilter
            val matchesRole = roleFilter == "全部" || hero.role.roleLabel() == roleFilter
            matchesQuery && matchesAttribute && matchesRole
        }
    }
    if (filtered.isEmpty()) {
        NoResultsState("没有匹配的英雄")
        return
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        // Centered-hero carousel: the focused card stays centered at full size with a small
        // card peeking on each side, and each swipe snaps one card into the middle, like
        // dealing through a hand. Neighbors shrink along the keyline path.
        val carouselState = rememberCarouselState(itemCount = { filtered.size })
        HorizontalCenteredHeroCarousel(
            state = carouselState,
            maxItemWidth = 260.dp,
            modifier = Modifier.fillMaxWidth(),
            itemSpacing = 8.dp,
            minSmallItemWidth = 56.dp,
            maxSmallItemWidth = 96.dp,
        ) { index ->
            val hero = filtered[index]
            HeroCard(
                hero = hero,
                onClick = { onSelect(hero.code) },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(HERO_CARD_ASPECT_RATIO)
                    .maskClip(MaterialTheme.shapes.extraLargeIncreased)
                    .graphicsLayer {
                        // Depth/parallax: focused card (focus -> 1) is full size and opaque;
                        // off-center cards recede, dim and sink like cards behind the front one.
                        val focus =
                            if (carouselItemDrawInfo.maxSize > 0f) {
                                (carouselItemDrawInfo.size / carouselItemDrawInfo.maxSize)
                                    .coerceIn(0f, 1f)
                            } else {
                                1f
                            }
                        val eased = FastOutSlowInEasing.transform(focus)
                        scaleY = lerp(0.92f, 1f, eased)
                        alpha = lerp(0.45f, 1f, eased)
                        translationY = lerp(14f, 0f, eased).dp.toPx()
                    },
            )
        }
    }
    Spacer(Modifier.height(12.dp))
}

/**
 * Full-bleed hero card: the character artwork is the cover, with name, stars, class and zodiac
 * overlaid on a scrim gradient, following the M3 filled-card + media pattern.
 */
private const val HERO_CARD_ASPECT_RATIO = 0.72f

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HeroCard(
    hero: E7Hero,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.catalogSharedBounds(
            key = "catalog-hero-${hero.code}",
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
        ),
        shape = MaterialTheme.shapes.extraLargeIncreased,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            RemoteImage(
                url = hero.assets.imageUrl ?: hero.assets.thumbnailUrl ?: hero.assets.iconUrl,
                contentDescription = "${hero.name}立绘",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            // Bottom scrim keeps overlaid text legible over busy artwork.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.45f to Color.Transparent,
                                0.75f to Color.Black.copy(alpha = 0.45f),
                                1f to Color.Black.copy(alpha = 0.8f),
                            ),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
            ) {
                Text(
                    hero.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                hero.rarity?.let {
                    HeroStars(stars = it, iconSize = 16.dp)
                    Spacer(Modifier.height(6.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    heroClassIconRes(hero.role)?.let { resId ->
                        Image(
                            painter = painterResource(resId),
                            contentDescription = hero.role.roleLabel(),
                            modifier = Modifier.size(20.dp),
                            contentScale = ContentScale.Fit,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            hero.role.roleLabel(),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                        )
                    }
                    hero.zodiac?.takeIf(String::isNotBlank)?.let { zodiac ->
                        Spacer(Modifier.width(8.dp))
                        HeroCardChip(zodiac)
                    }
                }
            }
        }
    }
}

/** Translucent chip for metadata overlaid on artwork (zodiac). */
@Composable
private fun HeroCardChip(label: String) {
    Surface(
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.22f),
        contentColor = Color.White,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun ColumnScope.ArtifactList(
    data: DataUiState,
    roleFilter: String,
    onSelect: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val filtered = remember(data.artifacts, data.query, roleFilter) {
        data.artifacts.filter { artifact ->
            val matchesQuery = data.query.isBlank() ||
                artifact.name.contains(data.query, ignoreCase = true) ||
                artifact.code.contains(data.query, ignoreCase = true)
            val matchesRole = roleFilter == "全部" || artifact.role?.roleLabel() == roleFilter
            matchesQuery && matchesRole
        }
    }
    if (filtered.isEmpty()) {
        NoResultsState("没有匹配的神器")
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .clip(MaterialTheme.shapes.medium),
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
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        HeroIdentityIcons(
                            attribute = null,
                            role = artifact.role,
                            rarity = artifact.rarity,
                            iconSize = 18.dp,
                        )
                    }
                },
                leadingContent = {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .catalogSharedBounds(
                                key = "catalog-artifact-${artifact.code}",
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope,
                            )
                            .clip(MaterialTheme.shapes.medium),
                    ) {
                        if (artifact.iconUrl != null) {
                            RemoteImage(
                                url = artifact.iconUrl,
                                contentDescription = artifact.name,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                shape = MaterialTheme.shapes.medium,
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
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                modifier = Modifier.clickable { onSelect(artifact.code) },
            )
        }
    }
    Spacer(Modifier.height(12.dp))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Modifier.catalogSharedBounds(
    key: String,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
): Modifier {
    val spatialSpec = MaterialTheme.motionScheme.slowSpatialSpec<Rect>()
    val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    return with(sharedTransitionScope) {
        this@catalogSharedBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = animatedVisibilityScope,
            enter = fadeIn(animationSpec = effectsSpec),
            exit = fadeOut(animationSpec = effectsSpec),
            boundsTransform = BoundsTransform { _, _ -> spatialSpec },
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        )
    }
}

/** M3E indeterminate loading indicator with the component's built-in shape morph. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColumnScope.DataLoadingState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LoadingIndicator(modifier = Modifier.size(64.dp))
            Text(
                "正在读取图鉴",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColumnScope.DataEmptyState(
    title: String,
    detail: String,
    action: String,
    onAction: () -> Unit,
    error: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        SectionSurface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentPadding = PaddingValues(24.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (error) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                val buttonShapes = ButtonDefaults.shapes(
                    shape = MaterialTheme.shapes.extraLarge,
                    pressedShape = MaterialTheme.shapes.medium,
                )
                if (error) {
                    OutlinedButton(
                        onClick = onAction,
                        shapes = buttonShapes,
                    ) { Text(action) }
                } else {
                    Button(
                        onClick = onAction,
                        shapes = buttonShapes,
                    ) { Text(action) }
                }
            }
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
    onBack: () -> Unit = {},
    onSeasonChanged: (String) -> Unit,
    onTierChanged: (RtaTier) -> Unit,
    onRetryRta: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    var selectedTab by rememberSaveable(hero?.code) { mutableIntStateOf(0) }
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (hero == null) {
                item { DataMissingDetail("英雄数据不可用") }
                return@LazyColumn
            }
            item {
                HeroHeader(
                    hero = hero,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                )
            }
            item {
                PrimaryTabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.background,
                ) {
                    listOf("概览", "成长", "RTA").forEachIndexed { index, label ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(label) },
                        )
                    }
                }
            }
            when (selectedTab) {
                0 -> {
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
                    if (hero.skills.isNotEmpty()) {
                        item { HeroSkills(hero.skills) }
                    }
                }

                1 -> item { HeroGrowthSection(hero) }

                else -> item {
                    HeroRtaSection(
                        state = rta,
                        onSeasonChanged = onSeasonChanged,
                        onTierChanged = onTierChanged,
                        onRetry = onRetryRta,
                    )
                }
            }
        }
        // Floating back affordance replaces the top app bar on this full-screen detail.
        Surface(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .size(40.dp),
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.35f),
            contentColor = Color.White,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = "返回",
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/** Full-bleed artwork header: name, stars, class and zodiac float over the hero portrait. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HeroHeader(
    hero: E7Hero,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
            .catalogSharedBounds(
                key = "catalog-hero-${hero.code}",
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
            )
            .clip(MaterialTheme.shapes.extraLargeIncreased)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        RemoteImage(
            url = hero.assets.imageUrl ?: hero.assets.thumbnailUrl ?: hero.assets.iconUrl,
            contentDescription = "${hero.name}立绘",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.5f to Color.Transparent,
                            0.8f to Color.Black.copy(alpha = 0.5f),
                            1f to Color.Black.copy(alpha = 0.85f),
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(20.dp),
        ) {
            Text(
                hero.name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                heroIdentityOverlay(hero)
            }
            hero.description?.cleanSkillText()?.takeIf(String::isNotBlank)?.let { description ->
                Spacer(Modifier.height(10.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun heroIdentityOverlay(hero: E7Hero): Unit {
    hero.rarity?.let {
        HeroStars(stars = it, iconSize = 18.dp)
        Spacer(Modifier.width(10.dp))
    }
    heroElementIconRes(hero.attribute)?.let { resId ->
        Image(
            painter = painterResource(resId),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.width(8.dp))
    }
    heroClassIconRes(hero.role)?.let { resId ->
        Image(
            painter = painterResource(resId),
            contentDescription = hero.role.roleLabel(),
            modifier = Modifier.size(22.dp),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.width(8.dp))
    }
    hero.zodiac?.takeIf(String::isNotBlank)?.let { HeroCardChip(it) }
}

@Composable
private fun HeroGrowthSection(hero: E7Hero) {
    if (hero.awakenings.isEmpty() && hero.memoryImprint == null) {
        SectionSurface {
            Text("暂无成长资料", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    if (hero.awakenings.isNotEmpty()) {
        SectionTitle("觉醒")
        Spacer(Modifier.height(8.dp))
        hero.awakenings.sortedBy(E7HeroAwakening::rank).forEach { awakening ->
            SectionSurface {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HeroStars(stars = awakening.rank, iconSize = 18.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${awakening.rank} 星觉醒",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (awakening.stats.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        awakening.stats.chunked(2).forEach { stats ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(20.dp),
                            ) {
                                stats.forEach { stat ->
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        growthStatIconRes(stat.label)?.let { iconRes ->
                                            Image(
                                                painter = painterResource(iconRes),
                                                contentDescription = stat.label.growthLabel(),
                                                modifier = Modifier.size(18.dp),
                                                contentScale = ContentScale.Fit,
                                            )
                                        }
                                        Text(
                                            "${stat.label.growthLabel()} ${stat.value}",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                                if (stats.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                val before = awakening.skillBefore?.cleanSkillText()?.takeIf(String::isNotBlank)
                val after = awakening.skillAfter?.cleanSkillText()?.takeIf(String::isNotBlank)
                if (before != null || after != null) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    before?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("觉醒前", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(3.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    after?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "觉醒后",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    hero.memoryImprint?.let { imprint ->
        Spacer(Modifier.height(12.dp))
        SectionTitle("刻印")
        Spacer(Modifier.height(8.dp))
        ImprintSection("阵型刻印", imprint.release)
        Spacer(Modifier.height(8.dp))
        ImprintSection("自身刻印", imprint.concentration)
    }
}

@Composable
private fun ImprintSection(title: String, section: E7ImprintSection?) {
    if (section == null || section.grades.isEmpty()) return
    SectionSurface {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val gradeColumns = if (maxWidth >= 520.dp) 3 else 2
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val position = section.position?.takeIf(String::isNotBlank)
                val positionIcon = position?.let(::imprintPositionIconRes)
                if (positionIcon != null) {
                    Image(
                        painter = painterResource(positionIcon),
                        contentDescription = imprintPositionDescription(position),
                        modifier = Modifier.size(68.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    section.grades.chunked(gradeColumns).forEach { grades ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            grades.forEach { grade ->
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    imprintRankIconRes(grade.rank)?.let { rankIcon ->
                                        Image(
                                            painter = painterResource(rankIcon),
                                            contentDescription = grade.rank,
                                            modifier = Modifier
                                                .width(42.dp)
                                                .height(25.dp),
                                            contentScale = ContentScale.Fit,
                                        )
                                    } ?: Text(
                                        grade.rank,
                                        modifier = Modifier.width(42.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        grade.value.imprintValueLabel(),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            repeat(gradeColumns - grades.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun String.growthLabel(): String = when (lowercase()) {
    "attack" -> "攻击"
    "health" -> "生命"
    "defense" -> "防御"
    "speed" -> "速度"
    "critical hit rate", "critical hit chance" -> "暴击"
    "critical hit damage" -> "暴伤"
    "effectiveness" -> "效果命中"
    "effect resistance" -> "效果抗性"
    else -> this
}

private fun String.imprintValueLabel(): String = when {
    startsWith("Effectiveness", ignoreCase = true) -> replaceFirst("Effectiveness", "效果命中", ignoreCase = true)
    startsWith("Effect Resistance", ignoreCase = true) -> replaceFirst("Effect Resistance", "效果抗性", ignoreCase = true)
    startsWith("Critical Hit Chance", ignoreCase = true) -> replaceFirst("Critical Hit Chance", "暴击", ignoreCase = true)
    startsWith("Critical Hit Damage", ignoreCase = true) -> replaceFirst("Critical Hit Damage", "暴伤", ignoreCase = true)
    startsWith("Attack", ignoreCase = true) -> replaceFirst("Attack", "攻击", ignoreCase = true)
    startsWith("Health", ignoreCase = true) -> replaceFirst("Health", "生命", ignoreCase = true)
    startsWith("Defense", ignoreCase = true) -> replaceFirst("Defense", "防御", ignoreCase = true)
    startsWith("Speed", ignoreCase = true) -> replaceFirst("Speed", "速度", ignoreCase = true)
    else -> this
}

@Composable
private fun HeroRtaSection(
    state: HeroRtaUiState,
    onSeasonChanged: (String) -> Unit,
    onTierChanged: (RtaTier) -> Unit,
    onRetry: () -> Unit,
) {
    if (state.seasons.isNotEmpty()) {
        val selectedSeasonIndex = state.seasons
            .indexOfFirst { it.code == state.selectedSeasonCode }
            .coerceAtLeast(0)
        SecondaryTabRow(
            selectedTabIndex = selectedSeasonIndex,
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            state.seasons.forEachIndexed { index, season ->
                Tab(
                    selected = selectedSeasonIndex == index,
                    onClick = { onSeasonChanged(season.code) },
                    text = {
                        Text(
                            if (season.isCurrent) "${season.name} · 当前" else season.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
        state.seasons.getOrNull(selectedSeasonIndex)?.let { season ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${season.startDate.rtaDate()} 至 ${season.endDate.rtaDate()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
    SecondaryTabRow(
        selectedTabIndex = RtaTier.entries.indexOf(state.selectedTier).coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        RtaTier.entries.forEach { tier ->
            Tab(
                selected = state.selectedTier == tier,
                onClick = { onTierChanged(tier) },
                text = { Text(tier.label, maxLines = 1) },
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
private fun HeroSkills(skills: List<E7HeroSkill>) {
    val sorted = skills.sortedBy(E7HeroSkill::slot)
    val baseSkills = sorted.filter { it.slot in 1..3 }
    val transformedSkills = sorted.filter { it.slot >= 4 }

    SectionTitle("技能")
    Spacer(Modifier.height(8.dp))
    baseSkills.forEach { skill -> HeroSkillCard(skill) }

    if (transformedSkills.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        SectionTitle("变身后")
        Spacer(Modifier.height(8.dp))
        transformedSkills.forEach { skill -> HeroSkillCard(skill) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeroSkillCard(skill: E7HeroSkill) {
    SectionSurface {
        Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RemoteImage(
                    url = skill.iconUrl,
                    contentDescription = "${skill.name}图标",
                    modifier = Modifier.size(56.dp),
                    contentScale = ContentScale.Fit,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = skill.name.ifBlank { "技能 ${skill.slot}" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        if (skill.isPassive) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ) {
                                Text(
                                    "被动",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                    val meta = listOfNotNull(
                        skill.cooldown?.takeIf { it > 0 }?.let { "冷却 $it 回合" },
                        skill.soulGain?.takeIf { it > 0 }?.let { "获得灵魂 $it" },
                        skill.soulRequirement?.takeIf { it > 0 }?.let { "灵魂燃烧 $it" },
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val statusEffects = skill.buffs + skill.debuffs
                if (statusEffects.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        statusEffects.forEach { effect ->
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                                tooltip = {
                                    PlainTooltip {
                                        Column {
                                            Text(effect.label, fontWeight = FontWeight.SemiBold)
                                            effect.description?.takeIf(String::isNotBlank)?.let { desc ->
                                                Text(desc, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                },
                                state = rememberTooltipState(),
                            ) {
                                RemoteImage(
                                    url = effect.iconUrl,
                                    contentDescription = effect.label,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(MaterialTheme.shapes.small),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }
                    }
                }
            }
            skill.description?.cleanSkillText()?.takeIf(String::isNotBlank)?.let { description ->
                Spacer(Modifier.height(10.dp))
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
            skill.soulDescription?.cleanSkillText()?.takeIf(String::isNotBlank)?.let { description ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "灵魂燃烧：$description",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            skill.enhancedDescription?.cleanSkillText()?.takeIf(String::isNotBlank)?.let { description ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "觉醒后：$description",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (skill.enhancements.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                skill.enhancements.forEach { enhancement ->
                    Text(
                        "· ${enhancement.cleanSkillText()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val scaling = listOfNotNull(
                skill.attackRate?.takeIf { it > 0.0 }?.let { "攻击倍率 ${it.formatMultiplier()}" },
                skill.pow?.takeIf { it > 0.0 }?.let { "POW ${it.formatMultiplier()}" },
            ).joinToString(" · ")
            if (scaling.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    scaling,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
    }
    Spacer(Modifier.height(8.dp))
}

private fun String.cleanSkillText(): String = replace("\\\\n", "\n")
    .replace("\\n", "\n")
    .replace(Regex("<[^>]+>"), "")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .trim()

private fun Double.formatMultiplier(): String = "%.2f".format(Locale.US, this)

@Composable
private fun HeroStats(stats: E7HeroStats?) {
    if (stats == null) {
        Text("暂无 Fribbels 属性数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    HeroStatMetricRow("攻击", "Attack", stats.attack.displayOrDash())
    HeroStatMetricRow("生命", "Health", stats.health.displayOrDash())
    HeroStatMetricRow("防御", "Defense", stats.defense.displayOrDash())
    HeroStatMetricRow("速度", "Speed", stats.speed.displayOrDash())
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    HeroStatMetricRow("暴击", "CriticalHitChancePercent", stats.criticalChance.percentOrDash())
    HeroStatMetricRow("暴伤", "CriticalHitDamagePercent", stats.criticalDamage.percentOrDash())
    HeroStatMetricRow("效果命中", "EffectivenessPercent", stats.effectiveness.percentOrDash())
    HeroStatMetricRow("效果抗性", "EffectResistancePercent", stats.effectResistance.percentOrDash())
    HeroStatMetricRow("战斗力", null, stats.combatPower.displayOrDash())
}

@Composable
private fun HeroStatMetricRow(
    label: String,
    statType: String?,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        statType?.let { type ->
            gearStatIconRes(type)?.let { iconRes ->
                Image(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
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
    "fire" -> "火焰"
    "ice", "water" -> "寒气"
    "earth", "wind" -> "自然"
    "light" -> "光明"
    "dark" -> "黑暗"
    else -> attribute
}

private fun E7Hero.roleLabel(): String = role.roleLabel()

private fun String.roleLabel(): String = when (lowercase()) {
    "knight" -> "骑士"
    "warrior" -> "战士"
    "ranger" -> "射手"
    "mage" -> "魔导士"
    "assassin" -> "盗贼"
    "manauser", "soul_weaver", "soulweaver" -> "精灵师"
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
