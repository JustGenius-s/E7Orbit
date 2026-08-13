package com.e7orbit.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.e7orbit.R
import com.e7orbit.data.ArtifactWikiDraft
import com.e7orbit.data.E7Artifact
import com.e7orbit.data.toArtifact
import com.e7orbit.data.toWikiDraft
import com.e7orbit.ui.theme.OrbitArtifactHighlight


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ArtifactDetailScreen(
    artifact: E7Artifact?,
    modifier: Modifier = Modifier,
    wikiEditor: WikiEditorUiState,
    onOpenWikiAuth: () -> Unit,
    onSignOutWikiEditor: () -> Unit,
    onSaveWikiArtifact: (E7Artifact) -> Unit,
    onUploadWikiImage: (String, ByteArray, (String?) -> Unit) -> Unit,
    onClearWikiEditorFeedback: () -> Unit,
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
    var editing by rememberSaveable(artifact.code) { mutableStateOf(false) }
    var workingDraft by remember(artifact.code) { mutableStateOf(artifact.toWikiDraft()) }
    var savedDraft by remember(artifact.code) { mutableStateOf<ArtifactWikiDraft?>(null) }
    var validationError by remember(artifact.code) { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val hasChanges = workingDraft != artifact.toWikiDraft()
    val editorError = validationError ?: wikiEditor.errorMessage

    fun exitEditing() {
        if (wikiEditor.saving) return
        workingDraft = savedDraft ?: artifact.toWikiDraft()
        editing = false
    }

    BackHandler(enabled = editing, onBack = ::exitEditing)
    LaunchedEffect(wikiEditor.canEdit) {
        if (!wikiEditor.canEdit) editing = false
    }
    LaunchedEffect(wikiEditor.saveRevision, wikiEditor.savedArtifactCode) {
        if (wikiEditor.savedArtifactCode == artifact.code && wikiEditor.saveRevision > 0L) {
            savedDraft = null
            workingDraft = artifact.toWikiDraft()
            editing = false
        }
    }
    LaunchedEffect(wikiEditor.message) {
        wikiEditor.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            onClearWikiEditorFeedback()
        }
    }

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

    Box(modifier = modifier.fillMaxSize()) {
        if (isLandscape) {
            Column(
                modifier = Modifier
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
                    ArtifactIdentityContent(
                        artifact = artifact,
                        editing = editing,
                        draft = workingDraft,
                        onDraftChange = { workingDraft = it },
                        modifier = Modifier.weight(1f),
                        landscape = true,
                    )
                }
                if (editing && editorError != null) {
                    Spacer(Modifier.height(10.dp))
                    WikiEditorError(editorError)
                }
                Spacer(Modifier.height(6.dp))
                ArtifactDetailLevels(
                    artifact = artifact,
                    modifier = Modifier.weight(1f),
                    editing = editing,
                    draft = workingDraft,
                    onDraftChange = { workingDraft = it },
                    onUploadWikiImage = onUploadWikiImage,
                )
            }
        } else {
            Column(
                modifier = Modifier
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
                    ArtifactIdentityContent(
                        artifact = artifact,
                        editing = editing,
                        draft = workingDraft,
                        onDraftChange = { workingDraft = it },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (editing && editorError != null) {
                    Spacer(Modifier.height(10.dp))
                    WikiEditorError(editorError)
                }
                Spacer(Modifier.height(6.dp))
                ArtifactDetailLevels(
                    artifact = artifact,
                    modifier = Modifier.weight(1f),
                    editing = editing,
                    draft = workingDraft,
                    onDraftChange = { workingDraft = it },
                    onUploadWikiImage = onUploadWikiImage,
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
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
            canEdit = wikiEditor.canEdit,
            editing = editing,
            saving = wikiEditor.saving,
            canSaveDraft = hasChanges && savedDraft != workingDraft,
            canUpdate = hasChanges,
            onEdit = {
                onClearWikiEditorFeedback()
                validationError = null
                workingDraft = savedDraft ?: artifact.toWikiDraft()
                editing = true
            },
            onExit = ::exitEditing,
            onSaveDraft = { savedDraft = workingDraft },
            onUpdate = {
                val candidate = runCatching { workingDraft.toArtifact(artifact) }
                    .onFailure { error ->
                        validationError = error.message ?: "资料格式有误"
                    }
                    .getOrNull()
                    ?: return@WikiEditingFloatingControls
                validationError = null
                onSaveWikiArtifact(candidate)
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }
}

@Composable
private fun ArtifactIdentityContent(
    artifact: E7Artifact,
    editing: Boolean,
    draft: ArtifactWikiDraft,
    onDraftChange: (ArtifactWikiDraft) -> Unit,
    modifier: Modifier = Modifier,
    landscape: Boolean = false,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (editing) {
            WikiTextField(
                value = draft.name,
                onValueChange = { onDraftChange(draft.copy(name = it)) },
                label = "神器名称",
            )
            EditorFieldPair(
                first = { fieldModifier ->
                    WikiTextField(
                        value = draft.rarity,
                        onValueChange = { onDraftChange(draft.copy(rarity = it)) },
                        label = "稀有度",
                        modifier = fieldModifier,
                        keyboardType = KeyboardType.Number,
                    )
                },
                second = { fieldModifier ->
                    EditorDropdown(
                        value = draft.role,
                        options = RoleEditorOptions,
                        label = "适用职业",
                        modifier = fieldModifier,
                        onValueChange = { onDraftChange(draft.copy(role = it)) },
                    )
                },
            )
            WikiTextField(
                value = draft.lore,
                onValueChange = { onDraftChange(draft.copy(lore = it)) },
                label = "背景故事",
                singleLine = false,
                minLines = if (landscape) 3 else 2,
            )
        } else {
            Text(
                artifact.name,
                modifier = Modifier.fillMaxWidth(),
                style = if (landscape) {
                    MaterialTheme.typography.headlineMedium
                } else {
                    MaterialTheme.typography.titleLarge
                },
                fontWeight = if (landscape) FontWeight.ExtraBold else FontWeight.Bold,
            )
            artifact.lore?.takeIf(String::isNotBlank)?.let { lore ->
                Text(
                    lore,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ArtifactDetailLevels(
    artifact: E7Artifact,
    modifier: Modifier = Modifier,
    editing: Boolean = false,
    draft: ArtifactWikiDraft = artifact.toWikiDraft(),
    onDraftChange: (ArtifactWikiDraft) -> Unit = {},
    onUploadWikiImage: (String, ByteArray, (String?) -> Unit) -> Unit = { _, _, _ -> },
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = if (editing) 104.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(if (editing) 14.dp else 0.dp),
    ) {
        if (editing) {
            item {
                WikiEditorSection(
                    title = "初始等级",
                    supportingText = "初始面板与神器效果",
                ) {
                    EditorNumberPair(
                        firstValue = draft.baseAttack,
                        firstLabel = "攻击力",
                        onFirstChange = { onDraftChange(draft.copy(baseAttack = it)) },
                        secondValue = draft.baseHealth,
                        secondLabel = "生命值",
                        onSecondChange = { onDraftChange(draft.copy(baseHealth = it)) },
                    )
                    WikiTextField(
                        value = draft.description,
                        onValueChange = { onDraftChange(draft.copy(description = it)) },
                        label = "初始效果",
                        singleLine = false,
                        minLines = 4,
                    )
                }
            }
            item {
                WikiEditorSection(
                    title = "满级",
                    supportingText = "满级面板与最大效果",
                ) {
                    EditorNumberPair(
                        firstValue = draft.attack,
                        firstLabel = "攻击力",
                        onFirstChange = { onDraftChange(draft.copy(attack = it)) },
                        secondValue = draft.health,
                        secondLabel = "生命值",
                        onSecondChange = { onDraftChange(draft.copy(health = it)) },
                    )
                    WikiTextField(
                        value = draft.maxDescription,
                        onValueChange = { onDraftChange(draft.copy(maxDescription = it)) },
                        label = "最大效果",
                        singleLine = false,
                        minLines = 4,
                    )
                }
            }
            item {
                WikiEditorSection(
                    title = "图像资源",
                    supportingText = "卡面与图标地址",
                ) {
                    WikiImageField(
                        value = draft.imageUrl,
                        onValueChange = { onDraftChange(draft.copy(imageUrl = it)) },
                        label = "卡面 URL",
                        uploadPath = "artifacts/${artifact.code}/image.png",
                        onUpload = onUploadWikiImage,
                    )
                    WikiImageField(
                        value = draft.iconUrl,
                        onValueChange = { onDraftChange(draft.copy(iconUrl = it)) },
                        label = "图标 URL",
                        uploadPath = "artifacts/${artifact.code}/icon.png",
                        onUpload = onUploadWikiImage,
                    )
                }
            }
        } else {
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ArtifactLevelSection(
                    title = "初始等级",
                    description = artifact.description,
                    comparisonDescription = artifact.maxDescription,
                    attack = artifact.baseAttack,
                    health = artifact.baseHealth,
                )
            }
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ArtifactLevelSection(
                    title = "满级",
                    description = artifact.maxDescription,
                    comparisonDescription = artifact.description,
                    attack = artifact.attack,
                    health = artifact.health,
                )
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
