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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
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

    fun init(idArg: String?) {
        val existing = idArg?.takeIf { it != "new" }
        if (conversationId == null && existing != null && messages.value.isEmpty()) {
            conversationId = existing
            loadHistory()
        }
    }

    private fun loadHistory() {
        val id = conversationId ?: return
        viewModelScope.launch {
            loadingHistory.value = true
            runCatching { Api.messages(id) }
                .onSuccess { list ->
                    // 后端只保留 USER/ASSISTANT 进对外消息列表；SYSTEM 过滤掉
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
            }
        }
    }

    /** 流式追加到最后一条 assistant 消息（新增整表替换，token 级别增量） */
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

    LaunchedEffect(conversationIdArg) { vm.init(conversationIdArg) }

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
                    Column {
                        Text(
                            titleArg?.ifBlank { null } ?: "新对话",
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
                        "输入消息，开始与 AI 工作台助手对话",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp, vertical = 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(messages, key = { _, m -> m.id }) { _, msg ->
                            MessageBubble(msg)
                            // 思考过程紧跟在流式中的空 assistant 气泡后面
                            if (msg.pending && reasoning.isNotBlank()) {
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("输入消息…") },
                        maxLines = 5,
                        enabled = !streaming,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            vm.send(input)
                            input = ""
                        },
                        enabled = !streaming && input.isNotBlank(),
                    ) {
                        if (streaming) {
                            CircularProgressIndicator(Modifier.width(22.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "发送",
                                tint = if (input.isNotBlank()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
        }
    }
}

@Composable
private fun MessageBubble(msg: Message) {
    val pending = msg.pending && msg.content.isEmpty()
    Box(
        Modifier.fillMaxWidth(),
        contentAlignment = if (msg.isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (msg.isUser) 16.dp else 4.dp,
                bottomEnd = if (msg.isUser) 4.dp else 16.dp,
            ),
            color = if (msg.isUser) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (msg.isUser) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                when {
                    msg.isUser -> Text(msg.content, fontSize = 15.sp)
                    pending -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            Modifier
                                .width(14.dp)
                                .alpha(0.7f),
                            strokeWidth = 1.5.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("思考中…", fontSize = 13.sp)
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
            .padding(top = 4.dp),
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
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}
