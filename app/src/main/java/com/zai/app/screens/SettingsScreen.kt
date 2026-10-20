package com.zai.app.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zai.app.auth.Session

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onLogout: () -> Unit) {
    var serverUrl by remember { mutableStateOf(Session.serverUrl) }
    val user = Session.user

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .padding(0.dp)
                            .size(72.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                user?.displayName ?: user?.username ?: "未登录",
                fontSize = 18.sp,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(24.dp))

            ListItem(headlineContent = { Text("用户名") }, supportingContent = { Text(user?.username ?: "-") })
            ListItem(headlineContent = { Text("角色") }, supportingContent = { Text(roleName(user?.role)) })
            ListItem(headlineContent = { Text("部门") }, supportingContent = { Text(user?.department ?: "-") })

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("服务器地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "修改后立即生效（需重新登录）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    Session.updateServerUrl(serverUrl)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存服务器地址") }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    Session.logout()
                    onLogout()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("退出登录") }

            Spacer(Modifier.height(16.dp))
            Text(
                "ZAI 工作台 安卓端 v0.1.0",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun roleName(role: String?): String = when (role) {
    "SYSTEM_ADMIN" -> "系统管理员"
    "IT_ADMIN" -> "IT管理员"
    "MANAGER" -> "部门领导"
    "EMPLOYEE" -> "普通员工"
    null, "" -> "-"
    else -> role
}
