package com.zai.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zai.app.data.ConvosStore
import com.zai.app.data.Message
import com.zai.app.net.Api
import com.zai.app.net.ChatEvent
import com.zai.app.ui.MarkdownText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {

    /** null = 新对话，SSE 首个 conversation 事件会填上真实 id */
    var conversationId: String? = null
        private set

    val messages = MutableStateFlow<List<Message>>(emptyList())
    val streaming = MutableStateFlow(false)
    val reasoning = MutableStateFlow("")
    val error = MutableStateFlow<String?>(null)
    val loadingHistory = MutableStateFlow(false)

    private var chatJob: Job? = null
    private var autoSent = false
    private var historyRequested = false

    /**
     * 进入页面初始化：已有会话则拉历史；带 prompt 的"新对话"自动发出首条消息
     * （首页输入/建议芯片直达），只发一次。
     */
    fun init(idArg: String?, prompt: String?) {
        val existing = idArg?.takeIf { it != "new" }
        if (existing != null && conversationId == null && !historyRequested) {
            conversationId = existing
            historyRequested = true
            loadHistory()
        }
        if (!autoSent && !prompt.isNullOrBlank() && messages.value.none { it.role == "USER" }) {
            autoSent = true
            send(prompt)
        }
    }

    private fun loadHistory() {
        val id = conversationId ?: return
        viewModelScope.launch {
            loadingHistory.value = true
            runCatching { Api.messages(id) }
                .onSuccess { list ->
                    messages.value = list.filter { it.role == "USER" || it.role == "ASSISTANT" }
                }
                .onFailure { error.value = it.message }
            loadingHistory.value = false
        }
    }

    fun send(text: String) {
        val content = text.trim()
        if (content.isEmpty() || streaming.value) return
        error.value = null
        messages.value = messages.value + listOf(
            Message(id = "local-user-${messages.value.size}", role = "USER", content = content, createdAt = ""),
            Message(id = "local-assistant", role = "ASSISTANT", content = "", createdAt = "", pending = true),
        )
        streaming.value = true
        reasoning.value = ""

        chatJob = viewModelScope.launch {
            try {
                Api.chat(content, conversationId).collect { ev ->
                    when (ev) {
                        is ChatEvent.ConversationCreated -> conversationId = ev.conversationId
                        is ChatEvent.Token -> appendAssistant(ev.content)
                        is ChatEvent.Reasoning -> reasoning.value += ev.content
                        is ChatEvent.Done -> Unit
                        is ChatEvent.Error -> error.value = ev.message
                    }
                }
            } catch (e: Exception) {
                error.value = e.message ?: "发送失败"
            } finally {
                streaming.value = false
                markAssistantDone()
                // 新会话可能已建标题，刷新共享列表（抽屉/记录 Tab）
                ConvosStore.refresh()
            }
        }
    }

    private fun appendAssistant(delta: String) {
        val list = messages.value.toMutableList()
        val last = list.lastOrNull() ?: return
        if (last.role == "ASSISTANT" && last.pending) {
            list[list.lastIndex] = last.copy(content = last.content + delta)
            messages.value = list
        }
    }

    private fun markAssistantDone() {
        val list = messages.value.toMutableList()
        val last = list.lastOrNull() ?: return
        if (last.role == "ASSISTANT" && last.pending) {
            list[list.lastIndex] = last.copy(pending = false)
            messages.value = list
        }
    }

    override fun onCleared() {
        chatJob?.cancel()
        super.onCleared()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationIdArg: String,
    titleArg: String?,
    promptArg: String?,
    onBack: () -> Unit,
) {
    val vm: ChatViewModel = viewModel()
    val messages by vm.messages.collectAsState()
    val streaming by vm.streaming.collectAsState()
    val reasoning by vm.reasoning.collectAsState()
    val error by vm.error.collectAsState()
    val loadingHistory by vm.loadingHistory.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(conversationIdArg) { vm.init(conversationIdArg, promptArg) }

    // 新消息或流式内容变化时自动滚到底部
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length, streaming) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            titleArg?.ifBlank { null } ?: "新对话",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (streaming) {
                            Text(
                                "正在回复…",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = { Spacer(Modifier.width(48.dp)) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            Box(Modifier.weight(1f)) {
                when {
                    loadingHistory && messages.isEmpty() -> CircularProgressIndicator(
                        Modifier.align(Alignment.Center),
                    )

                    messages.isEmpty() -> Text(
                        "有什么可以帮你？",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 14.dp, vertical = 10.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        itemsIndexed(messages, key = { _, m -> m.id }) { _, msg ->
                            MessageItem(msg)
                            if (msg.role == "ASSISTANT" && msg.pending && reasoning.isNotBlank()) {
                                ReasoningBox(reasoning)
                            }
                        }
                    }
                }
            }

            if (error != null) {
                Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            // 底部：圆角输入条 + 圆形发送按钮（与首页一致）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("发消息…") },
                    shape = RoundedCornerShape(26.dp),
                    maxLines = 5,
                    enabled = !streaming,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                val canSend = input.isNotBlank() && !streaming
                Surface(
                    onClick = {
                        if (canSend) {
                            vm.send(input)
                            input = ""
                        }
                    },
                    enabled = canSend,
                    shape = CircleShape,
                    color = if (canSend) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(46.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (streaming) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "发送",
                                tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageItem(msg: Message) {
    if (msg.isUser) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp, topEnd = 18.dp,
                    bottomStart = 18.dp, bottomEnd = 4.dp,
                ),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(
                    msg.content,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                )
            }
        }
    } else {
        // AI 消息：小头像 + 纯文本（WorkBuddy 风格，无气泡）
        Row(verticalAlignment = Alignment.Top) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                modifier = Modifier.size(30.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "Z",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.widthIn(max = 330.dp)) {
                when {
                    msg.pending && msg.content.isEmpty() -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        CircularProgressIndicator(
                            Modifier
                                .size(13.dp)
                                .alpha(0.7f),
                            strokeWidth = 1.5.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "思考中…",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> MarkdownText(msg.content + if (msg.pending) " ▍" else "")
                }
            }
        }
    }
}

@Composable
private fun ReasoningBox(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 39.dp, top = 6.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                "思考过程",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text.takeLast(600),   // 只展示最近的内容，避免超长推理卡顿
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
