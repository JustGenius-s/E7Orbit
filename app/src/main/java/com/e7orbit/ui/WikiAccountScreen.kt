package com.e7orbit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.e7orbit.R
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource

private enum class WikiAccountMode {
    SIGN_IN,
    REGISTER,
    FORGOT_PASSWORD,
    RESET_PASSWORD,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WikiAccountScreen(
    state: WikiEditorUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onSignIn: (String, String) -> Unit,
    onRegister: (String, String, String) -> Unit,
    onResendConfirmation: (String) -> Unit,
    onSendPasswordReset: (String) -> Unit,
    onUpdatePassword: (String, String) -> Unit,
    onSignOut: () -> Unit,
) {
    BackHandler(enabled = !state.authenticating) { onBack() }
    var mode by remember(state.passwordRecovery) {
        mutableStateOf(
            if (state.passwordRecovery) {
                WikiAccountMode.RESET_PASSWORD
            } else {
                WikiAccountMode.SIGN_IN
            },
        )
    }
    var email by remember {
        mutableStateOf(state.pendingConfirmationEmail ?: state.email.orEmpty())
    }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val busy = state.authenticating

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Wiki 账号") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = contentPadding.calculateTopPadding() + 20.dp,
                end = 20.dp,
                bottom = contentPadding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = when {
                            state.passwordRecovery -> "设置新密码"
                            state.email != null -> "账号状态"
                            else -> "登录或注册"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = when {
                            state.passwordRecovery -> "请设置一个新的登录密码。"
                            state.email != null -> "当前账号已登录。"
                            else -> "使用邮箱管理你的 Wiki 账号。"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (state.email != null && !state.passwordRecovery) {
                        SignedInAccount(
                            state = state,
                            onSignOut = onSignOut,
                        )
                    } else {
                        when (mode) {
                            WikiAccountMode.SIGN_IN,
                            WikiAccountMode.REGISTER,
                            -> PrimaryTabRow(selectedTabIndex = if (mode == WikiAccountMode.SIGN_IN) 0 else 1) {
                                Tab(
                                    selected = mode == WikiAccountMode.SIGN_IN,
                                    onClick = { if (!busy) mode = WikiAccountMode.SIGN_IN },
                                    text = { Text("登录") },
                                )
                                Tab(
                                    selected = mode == WikiAccountMode.REGISTER,
                                    onClick = { if (!busy) mode = WikiAccountMode.REGISTER },
                                    text = { Text("注册") },
                                )
                            }

                            else -> Unit
                        }

                        AccountForm(
                            mode = mode,
                            state = state,
                            email = email,
                            password = password,
                            confirmation = confirmation,
                            busy = busy,
                            onEmailChanged = { email = it },
                            onPasswordChanged = { password = it },
                            onConfirmationChanged = { confirmation = it },
                            onSignIn = onSignIn,
                            onRegister = onRegister,
                            onResendConfirmation = onResendConfirmation,
                            onSendPasswordReset = onSendPasswordReset,
                            onUpdatePassword = onUpdatePassword,
                            onModeChanged = { mode = it },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SignedInAccount(
    state: WikiEditorUiState,
    onSignOut: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("已登录", style = MaterialTheme.typography.titleMedium)
            Text(
                text = state.email.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
            )
            HorizontalDivider()
            Text(
                text = if (state.canEdit) {
                    "Wiki 编辑权限已启用"
                } else {
                    "当前账号可以登录，但没有 Wiki 编辑权限"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.errorMessage?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(
                onClick = onSignOut,
                enabled = !state.authenticating,
            ) {
                Text("退出账号")
            }
        }
    }
}

@Composable
private fun AccountForm(
    mode: WikiAccountMode,
    state: WikiEditorUiState,
    email: String,
    password: String,
    confirmation: String,
    busy: Boolean,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onConfirmationChanged: (String) -> Unit,
    onSignIn: (String, String) -> Unit,
    onRegister: (String, String, String) -> Unit,
    onResendConfirmation: (String) -> Unit,
    onSendPasswordReset: (String) -> Unit,
    onUpdatePassword: (String, String) -> Unit,
    onModeChanged: (WikiAccountMode) -> Unit,
) {
    val submitEnabled = !busy && when (mode) {
        WikiAccountMode.SIGN_IN -> email.isNotBlank() && password.isNotEmpty()
        WikiAccountMode.REGISTER ->
            email.isNotBlank() && password.isNotEmpty() && confirmation.isNotEmpty()
        WikiAccountMode.FORGOT_PASSWORD -> email.isNotBlank()
        WikiAccountMode.RESET_PASSWORD -> password.isNotEmpty() && confirmation.isNotEmpty()
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (mode != WikiAccountMode.RESET_PASSWORD) {
                OutlinedTextField(
                    value = email,
                    onValueChange = onEmailChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("邮箱") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true,
                    enabled = !busy,
                )
            }
            if (mode == WikiAccountMode.SIGN_IN ||
                mode == WikiAccountMode.REGISTER ||
                mode == WikiAccountMode.RESET_PASSWORD
            ) {
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (mode == WikiAccountMode.RESET_PASSWORD) "新密码" else "密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    enabled = !busy,
                )
            }
            if (mode == WikiAccountMode.REGISTER || mode == WikiAccountMode.RESET_PASSWORD) {
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = onConfirmationChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("确认密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    enabled = !busy,
                )
            }
            if (mode == WikiAccountMode.RESET_PASSWORD) {
                Text(
                    text = "密码至少需要 8 个字符。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.pendingConfirmationEmail?.let { pendingEmail ->
                if (mode == WikiAccountMode.SIGN_IN || mode == WikiAccountMode.REGISTER) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("请先确认 $pendingEmail 的邮箱。")
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
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.errorMessage?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = {
                        when (mode) {
                            WikiAccountMode.SIGN_IN -> onSignIn(email, password)
                            WikiAccountMode.REGISTER -> onRegister(email, password, confirmation)
                            WikiAccountMode.FORGOT_PASSWORD -> onSendPasswordReset(email)
                            WikiAccountMode.RESET_PASSWORD -> onUpdatePassword(password, confirmation)
                        }
                    },
                    enabled = submitEnabled,
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(
                        text = when (mode) {
                            WikiAccountMode.SIGN_IN -> "登录"
                            WikiAccountMode.REGISTER -> "注册"
                            WikiAccountMode.FORGOT_PASSWORD -> "发送重置邮件"
                            WikiAccountMode.RESET_PASSWORD -> "更新密码"
                        },
                        modifier = Modifier.padding(start = if (busy) 8.dp else 0.dp),
                    )
                }
            }
            when (mode) {
                WikiAccountMode.SIGN_IN -> {
                    TextButton(
                        onClick = { onModeChanged(WikiAccountMode.FORGOT_PASSWORD) },
                        enabled = !busy,
                    ) {
                        Text("忘记密码？")
                    }
                }

                WikiAccountMode.FORGOT_PASSWORD,
                WikiAccountMode.RESET_PASSWORD,
                -> TextButton(
                    onClick = { onModeChanged(WikiAccountMode.SIGN_IN) },
                    enabled = !busy,
                ) {
                    Text("返回登录")
                }

                WikiAccountMode.REGISTER -> Unit
            }
        }
    }
}
