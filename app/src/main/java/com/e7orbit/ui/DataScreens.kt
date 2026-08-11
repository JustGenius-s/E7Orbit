package com.e7orbit.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalCenteredHeroCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.e7orbit.R
import com.e7orbit.data.E7Artifact
import com.e7orbit.data.E7Hero
import com.e7orbit.ui.theme.OrbitPolygonShapes
import com.e7orbit.ui.theme.asShape
import com.e7orbit.ui.theme.elementColorsFor
import kotlinx.coroutines.launch


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
                        accentAttribute = attributeLabelToKey(selectedAttribute),
                    )
                }
                FilterDropdownChip(
                    label = "职业",
                    options = RoleFilters,
                    selected = selectedRole,
                    onSelected = { selectedRole = it },
                    iconRes = ::roleFilterIconRes,
                    accentAttribute = null,
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FilterDropdownChip(
    label: String,
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
    iconRes: (String) -> Int?,
    accentAttribute: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val active = selected != options.first()

    // 激活态：属性筛选按选中元素着色。形状保持 chip 的全圆角语义，
    // 多边形只留给元素徽标/卡片光晕这类图形元素（形状一致性）。
    val accent = accentAttribute?.let { elementColorsFor(it) }?.first
    val chipShape = MaterialTheme.shapes.small
    val chipColors = if (active && accent != null) {
        FilterChipDefaults.filterChipColors(
            selectedContainerColor = accent.copy(alpha = 0.18f),
            selectedLabelColor = accent.copy(alpha = 0.95f).darken(),
            selectedLeadingIconColor = accent,
            selectedTrailingIconColor = accent,
        )
    } else {
        FilterChipDefaults.filterChipColors()
    }

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
            shape = chipShape,
            colors = chipColors,
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

/** 中文属性标签 → 元素键，用于给筛选 chip 着色。 */
private fun attributeLabelToKey(label: String): String? = when (label) {
    "火焰" -> "fire"
    "寒气" -> "ice"
    "自然" -> "earth"
    "光明" -> "light"
    "黑暗" -> "dark"
    else -> null
}

/** 让元素主色在浅色 chip 底上更深一档，保证文字可读（WCAG）。 */
private fun Color.darken(factor: Float = 0.62f): Color = Color(
    red = red * factor,
    green = green * factor,
    blue = blue * factor,
    alpha = alpha,
)

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
private const val HERO_CARD_ASPECT_RATIO = 3f / 4f

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
        Box(modifier = Modifier.fillMaxSize()) {
            // E7 圣约卡面背景。
            HeroCardBackdrop()
            RemoteImage(
                url = hero.assets.imageUrl ?: hero.assets.thumbnailUrl ?: hero.assets.iconUrl,
                contentDescription = "${hero.name}立绘",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            // 中性深色 scrim 压底，保证文字可读。
            HeroCardScrim()
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
                    // 属性图标放职业左边。
                    heroElementIconRes(hero.attribute)?.let { elementRes ->
                        Image(
                            painter = painterResource(elementRes),
                            contentDescription = hero.attributeLabel(),
                            modifier = Modifier.size(20.dp),
                            contentScale = ContentScale.Fit,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
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

/** Translucent chip for metadata overlaid on artwork (zodiac). 干净全圆角。 */
@Composable
internal fun HeroCardChip(label: String) {
    Surface(
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.22f),
        contentColor = Color.White,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
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
internal fun Modifier.catalogSharedBounds(
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
