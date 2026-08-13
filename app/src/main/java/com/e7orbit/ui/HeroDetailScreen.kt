package com.e7orbit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.e7orbit.R
import com.e7orbit.data.E7Hero
import com.e7orbit.data.E7HeroAwakening
import com.e7orbit.data.E7HeroExclusiveEquipment
import com.e7orbit.data.E7HeroSkill
import com.e7orbit.data.E7HeroStats
import com.e7orbit.data.E7ImprintSection
import com.e7orbit.data.E7StatusEffect
import com.e7orbit.data.HeroRtaAnalysis
import com.e7orbit.data.HeroWikiDraft
import com.e7orbit.data.RtaTier
import com.e7orbit.data.emptyHeroSkillWikiDraft
import com.e7orbit.data.toHero
import com.e7orbit.data.toWikiDraft
import java.util.Locale


@Composable
internal fun HeroDetailScreen(
    hero: E7Hero?,
    rta: HeroRtaUiState,
    buffStatusEffects: List<E7StatusEffect>,
    debuffStatusEffects: List<E7StatusEffect>,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onSeasonChanged: (String) -> Unit,
    onTierChanged: (RtaTier) -> Unit,
    onRetryRta: () -> Unit,
    wikiEditor: WikiEditorUiState,
    onOpenWikiAuth: () -> Unit,
    onSignOutWikiEditor: () -> Unit,
    onSaveWikiHero: (E7Hero) -> Unit,
    onUploadWikiImage: (String, ByteArray, (String?) -> Unit) -> Unit,
    onClearWikiEditorFeedback: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    var selectedTab by rememberSaveable(hero?.code) { mutableIntStateOf(0) }
    var editing by rememberSaveable(hero?.code) { mutableStateOf(false) }
    var workingDraft by remember(hero?.code) { mutableStateOf(hero?.toWikiDraft()) }
    var savedDraft by remember(hero?.code) { mutableStateOf<HeroWikiDraft?>(null) }
    var validationError by remember(hero?.code) { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val hasChanges = workingDraft != null && workingDraft != hero?.toWikiDraft()
    val editorError = validationError ?: wikiEditor.errorMessage

    fun exitEditing() {
        if (wikiEditor.saving) return
        workingDraft = savedDraft ?: hero?.toWikiDraft()
        editing = false
    }

    BackHandler(enabled = editing, onBack = ::exitEditing)
    LaunchedEffect(wikiEditor.canEdit) {
        if (!wikiEditor.canEdit) editing = false
    }
    LaunchedEffect(wikiEditor.saveRevision, wikiEditor.savedHeroCode) {
        if (wikiEditor.savedHeroCode == hero?.code && wikiEditor.saveRevision > 0L) {
            savedDraft = null
            workingDraft = hero?.toWikiDraft()
            editing = false
        }
    }
    LaunchedEffect(wikiEditor.message) {
        wikiEditor.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            onClearWikiEditorFeedback()
        }
    }

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
            if (editing && editorError != null) {
                item { WikiEditorError(editorError) }
            }
            item {
                HeroHeader(
                    hero = hero,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    editing = editing,
                    draft = workingDraft,
                    onDraftChange = { workingDraft = it },
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
                            enabled = !editing || index == 0,
                            text = { Text(label) },
                        )
                    }
                }
            }
            when (selectedTab) {
                0 -> {
                    if (editing && workingDraft != null) {
                        val draft = requireNotNull(workingDraft)
                        item {
                            WikiEditorSection(
                                title = "六星满觉属性",
                                supportingText = "只填写游戏面板中的基础数值",
                            ) {
                                HeroStatsEditor(
                                    draft = draft,
                                    onChange = { workingDraft = it },
                                )
                            }
                        }
                        item {
                            WikiEditorSection(
                                title = "专属装备",
                                supportingText = "装备属性及三个强化选项",
                            ) {
                                ExclusiveEquipmentEditor(
                                    equipment = draft.exclusiveEquipment,
                                    heroCode = hero.code,
                                    onUpload = onUploadWikiImage,
                                    onChange = {
                                        workingDraft = draft.copy(exclusiveEquipment = it)
                                    },
                                )
                            }
                        }
                        item {
                            WikiEditorSection(
                                title = "技能",
                                supportingText = "最多五个技能，栏位不能重复",
                                action = {
                                    OutlinedButton(
                                        onClick = {
                                            val usedSlots = draft.skills
                                                .mapNotNull { it.slot.toIntOrNull() }
                                                .toSet()
                                            val slot = (1..5).firstOrNull { it !in usedSlots }
                                                ?: return@OutlinedButton
                                            workingDraft = draft.copy(
                                                skills = draft.skills + emptyHeroSkillWikiDraft(slot),
                                            )
                                        },
                                        enabled = draft.skills.size < 5,
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_add),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("添加")
                                    }
                                },
                            ) {
                                if (draft.skills.isEmpty()) {
                                    Text(
                                        "暂无技能",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        itemsIndexed(
                            items = draft.skills,
                            key = { index, _ -> index },
                        ) { index, skill ->
                            HeroSkillEditor(
                                index = index,
                                skill = skill,
                                heroCode = hero.code,
                                buffStatusEffects = buffStatusEffects,
                                debuffStatusEffects = debuffStatusEffects,
                                onUpload = onUploadWikiImage,
                                onChange = { updated ->
                                    workingDraft = draft.copy(
                                        skills = draft.skills.toMutableList().apply {
                                            set(index, updated)
                                        },
                                    )
                                },
                                onDelete = {
                                    workingDraft = draft.copy(
                                        skills = draft.skills.filterIndexed { i, _ -> i != index },
                                    )
                                },
                            )
                        }
                        item {
                            WikiEditorSection(
                                title = "图像资源",
                                supportingText = "头像、缩略图与立绘地址",
                            ) {
                                WikiImageField(
                                    value = draft.iconUrl,
                                    onValueChange = { workingDraft = draft.copy(iconUrl = it) },
                                    label = "头像 URL",
                                    uploadPath = "heroes/${hero.code}/icon.png",
                                    onUpload = onUploadWikiImage,
                                )
                                WikiImageField(
                                    value = draft.thumbnailUrl,
                                    onValueChange = { workingDraft = draft.copy(thumbnailUrl = it) },
                                    label = "缩略图 URL",
                                    uploadPath = "heroes/${hero.code}/thumbnail.png",
                                    onUpload = onUploadWikiImage,
                                )
                                WikiImageField(
                                    value = draft.imageUrl,
                                    onValueChange = { workingDraft = draft.copy(imageUrl = it) },
                                    label = "立绘 URL",
                                    uploadPath = "heroes/${hero.code}/art.webp",
                                    onUpload = onUploadWikiImage,
                                )
                            }
                        }
                        item { Spacer(Modifier.height(88.dp)) }
                    } else {
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
                        hero.exclusiveEquipment?.let { equipment ->
                            item { HeroExclusiveEquipment(equipment, hero.skills) }
                        }
                        if (hero.skills.isNotEmpty()) {
                            item { HeroSkills(hero.skills) }
                        }
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
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
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
        WikiHeroManagementMenu(
                state = wikiEditor,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                onSignIn = {
                    onClearWikiEditorFeedback()
                    onOpenWikiAuth()
                },
                onSignOut = onSignOutWikiEditor,
            )
        WikiEditingFloatingControls(
            canEdit = wikiEditor.canEdit && hero != null,
            editing = editing,
            saving = wikiEditor.saving,
            canSaveDraft = hasChanges && savedDraft != workingDraft,
            canUpdate = hasChanges,
            onEdit = {
                val currentHero = hero ?: return@WikiEditingFloatingControls
                onClearWikiEditorFeedback()
                validationError = null
                selectedTab = 0
                workingDraft = savedDraft ?: currentHero.toWikiDraft()
                editing = true
            },
            onExit = ::exitEditing,
            onSaveDraft = { savedDraft = workingDraft },
            onUpdate = {
                val currentHero = hero ?: return@WikiEditingFloatingControls
                val candidate = runCatching {
                    requireNotNull(workingDraft).toHero(currentHero)
                }.onFailure { error ->
                    validationError = error.message ?: "资料格式有误"
                }.getOrNull() ?: return@WikiEditingFloatingControls
                validationError = null
                onSaveWikiHero(candidate)
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }
}

/** Full-bleed artwork header: name, stars, class and zodiac float over the hero portrait. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HeroHeader(
    hero: E7Hero,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    editing: Boolean = false,
    draft: HeroWikiDraft? = null,
    onDraftChange: (HeroWikiDraft) -> Unit = {},
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
            .clip(MaterialTheme.shapes.extraLargeIncreased),
    ) {
        // 与 Wiki 卡片使用同一套元素光辉背景和 scrim，保证共享元素过渡连贯。
        HeroCardBackdrop()
        RemoteImage(
            url = hero.assets.imageUrl ?: hero.assets.thumbnailUrl ?: hero.assets.iconUrl,
            contentDescription = "${hero.name}立绘",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        HeroCardScrim()
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(20.dp),
        ) {
            if (editing && draft != null) {
                WikiTextField(
                    value = draft.name,
                    onValueChange = { onDraftChange(draft.copy(name = it)) },
                    label = "英雄名称",
                    textColor = Color.White,
                    containerColor = Color.Black.copy(alpha = 0.24f),
                )
            } else {
                Text(
                    hero.name,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (editing && draft != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WikiTextField(
                        value = draft.rarity,
                        onValueChange = { onDraftChange(draft.copy(rarity = it)) },
                        label = "稀有度",
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Number,
                        textColor = Color.White,
                        containerColor = Color.Black.copy(alpha = 0.24f),
                    )
                    WikiTextField(
                        value = draft.zodiac,
                        onValueChange = { onDraftChange(draft.copy(zodiac = it)) },
                        label = "星座",
                        modifier = Modifier.weight(1f),
                        textColor = Color.White,
                        containerColor = Color.Black.copy(alpha = 0.24f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EditorDropdown(
                        value = draft.attribute,
                        options = AttributeEditorOptions,
                        label = "属性",
                        modifier = Modifier.weight(1f),
                        textColor = Color.White,
                        containerColor = Color.Black.copy(alpha = 0.24f),
                        onValueChange = { onDraftChange(draft.copy(attribute = it)) },
                    )
                    EditorDropdown(
                        value = draft.role,
                        options = RoleEditorOptions,
                        label = "职业",
                        modifier = Modifier.weight(1f),
                        textColor = Color.White,
                        containerColor = Color.Black.copy(alpha = 0.24f),
                        onValueChange = { onDraftChange(draft.copy(role = it)) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                WikiTextField(
                    value = draft.description,
                    onValueChange = { onDraftChange(draft.copy(description = it)) },
                    label = "英雄简介",
                    singleLine = false,
                    minLines = 2,
                    textColor = Color.White,
                    containerColor = Color.Black.copy(alpha = 0.24f),
                )
            } else {
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
            contentDescription = hero.attributeLabel(),
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
private fun HeroExclusiveEquipment(
    equipment: E7HeroExclusiveEquipment,
    skills: List<E7HeroSkill>,
) {
    SectionTitle("专属装备")
    Spacer(Modifier.height(8.dp))
    SectionSurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RemoteImage(
                url = equipment.iconUrl,
                contentDescription = "${equipment.name}图标",
                modifier = Modifier.size(72.dp),
                contentScale = ContentScale.Fit,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = equipment.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(5.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    exclusiveEquipmentStatIconRes(
                        statType = equipment.statType,
                        isPercent = equipment.statPercent,
                    )?.let { iconRes ->
                        Image(
                            painter = painterResource(iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Text(
                        text = equipment.statRangeLabel(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                equipment.description?.cleanSkillText()?.takeIf(String::isNotBlank)?.let { description ->
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (equipment.enhancements.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 14.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                equipment.enhancements.sortedBy { it.option }.forEach { enhancement ->
                    val skill = skills.firstOrNull { it.slot == enhancement.skillSlot }
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RemoteImage(
                            url = skill?.iconUrl,
                            contentDescription = "强化 ${enhancement.option} 技能图标",
                            modifier = Modifier.size(44.dp),
                            contentScale = ContentScale.Fit,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            skill?.name?.takeIf(String::isNotBlank)?.let { skillName ->
                                Text(
                                    text = skillName,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(
                                text = enhancement.description.cleanSkillText(),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun E7HeroExclusiveEquipment.statRangeLabel(): String {
    val suffix = if (statPercent) "%" else ""
    return "${statMin.exclusiveStatValue()}$suffix - ${statMax.exclusiveStatValue()}$suffix"
}

private fun Double.exclusiveStatValue(): String =
    if (this % 1.0 == 0.0) toInt().toString() else toString()

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
                Text(description.withSkillValueHighlight(), style = MaterialTheme.typography.bodyMedium)
            }
            skill.soulDescription?.cleanSkillText()?.takeIf(String::isNotBlank)?.let { description ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SoulBurnIcons(skill.soulRequirement)
                    Text(
                        "灵魂燃烧：$description".withSkillValueHighlight(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            skill.enhancedDescription?.cleanSkillText()?.takeIf(String::isNotBlank)?.let { description ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "觉醒后：$description".withSkillValueHighlight(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (skill.enhancements.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                skill.enhancements.forEach { enhancement ->
                    Text(
                        "· ${enhancement.cleanSkillText()}".withSkillValueHighlight(),
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

// 10 魂 = 1 个图标，不足 10 的部分按 1 个算（最多 3 个，防止异常数据刷屏）。
// 图标资源还没找到可用 CDN，URL 为空时 RemoteImage 会自动隐藏，拿到地址后填 SOUL_ICON_URL 即可。
private val SOUL_ICON_URL: String? = null
private const val SOULS_PER_ICON = 10
private const val MAX_SOUL_ICONS = 3

@Composable
private fun SoulBurnIcons(soulRequirement: Int?) {
    val requirement = soulRequirement ?: return
    if (requirement <= 0 || SOUL_ICON_URL == null) return
    val count = ((requirement + SOULS_PER_ICON - 1) / SOULS_PER_ICON).coerceIn(1, MAX_SOUL_ICONS)
    repeat(count) {
        RemoteImage(
            url = SOUL_ICON_URL,
            contentDescription = "消耗灵魂",
            modifier = Modifier.size(16.dp),
            contentScale = ContentScale.Fit,
        )
    }
    Spacer(Modifier.width(6.dp))
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
