package com.e7orbit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.e7orbit.R
import com.e7orbit.data.E7Hero
import com.e7orbit.data.ExclusiveEquipmentWikiDraft
import com.e7orbit.data.HeroSkillWikiDraft
import com.e7orbit.data.HeroWikiDraft
import com.e7orbit.data.emptyExclusiveEquipmentWikiDraft
import com.e7orbit.data.emptyHeroSkillWikiDraft
import com.e7orbit.data.toHero
import com.e7orbit.data.toWikiDraft

private data class EditorOption(
    val value: String,
    val label: String,
)

private val AttributeEditorOptions = listOf(
    EditorOption("fire", "火焰"),
    EditorOption("ice", "寒气"),
    EditorOption("earth", "自然"),
    EditorOption("light", "光明"),
    EditorOption("dark", "黑暗"),
)

private val RoleEditorOptions = listOf(
    EditorOption("knight", "骑士"),
    EditorOption("warrior", "战士"),
    EditorOption("ranger", "射手"),
    EditorOption("mage", "魔导士"),
    EditorOption("assassin", "盗贼"),
    EditorOption("manauser", "精灵师"),
)

private val ExclusiveStatEditorOptions = listOf(
    EditorOption("attack", "攻击力"),
    EditorOption("health", "生命值"),
    EditorOption("defense", "防御力"),
    EditorOption("speed", "速度"),
    EditorOption("critical_chance", "暴击率"),
    EditorOption("critical_damage", "暴击伤害"),
    EditorOption("effectiveness", "效果命中"),
    EditorOption("effect_resistance", "效果抗性"),
)

@Composable
internal fun WikiHeroManagementMenu(
    state: WikiEditorUiState,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.35f),
            contentColor = Color.White,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = "Wiki 管理菜单",
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            when {
                state.canEdit -> {
                    DropdownMenuItem(
                        text = { Text("编辑资料") },
                        onClick = {
                            expanded = false
                            onEdit()
                        },
                    )
                    state.email?.takeIf(String::isNotBlank)?.let { email ->
                        DropdownMenuItem(
                            text = { Text(email) },
                            onClick = {},
                            enabled = false,
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("退出账号") },
                        onClick = {
                            expanded = false
                            onSignOut()
                        },
                        enabled = !state.saving,
                    )
                }

                state.email != null -> {
                    DropdownMenuItem(
                        text = { Text("当前账号无 Wiki 编辑权限") },
                        onClick = {},
                        enabled = false,
                    )
                    DropdownMenuItem(
                        text = { Text("退出账号") },
                        onClick = {
                            expanded = false
                            onSignOut()
                        },
                    )
                }

                state.configured -> DropdownMenuItem(
                    text = { Text("账号登录 / 注册") },
                    onClick = {
                        expanded = false
                        onSignIn()
                    },
                )

                else -> DropdownMenuItem(
                    text = { Text("Supabase 未配置") },
                    onClick = {},
                    enabled = false,
                )
            }
        }
    }
}

private enum class WikiAuthDialogMode {
    SIGN_IN,
    REGISTER,
    FORGOT_PASSWORD,
    RESET_PASSWORD,
}

@Composable
internal fun WikiEditorAuthDialog(
    state: WikiEditorUiState,
    onDismiss: () -> Unit,
    onSignIn: (String, String) -> Unit,
    onRegister: (String, String, String) -> Unit,
    onResendConfirmation: (String) -> Unit,
    onSendPasswordReset: (String) -> Unit,
    onUpdatePassword: (String, String) -> Unit,
) {
    var mode by remember(state.passwordRecovery) {
        mutableStateOf(
            if (state.passwordRecovery) {
                WikiAuthDialogMode.RESET_PASSWORD
            } else {
                WikiAuthDialogMode.SIGN_IN
            },
        )
    }
    var email by remember {
        mutableStateOf(state.pendingConfirmationEmail ?: state.email.orEmpty())
    }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val busy = state.authenticating
    val title = when (mode) {
        WikiAuthDialogMode.SIGN_IN -> "账号登录"
        WikiAuthDialogMode.REGISTER -> "注册账号"
        WikiAuthDialogMode.FORGOT_PASSWORD -> "找回密码"
        WikiAuthDialogMode.RESET_PASSWORD -> "设置新密码"
    }
    val canSubmit = !busy && when (mode) {
        WikiAuthDialogMode.SIGN_IN -> email.isNotBlank() && password.isNotEmpty()
        WikiAuthDialogMode.REGISTER ->
            email.isNotBlank() && password.isNotEmpty() && confirmation.isNotEmpty()
        WikiAuthDialogMode.FORGOT_PASSWORD -> email.isNotBlank()
        WikiAuthDialogMode.RESET_PASSWORD -> password.isNotEmpty() && confirmation.isNotEmpty()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (mode) {
                    WikiAuthDialogMode.SIGN_IN,
                    WikiAuthDialogMode.REGISTER,
                    WikiAuthDialogMode.FORGOT_PASSWORD,
                    -> OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("邮箱") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                        enabled = !busy,
                    )

                    WikiAuthDialogMode.RESET_PASSWORD -> {
                        Text(
                            text = "请输入新的登录密码。密码至少需要 8 个字符。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (mode == WikiAuthDialogMode.SIGN_IN ||
                    mode == WikiAuthDialogMode.REGISTER ||
                    mode == WikiAuthDialogMode.RESET_PASSWORD
                ) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(if (mode == WikiAuthDialogMode.RESET_PASSWORD) "新密码" else "密码")
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        enabled = !busy,
                    )
                }
                if (mode == WikiAuthDialogMode.REGISTER ||
                    mode == WikiAuthDialogMode.RESET_PASSWORD
                ) {
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("确认密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        enabled = !busy,
                    )
                }
                state.pendingConfirmationEmail?.let { pendingEmail ->
                    if (mode == WikiAuthDialogMode.SIGN_IN || mode == WikiAuthDialogMode.REGISTER) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "请先确认 $pendingEmail 的邮箱。",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(
                                    onClick = { onResendConfirmation(pendingEmail) },
                                    enabled = !busy,
                                ) {
                                    Text("重新发送确认邮件")
                                }
                            }
                        }
                    }
                }
                state.message?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                state.errorMessage?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (mode == WikiAuthDialogMode.SIGN_IN) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = { mode = WikiAuthDialogMode.REGISTER }, enabled = !busy) {
                            Text("注册账号")
                        }
                        TextButton(
                            onClick = { mode = WikiAuthDialogMode.FORGOT_PASSWORD },
                            enabled = !busy,
                        ) {
                            Text("忘记密码")
                        }
                    }
                } else if (mode != WikiAuthDialogMode.RESET_PASSWORD) {
                    TextButton(
                        onClick = { mode = WikiAuthDialogMode.SIGN_IN },
                        enabled = !busy,
                    ) {
                        Text("返回登录")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (mode) {
                        WikiAuthDialogMode.SIGN_IN -> onSignIn(email, password)
                        WikiAuthDialogMode.REGISTER -> onRegister(email, password, confirmation)
                        WikiAuthDialogMode.FORGOT_PASSWORD -> onSendPasswordReset(email)
                        WikiAuthDialogMode.RESET_PASSWORD -> onUpdatePassword(password, confirmation)
                    }
                },
                enabled = canSubmit,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when (mode) {
                        WikiAuthDialogMode.SIGN_IN -> "登录"
                        WikiAuthDialogMode.REGISTER -> "注册"
                        WikiAuthDialogMode.FORGOT_PASSWORD -> "发送邮件"
                        WikiAuthDialogMode.RESET_PASSWORD -> "更新密码"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !busy,
            ) {
                Text("取消")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WikiHeroEditorScreen(
    hero: E7Hero,
    state: WikiEditorUiState,
    modifier: Modifier = Modifier,
    onCancel: () -> Unit,
    onSave: (E7Hero) -> Unit,
) {
    var draft by remember(hero.code) { mutableStateOf(hero.toWikiDraft()) }
    var validationError by remember(hero.code) { mutableStateOf<String?>(null) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("编辑 Wiki 资料")
                        Text(
                            text = hero.code,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onCancel,
                        enabled = !state.saving,
                    ) {
                        Icon(
                            painter = painterResource(android.R.drawable.ic_menu_close_clear_cancel),
                            contentDescription = "取消编辑",
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            val candidate = runCatching { draft.toHero(hero) }
                                .onFailure { validationError = it.message ?: "资料格式有误" }
                                .getOrNull()
                                ?: return@Button
                            validationError = null
                            onSave(candidate)
                        },
                        enabled = !state.saving,
                    ) {
                        if (state.saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("保存")
                    }
                },
            )
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = contentPadding.calculateTopPadding() + 12.dp,
                end = 16.dp,
                bottom = contentPadding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val error = validationError ?: state.errorMessage
            if (error != null) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item { EditorSectionTitle("基本信息") }
            item {
                BasicInfoEditor(
                    draft = draft,
                    onChange = { draft = it },
                )
            }
            item { EditorDivider() }
            item { EditorSectionTitle("六星满觉基础属性") }
            item {
                HeroStatsEditor(
                    draft = draft,
                    onChange = { draft = it },
                )
            }
            item { EditorDivider() }
            item { EditorSectionTitle("专属装备") }
            item {
                ExclusiveEquipmentEditor(
                    equipment = draft.exclusiveEquipment,
                    onChange = { draft = draft.copy(exclusiveEquipment = it) },
                )
            }
            item { EditorDivider() }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    EditorSectionTitle("技能")
                    OutlinedButton(
                        onClick = {
                            val usedSlots = draft.skills.mapNotNull { it.slot.toIntOrNull() }.toSet()
                            val slot = (1..5).firstOrNull { it !in usedSlots } ?: return@OutlinedButton
                            draft = draft.copy(skills = draft.skills + emptyHeroSkillWikiDraft(slot))
                        },
                        enabled = draft.skills.size < 5,
                    ) {
                        Text("添加技能")
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
                    onChange = { updated ->
                        draft = draft.copy(
                            skills = draft.skills.toMutableList().apply { set(index, updated) },
                        )
                    },
                    onDelete = {
                        draft = draft.copy(skills = draft.skills.filterIndexed { i, _ -> i != index })
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BasicInfoEditor(
    draft: HeroWikiDraft,
    onChange: (HeroWikiDraft) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WikiTextField(
            value = draft.name,
            onValueChange = { onChange(draft.copy(name = it)) },
            label = "英雄名称",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WikiTextField(
                value = draft.rarity,
                onValueChange = { onChange(draft.copy(rarity = it)) },
                label = "稀有度",
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Number,
            )
            WikiTextField(
                value = draft.zodiac,
                onValueChange = { onChange(draft.copy(zodiac = it)) },
                label = "星座",
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorDropdown(
                value = draft.attribute,
                options = AttributeEditorOptions,
                label = "属性",
                modifier = Modifier.weight(1f),
                onValueChange = { onChange(draft.copy(attribute = it)) },
            )
            EditorDropdown(
                value = draft.role,
                options = RoleEditorOptions,
                label = "职业",
                modifier = Modifier.weight(1f),
                onValueChange = { onChange(draft.copy(role = it)) },
            )
        }
        WikiTextField(
            value = draft.description,
            onValueChange = { onChange(draft.copy(description = it)) },
            label = "英雄简介",
            singleLine = false,
            minLines = 3,
        )
        WikiTextField(
            value = draft.iconUrl,
            onValueChange = { onChange(draft.copy(iconUrl = it)) },
            label = "头像 URL",
            keyboardType = KeyboardType.Uri,
        )
        WikiTextField(
            value = draft.thumbnailUrl,
            onValueChange = { onChange(draft.copy(thumbnailUrl = it)) },
            label = "缩略图 URL",
            keyboardType = KeyboardType.Uri,
        )
        WikiTextField(
            value = draft.imageUrl,
            onValueChange = { onChange(draft.copy(imageUrl = it)) },
            label = "立绘 URL",
            keyboardType = KeyboardType.Uri,
        )
    }
}

@Composable
private fun HeroStatsEditor(
    draft: HeroWikiDraft,
    onChange: (HeroWikiDraft) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EditorNumberPair(
            firstValue = draft.attack,
            firstLabel = "攻击力",
            onFirstChange = { onChange(draft.copy(attack = it)) },
            secondValue = draft.health,
            secondLabel = "生命值",
            onSecondChange = { onChange(draft.copy(health = it)) },
        )
        EditorNumberPair(
            firstValue = draft.defense,
            firstLabel = "防御力",
            onFirstChange = { onChange(draft.copy(defense = it)) },
            secondValue = draft.speed,
            secondLabel = "速度",
            onSecondChange = { onChange(draft.copy(speed = it)) },
        )
        EditorNumberPair(
            firstValue = draft.criticalChance,
            firstLabel = "暴击率",
            onFirstChange = { onChange(draft.copy(criticalChance = it)) },
            secondValue = draft.criticalDamage,
            secondLabel = "暴击伤害",
            onSecondChange = { onChange(draft.copy(criticalDamage = it)) },
        )
        EditorNumberPair(
            firstValue = draft.effectiveness,
            firstLabel = "效果命中",
            onFirstChange = { onChange(draft.copy(effectiveness = it)) },
            secondValue = draft.effectResistance,
            secondLabel = "效果抗性",
            onSecondChange = { onChange(draft.copy(effectResistance = it)) },
        )
        WikiTextField(
            value = draft.combatPower,
            onValueChange = { onChange(draft.copy(combatPower = it)) },
            label = "战斗力",
            keyboardType = KeyboardType.Number,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExclusiveEquipmentEditor(
    equipment: ExclusiveEquipmentWikiDraft?,
    onChange: (ExclusiveEquipmentWikiDraft?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EditorSwitchRow(
            label = "有专属装备",
            checked = equipment != null,
            onCheckedChange = { enabled ->
                onChange(if (enabled) emptyExclusiveEquipmentWikiDraft() else null)
            },
        )
        equipment?.let { current ->
            WikiTextField(
                value = current.name,
                onValueChange = { onChange(current.copy(name = it)) },
                label = "装备名称",
            )
            WikiTextField(
                value = current.description,
                onValueChange = { onChange(current.copy(description = it)) },
                label = "装备说明",
                singleLine = false,
                minLines = 2,
            )
            WikiTextField(
                value = current.iconUrl,
                onValueChange = { onChange(current.copy(iconUrl = it)) },
                label = "图标 URL",
                keyboardType = KeyboardType.Uri,
            )
            EditorDropdown(
                value = current.statType,
                options = ExclusiveStatEditorOptions,
                label = "属性类型",
                onValueChange = { onChange(current.copy(statType = it)) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WikiTextField(
                    value = current.statMin,
                    onValueChange = { onChange(current.copy(statMin = it)) },
                    label = "属性下限",
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Decimal,
                )
                WikiTextField(
                    value = current.statMax,
                    onValueChange = { onChange(current.copy(statMax = it)) },
                    label = "属性上限",
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Decimal,
                )
            }
            EditorSwitchRow(
                label = "百分比属性",
                checked = current.statPercent,
                onCheckedChange = { onChange(current.copy(statPercent = it)) },
            )
            current.enhancements.forEachIndexed { index, enhancement ->
                Text(
                    text = "强化 ${enhancement.option}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                WikiTextField(
                    value = enhancement.skillSlot,
                    onValueChange = { value ->
                        onChange(
                            current.copy(
                                enhancements = current.enhancements.toMutableList().apply {
                                    set(index, enhancement.copy(skillSlot = value))
                                },
                            ),
                        )
                    },
                    label = "技能栏位",
                    keyboardType = KeyboardType.Number,
                )
                WikiTextField(
                    value = enhancement.description,
                    onValueChange = { value ->
                        onChange(
                            current.copy(
                                enhancements = current.enhancements.toMutableList().apply {
                                    set(index, enhancement.copy(description = value))
                                },
                            ),
                        )
                    },
                    label = "强化说明",
                    singleLine = false,
                    minLines = 2,
                )
            }
        }
    }
}

@Composable
private fun HeroSkillEditor(
    index: Int,
    skill: HeroSkillWikiDraft,
    onChange: (HeroSkillWikiDraft) -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = skill.name.ifBlank { "技能 ${index + 1}" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onDelete) { Text("删除") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WikiTextField(
                    value = skill.slot,
                    onValueChange = { onChange(skill.copy(slot = it)) },
                    label = "栏位",
                    modifier = Modifier.weight(0.35f),
                    keyboardType = KeyboardType.Number,
                )
                WikiTextField(
                    value = skill.name,
                    onValueChange = { onChange(skill.copy(name = it)) },
                    label = "技能名称",
                    modifier = Modifier.weight(0.65f),
                )
            }
            WikiTextField(
                value = skill.iconUrl,
                onValueChange = { onChange(skill.copy(iconUrl = it)) },
                label = "图标 URL",
                keyboardType = KeyboardType.Uri,
            )
            WikiTextField(
                value = skill.description,
                onValueChange = { onChange(skill.copy(description = it)) },
                label = "技能说明",
                singleLine = false,
                minLines = 3,
            )
            WikiTextField(
                value = skill.enhancedDescription,
                onValueChange = { onChange(skill.copy(enhancedDescription = it)) },
                label = "强化后说明",
                singleLine = false,
                minLines = 2,
            )
            EditorNumberPair(
                firstValue = skill.cooldown,
                firstLabel = "冷却回合",
                onFirstChange = { onChange(skill.copy(cooldown = it)) },
                secondValue = skill.soulGain,
                secondLabel = "获得灵魂",
                onSecondChange = { onChange(skill.copy(soulGain = it)) },
            )
            WikiTextField(
                value = skill.soulRequirement,
                onValueChange = { onChange(skill.copy(soulRequirement = it)) },
                label = "灵魂燃烧消耗",
                keyboardType = KeyboardType.Number,
            )
            WikiTextField(
                value = skill.soulDescription,
                onValueChange = { onChange(skill.copy(soulDescription = it)) },
                label = "灵魂燃烧说明",
                singleLine = false,
                minLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WikiTextField(
                    value = skill.attackRate,
                    onValueChange = { onChange(skill.copy(attackRate = it)) },
                    label = "攻击倍率",
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Decimal,
                )
                WikiTextField(
                    value = skill.pow,
                    onValueChange = { onChange(skill.copy(pow = it)) },
                    label = "POW",
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Decimal,
                )
            }
            EditorSwitchRow(
                label = "被动技能",
                checked = skill.isPassive,
                onCheckedChange = { onChange(skill.copy(isPassive = it)) },
            )
            EditorSwitchRow(
                label = "可强化",
                checked = skill.canEnhance,
                onCheckedChange = { onChange(skill.copy(canEnhance = it)) },
            )
            WikiTextField(
                value = skill.enhancements,
                onValueChange = { onChange(skill.copy(enhancements = it)) },
                label = "强化效果",
                singleLine = false,
                minLines = 3,
            )
            WikiTextField(
                value = skill.buffSlugs,
                onValueChange = { onChange(skill.copy(buffSlugs = it)) },
                label = "增益 Slug",
                singleLine = false,
                minLines = 2,
            )
            WikiTextField(
                value = skill.debuffSlugs,
                onValueChange = { onChange(skill.copy(debuffSlugs = it)) },
                label = "减益 Slug",
                singleLine = false,
                minLines = 2,
            )
        }
    }
}

@Composable
private fun EditorSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun EditorDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun EditorNumberPair(
    firstValue: String,
    firstLabel: String,
    onFirstChange: (String) -> Unit,
    secondValue: String,
    secondLabel: String,
    onSecondChange: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WikiTextField(
            value = firstValue,
            onValueChange = onFirstChange,
            label = firstLabel,
            modifier = Modifier.weight(1f),
            keyboardType = KeyboardType.Number,
        )
        WikiTextField(
            value = secondValue,
            onValueChange = onSecondChange,
            label = secondLabel,
            modifier = Modifier.weight(1f),
            keyboardType = KeyboardType.Number,
        )
    }
}

@Composable
private fun EditorSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun WikiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = singleLine,
        minLines = minLines,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorDropdown(
    value: String,
    options: List<EditorOption>,
    label: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = options.firstOrNull { it.value == value }?.label ?: value,
            onValueChange = {},
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            label = { Text(label) },
            readOnly = true,
            trailingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_drop_down),
                    contentDescription = null,
                )
            },
            singleLine = true,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onValueChange(option.value)
                        expanded = false
                    },
                    trailingIcon = if (option.value == value) {
                        {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                            )
                        }
                    } else null,
                )
            }
        }
    }
}
