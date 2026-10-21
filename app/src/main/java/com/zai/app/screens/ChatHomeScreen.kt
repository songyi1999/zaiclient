package com.zai.app.screens

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RequestQuote
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zai.app.auth.Session
import com.zai.app.data.ConvosStore
import com.zai.app.data.Conversation
import com.zai.app.data.SkillsStore
import com.zai.app.ui.PendingInput
import com.zai.app.ui.PlusMenuSheet
import com.zai.app.ui.SkillPickerSheet
import com.zai.app.ui.rememberAttachmentPicker
import kotlinx.coroutines.launch
import java.util.Calendar

private data class Suggestion(val icon: ImageVector, val label: String)

// 建议池：覆盖 zai 平台的高频办公场景，每次展示 4 个，可"换一换"轮换
private val SUGGESTIONS = listOf(
    Suggestion(Icons.Filled.EditNote, "帮我起草一份合同初稿"),
    Suggestion(Icons.Filled.Slideshow, "做一份项目汇报 PPT"),
    Suggestion(Icons.Filled.Insights, "进行数据分析及可视化"),
    Suggestion(Icons.Filled.RequestQuote, "起草一份报价单"),
    Suggestion(Icons.Filled.EditNote, "润色一段公文"),
    Suggestion(Icons.Filled.FactCheck, "审阅文档并标出风险"),
)

private fun greeting(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        h < 6 -> "夜深了"
        h < 12 -> "上午好"
        h < 14 -> "中午好"
        h < 18 -> "下午好"
        else -> "晚上好"
    }
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)
@Composable
fun ChatHomeScreen(
    onOpenChat: (id: String, title: String) -> Unit,
    onSendMessage: (prompt: String) -> Unit,
    onStartWithDraft: () -> Unit,
) {
    val drawerState = rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var chipOffset by remember { mutableIntStateOf(0) }
    var showPlusSheet by remember { mutableStateOf(false) }

    // 选好附件 → 暂存 + 带着当前草稿进聊天页（草稿只预填不自动发送）
    val picker = rememberAttachmentPicker { uris ->
        PendingInput.uris = uris
        PendingInput.draft = input.trim()
        input = ""
        onStartWithDraft()
    }
    var showSkillSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { ConvosStore.refresh() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                AppDrawerContent(
                    onNewChat = {
                        scope.launch { drawerState.close() }
                        onOpenChat("new", "")
                    },
                    onOpenConversation = { c ->
                        scope.launch { drawerState.close() }
                        onOpenChat(c.id, c.title)
                    },
                )
            }
        },
    ) {
        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
            ) {
                // 顶部：圆角菜单按钮 + 标题 + 服务器状态
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .combinedClickable(onClick = {
                                    scope.launch { drawerState.open() }
                                }),
                        ) {
                            Icon(Icons.Filled.Menu, contentDescription = "会话列表")
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("文语工作台", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .padding(0.dp),
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(6.dp),
                                ) {}
                            }
                            Spacer(Modifier.width(5.dp))
                            Text(
                                "已连接 · ${Uri.parse(Session.serverUrl).host ?: ""}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // 中部：问候 + 建议芯片
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                ) {
                    Spacer(Modifier.height(32.dp))
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        modifier = Modifier.size(68.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Filled.Chat,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("文语，我帮你", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${greeting()}，今天要做点什么？",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        (0 until 4).forEach { i ->
                            val s = SUGGESTIONS[(chipOffset + i) % SUGGESTIONS.size]
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = Color.Transparent,
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                                ),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .combinedClickable(onClick = { onSendMessage(s.label) })
                                        .padding(horizontal = 14.dp, vertical = 9.dp),
                                ) {
                                    Icon(
                                        s.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(17.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(7.dp))
                                    Text(s.label, fontSize = 15.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { chipOffset += 4 }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text("换一换")
                    }
                }

                // 底部：+ 号 + 圆角输入条 + 圆形发送按钮
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Surface(
                        onClick = { showPlusSheet = true },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(46.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Add, contentDescription = "附件")
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("发消息，问问文语…") },
                        shape = RoundedCornerShape(26.dp),
                        maxLines = 4,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    val canSend = input.isNotBlank()
                    Surface(
                        shape = CircleShape,
                        color = if (canSend) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(46.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.combinedClickable(onClick = {
                                if (canSend) {
                                    onSendMessage(input.trim())
                                    input = ""
                                }
                            }),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "发送",
                                tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }

    // + 号菜单：拍照 / 相册 / 文件 / 技能
    if (showPlusSheet) {
        PlusMenuSheet(
            onDismiss = { showPlusSheet = false },
            onPickCamera = {
                showPlusSheet = false
                picker.launchCamera()
            },
            onPickGallery = {
                showPlusSheet = false
                picker.launchGallery()
            },
            onPickFiles = {
                showPlusSheet = false
                picker.launchFiles()
            },
            onPickSkill = {
                showPlusSheet = false
                SkillsStore.load()
                showSkillSheet = true
            },
        )
    }

    // 技能选择：选中后带草稿进聊天页，技能芯片预置
    if (showSkillSheet) {
        SkillPickerSheet(
            onDismiss = { showSkillSheet = false },
            onSelect = { skill ->
                showSkillSheet = false
                PendingInput.skill = skill
                PendingInput.draft = input.trim()
                input = ""
                onStartWithDraft()
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppDrawerContent(
    onNewChat: () -> Unit,
    onOpenConversation: (Conversation) -> Unit,
) {
    val conversations by ConvosStore.items.collectAsState()
    val loading by ConvosStore.loading.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Text("文语工作台", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onNewChat,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("新建对话")
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "对话",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (loading && conversations.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(conversations, key = { it.id }) { c ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { onOpenConversation(c) })
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                ) {
                    Text(
                        c.title,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${c.messageCount} 条 · ${c.displayTime}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
