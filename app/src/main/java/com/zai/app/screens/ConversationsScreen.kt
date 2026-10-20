package com.zai.app.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zai.app.data.Conversation
import com.zai.app.net.Api
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ConversationsViewModel : ViewModel() {
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching { Api.conversations() }
                .onSuccess { _conversations.value = it }
                .onFailure { _error.value = it.message }
            _loading.value = false
        }
    }

    fun archive(id: String) {
        viewModelScope.launch {
            runCatching { Api.archiveConversation(id) }
                .onSuccess { _conversations.value = _conversations.value.filterNot { it.id == id } }
                .onFailure { _error.value = it.message }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onOpenChat: (id: String, title: String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: ConversationsViewModel = viewModel()
    val conversations by vm.conversations.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    var archiveTarget by remember { mutableStateOf<Conversation?>(null) }

    // 从聊天页返回时 NavHost 重新进入组合，这里重拉列表即覆盖"聊完返回刷新"
    LaunchedEffect(Unit) { vm.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ZAI 工作台") },
                actions = {
                    IconButton(onClick = { vm.load() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onOpenChat("new", "") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("新对话") },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                loading && conversations.isEmpty() -> CircularProgressIndicator(
                    Modifier.align(Alignment.Center),
                )

                error != null && conversations.isEmpty() -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.load() }) { Text("重试") }
                }

                conversations.isEmpty() -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Chat,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("还没有对话，点右下角开始", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(conversations, key = { it.id }) { c ->
                        ConversationRow(
                            conversation = c,
                            onClick = { onOpenChat(c.id, c.title) },
                            onLongClick = { archiveTarget = c },
                        )
                    }
                }
            }
        }
    }

    archiveTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { archiveTarget = null },
            title = { Text("归档对话") },
            text = { Text("确定归档「${target.title}」吗？归档后可在网页端「全部对话」中找到。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.archive(target.id)
                    archiveTarget = null
                }) { Text("归档") }
            },
            dismissButton = {
                TextButton(onClick = { archiveTarget = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: Conversation,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${conversation.messageCount} 条消息")
                Text(conversation.displayTime)
            }
        },
        leadingContent = {
            Icon(
                Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}
