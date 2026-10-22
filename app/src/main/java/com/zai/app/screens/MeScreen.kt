package com.zai.app.screens

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zai.app.auth.Session
import com.zai.app.ui.UpdateCheckRow

/** 「我的」Tab：个人资料卡 + 服务器配置 + 检查更新 + 退出登录 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MeScreen(onLogout: () -> Unit) {
    var showServerDialog by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf(Session.serverUrl) }
    val user = Session.user

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "我的",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // 个人资料卡
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(54.dp),
                    ) {
                        AlignmentCenterText(
                            (user?.displayName ?: user?.username ?: "文").take(1).uppercase(),
                        )
                    }
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            user?.displayName ?: user?.username ?: "未登录",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            listOfNotNull(
                                user?.username?.takeIf { it.isNotBlank() },
                                roleName(user?.role),
                            ).joinToString(" · "),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            // 设置卡：服务器 + 检查更新
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    ListItem(
                        headlineContent = { Text("服务器地址") },
                        supportingContent = {
                            Text(
                                Session.serverUrl,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = { Icon(Icons.Filled.Dns, contentDescription = null) },
                        trailingContent = {
                            Text(
                                "修改",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(onClick = {
                                serverUrl = Session.serverUrl
                                showServerDialog = true
                            }),
                    )
                    UpdateCheckRow()
                }
            }

            Spacer(Modifier.height(14.dp))
            // 部门/角色信息
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    ListItem(
                        headlineContent = { Text("角色") },
                        supportingContent = { Text(roleName(user?.role)) },
                    )
                    ListItem(
                        headlineContent = { Text("部门") },
                        supportingContent = { Text(user?.department ?: "-") },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            // 退出登录
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = {
                        Session.logout()
                        onLogout()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    elevation = null,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("退出登录")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showServerDialog) {
        // 与登录页"高级"一致的下拉选择；切换服务器后需重新登录
        var menuOpen by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showServerDialog = false },
            title = { Text("切换服务器") },
            text = {
                Column {
                    ExposedDropdownMenuBox(
                        expanded = menuOpen,
                        onExpandedChange = { menuOpen = it },
                    ) {
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("服务器") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen)
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            Session.SERVER_OPTIONS.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt) },
                                    onClick = {
                                        serverUrl = opt
                                        menuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "切换服务器后需要重新登录（两套环境账号不通用）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val changed = serverUrl.trimEnd('/') != Session.serverUrl.trimEnd('/')
                    Session.updateServerUrl(serverUrl)
                    showServerDialog = false
                    if (changed) {
                        Session.logout()
                        onLogout()
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showServerDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AlignmentCenterText(text: String) {
    androidx.compose.foundation.layout.Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
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
