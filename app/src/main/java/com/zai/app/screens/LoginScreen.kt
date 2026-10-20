package com.zai.app.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zai.app.auth.Session
import com.zai.app.net.Api
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit) {
    // 之前记住过账号密码则自动回填
    var serverUrl by remember { mutableStateOf(Session.serverUrl) }
    var username by remember { mutableStateOf(Session.rememberedUsername ?: "") }
    var password by remember { mutableStateOf(Session.rememberedPassword ?: "") }
    var rememberPassword by remember { mutableStateOf(Session.rememberedPassword != null) }
    var showAdvanced by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(96.dp))
        Icon(
            Icons.AutoMirrored.Filled.Chat,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("ZAI 工作台", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            "企业智能共享平台",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            Checkbox(
                checked = rememberPassword,
                onCheckedChange = { rememberPassword = it },
            )
            Text("记住密码", fontSize = 14.sp)
        }

        // 高级：默认收起，展开后才显示服务器地址
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Icon(
                    if (showAdvanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text("高级")
            }
            Spacer(Modifier.weight(1f))
        }
        if (showAdvanced) {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("服务器地址") },
                placeholder = { Text(Session.DEFAULT_SERVER) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                error!!,
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (loading) return@Button
                if (username.isBlank() || password.isBlank()) {
                    error = "请输入用户名和密码"
                    return@Button
                }
                loading = true
                error = null
                scope.launch {
                    try {
                        Session.updateServerUrl(serverUrl.ifBlank { Session.DEFAULT_SERVER })
                        Api.login(username.trim(), password)
                        // 登录成功后才记住/清除，避免存下错误密码
                        if (rememberPassword) {
                            Session.saveRemembered(username.trim(), password)
                        } else {
                            Session.clearRemembered()
                        }
                        onLoginSuccess()
                    } catch (e: Exception) {
                        error = e.message ?: "登录失败"
                    } finally {
                        loading = false
                    }
                }
            },
            enabled = !loading,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("登 录")
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}
