package com.zai.app.screens

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zai.app.data.ConvosStore
import com.zai.app.data.Message
import com.zai.app.data.Skill
import com.zai.app.net.Api
import com.zai.app.net.ChatEvent
import com.zai.app.ui.MarkdownText
import com.zai.app.ui.PendingInput
import com.zai.app.ui.PlusMenuSheet
import com.zai.app.ui.rememberAttachmentPicker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** 输入框上方展示的附件芯片 */
data class AttachChip(
    val localId: String,
    val name: String,
    val status: String,            // UPLOADING | READY | FAILED
    val remoteId: String? = null,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    /** null = 新对话，SSE 首个 conversation 事件会填上真实 id */
    var conversationId: String? = null
        private set

    val messages = MutableStateFlow<List<Message>>(emptyList())
    val streaming = MutableStateFlow(false)
    val reasoning = MutableStateFlow("")
    val error = MutableStateFlow<String?>(null)
    val loadingHistory = MutableStateFlow(false)

    val attachments = MutableStateFlow<List<AttachChip>>(emptyList())
    val selectedSkill = MutableStateFlow<Skill?>(null)
    val skills = MutableStateFlow<List<Skill>>(emptyList())
    val skillsLoading = MutableStateFlow(false)

    /** 流状态（连接中… / 已连接 · N字 · 心跳N）——诊断 + 进度展示 */
    val streamInfo = MutableStateFlow("")

    private var chatJob: Job? = null
    private var autoSent = false
    private var historyRequested = false
    private var skillsLoaded = false

    fun init(idArg: String?, prompt: String?) {
        val existing = idArg?.takeIf { it != "new" }
        if (existing != null && conversationId == null && !historyRequested) {
            conversationId = existing
            historyRequested = true
            loadHistory()
        }
        // 首页 "+" 选好的附件：进入聊天页即开始上传（不等发送）
        val pending = PendingInput.consumeUris()
        if (pending.isNotEmpty()) addAttachments(pending)
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

    /** 上传附件前保证有会话（后端附件必须挂在会话上） */
    private suspend fun ensureConversation(): String {
        conversationId?.let { return it }
        val id = Api.createConversation()
        conversationId = id
        return id
    }

    /** 相册/文件选择器返回的 content Uri 列表 → 逐个上传为芯片 */
    fun addAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val ctx: Context = getApplication()
        viewModelScope.launch {
            for (uri in uris) {
                val localId = "att-${System.nanoTime()}"
                val name = queryDisplayName(ctx, uri) ?: "文件"
                attachments.value = attachments.value + AttachChip(localId, name, "UPLOADING")
                runCatching {
                    val mime = ctx.contentResolver.getType(uri) ?: guessMime(name)
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("无法读取文件")
                    val convId = ensureConversation()
                    val remoteId = Api.uploadAttachment(convId, name, mime, bytes)
                    attachments.value = attachments.value.map {
                        if (it.localId == localId) it.copy(status = "READY", remoteId = remoteId) else it
                    }
                }.onFailure {
                    attachments.value = attachments.value.map {
                        if (it.localId == localId) it.copy(status = "FAILED") else it
                    }
                    error.value = "${name}: ${it.message ?: "上传失败"}"
                }
            }
        }
    }

    /** 拍照完成的本地文件 → 上传 */
    fun addCameraPhoto(file: File) = addAttachments(listOf(Uri.fromFile(file)))

    fun removeAttachment(chip: AttachChip) {        attachments.value = attachments.value.filterNot { it.localId == chip.localId }
        chip.remoteId?.let { id ->
            viewModelScope.launch { runCatching { Api.deleteAttachment(id) } }
        }
    }

    fun loadSkills() {
        if (skillsLoaded) return
        viewModelScope.launch {
            skillsLoading.value = true
            runCatching { Api.skills() }
                .onSuccess {
                    skills.value = it
                    skillsLoaded = true
                }
            skillsLoading.value = false
        }
    }

    fun send(text: String) {
        val raw = text.trim()
        if (raw.isEmpty() || streaming.value) return
        error.value = null

        // 选了技能 → 组装成明确指令，引导 agent 调用对应技能
        val skill = selectedSkill.value
        val content = if (skill != null) "请使用技能「${skill.name}」完成以下任务：\n$raw" else raw
        selectedSkill.value = null

        // 轮询兜底的基线：发送前的消息数（服务端落库后比它多 2 条 = 本轮完成）
        val baselineCount = messages.value.size
        val sentAt = System.currentTimeMillis()

        messages.value = messages.value + listOf(
            Message(id = "local-user-${messages.value.size}", role = "USER", content = content, createdAt = ""),
            Message(id = "local-assistant", role = "ASSISTANT", content = "", createdAt = "", pending = true),
        )
        streaming.value = true
        reasoning.value = ""
        streamInfo.value = "连接中…"

        var tokens = 0
        chatJob = viewModelScope.launch {
            try {
                Api.chat(content, conversationId).collect { ev ->
                    when (ev) {
                        is ChatEvent.Connected -> streamInfo.value = "已连接"

                        is ChatEvent.ConversationCreated -> {
                            conversationId = ev.conversationId
                            streamInfo.value = "已连接"
                        }

                        is ChatEvent.Token -> {
                            tokens += ev.content.length
                            appendAssistant(ev.content)
                            streamInfo.value = "已连接 · $tokens 字"
                        }

                        is ChatEvent.ReplaceContent -> replaceAssistant(ev.content)

                        is ChatEvent.Reasoning -> reasoning.value += ev.content

                        is ChatEvent.Done -> Unit
                        is ChatEvent.Error -> error.value = ev.message
                    }
                }
            } catch (e: Exception) {
                error.value = e.message ?: "发送失败"
            } finally {
                streaming.value = false
                streamInfo.value = ""
                markAssistantDone()
                ConvosStore.refresh()
            }
        }
        startPollingFallback(sentAt, baselineCount)
    }

    /**
     * 流式通道兜底：部分手机网络（运营商对明文 HTTP 的代理）会缓冲 SSE，
     * 表现为"一直在思考中"但服务端其实已完整生成（历史记录里能看到）。
     * 发送 6 秒后仍无内容时改为每 4 秒拉一次服务端消息，
     * 一旦发现本轮回复已落库就直接完整展示。
     */
    private fun startPollingFallback(sentAt: Long, baselineCount: Int) {
        viewModelScope.launch {
            delay(6000)
            var waited = 6
            while (isActive && streaming.value && waited < 300) {
                // SSE 已经出字（或已结束）→ 轮询退出
                val cur = messages.value.lastOrNull()
                if (cur == null || !(cur.role == "ASSISTANT" && cur.pending)) break
                if (cur.content.isNotEmpty()) break

                streamInfo.value = "流式通道未响应，正在获取回复…（${waited}s）"
                runCatching {
                    val convId = conversationId
                        ?: Api.conversations()
                            .firstOrNull { it.updatedAtMillis >= sentAt - 60_000 }
                            ?.id
                            ?.also { conversationId = it }
                    if (convId != null) {
                        val server = Api.messages(convId)
                            .filter { it.role == "USER" || it.role == "ASSISTANT" }
                        val lastMsg = server.lastOrNull()
                        val isThisTurn = server.size >= baselineCount + 2 &&
                            lastMsg?.role == "ASSISTANT" &&
                            lastMsg.createdAtMillis >= sentAt - 5000
                        if (isThisTurn && !lastMsg!!.content.isBlank()) {
                            applyFallbackReply(lastMsg)
                        }
                    }
                }
                if (!streaming.value) break
                delay(4000)
                waited += 4
            }
            // 超过 5 分钟仍无结果 → 结束等待并提示
            if (streaming.value) {
                val cur = messages.value.lastOrNull()
                if (cur?.role == "ASSISTANT" && cur.pending && cur.content.isEmpty()) {
                    streaming.value = false
                    streamInfo.value = ""
                    chatJob?.cancel()
                    markAssistantDone()
                    error.value = "长时间未收到回复（网络可能限制了流式传输），请稍后在对话记录中查看结果"
                    ConvosStore.refresh()
                }
            }
        }
    }

    /** 用服务端已落库的完整回复替换本地空白的等待气泡 */
    private fun applyFallbackReply(serverMsg: Message) {
        val list = messages.value.toMutableList()
        val last = list.lastOrNull() ?: return
        if (!(last.role == "ASSISTANT" && last.pending && last.content.isEmpty())) return
        list[list.lastIndex] = serverMsg.copy(pending = false)
        messages.value = list
        streaming.value = false
        streamInfo.value = ""
        reasoning.value = ""
        chatJob?.cancel()
        ConvosStore.refresh()
    }

    private fun appendAssistant(delta: String) {
        val list = messages.value.toMutableList()
        val last = list.lastOrNull() ?: return
        if (last.role == "ASSISTANT" && last.pending) {
            list[list.lastIndex] = last.copy(content = last.content + delta)
            messages.value = list
        }
    }

    /** 服务端 replace_content：整体替换已流出的内容 */
    private fun replaceAssistant(content: String) {
        val list = messages.value.toMutableList()
        val last = list.lastOrNull() ?: return
        if (last.role == "ASSISTANT" && last.pending) {
            list[list.lastIndex] = last.copy(content = content)
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

    private fun queryDisplayName(ctx: Context, uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return File(uri.path ?: return null).name
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: uri.lastPathSegment
    }.getOrNull()

    /** contentResolver 拿不到 MIME 时（如 file:// 拍照产物）按扩展名兜底 */
    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "txt", "md" -> "text/plain"
        "doc", "docx" -> "application/msword"
        "xls", "xlsx" -> "application/vnd.ms-excel"
        "ppt", "pptx" -> "application/vnd.ms-powerpoint"
        else -> "application/octet-stream"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val attachments by vm.attachments.collectAsState()
    val selectedSkill by vm.selectedSkill.collectAsState()
    val skills by vm.skills.collectAsState()
    val skillsLoading by vm.skillsLoading.collectAsState()
    val streamInfo by vm.streamInfo.collectAsState()
    // 首页带过来的草稿只作为初始输入，不自动发送
    var input by remember { mutableStateOf(PendingInput.consumeDraft()) }
    var showPlusSheet by remember { mutableStateOf(false) }
    var showSkillSheet by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val picker = rememberAttachmentPicker { uris -> vm.addAttachments(uris) }

    LaunchedEffect(conversationIdArg) { vm.init(conversationIdArg, promptArg) }

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
                        )
                        if (streaming && streamInfo.isNotBlank()) {
                            Text(
                                streamInfo,
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
                            MessageItem(msg, streamInfo)
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

            // 附件与技能芯片
            if (attachments.isNotEmpty() || selectedSkill != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                ) {
                    selectedSkill?.let { s ->
                        AssistChip(
                            onClick = { vm.selectedSkill.value = null },
                            label = { Text("技能：${s.name}", maxLines = 1) },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Psychology,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "移除",
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                        )
                    }
                    attachments.forEach { chip ->
                        AssistChip(
                            onClick = { if (chip.status != "UPLOADING") vm.removeAttachment(chip) },
                            label = {
                                Text(
                                    when (chip.status) {
                                        "UPLOADING" -> "${chip.name}（上传中…）"
                                        "FAILED" -> "${chip.name}（失败，点击移除）"
                                        else -> chip.name
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 180.dp),
                                )
                            },
                            leadingIcon = {
                                if (chip.status == "UPLOADING") {
                                    CircularProgressIndicator(
                                        Modifier.size(13.dp),
                                        strokeWidth = 1.5.dp,
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.Description,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }

            // 底部：+ 号 + 圆角输入条 + 圆形发送按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Surface(
                    onClick = { showPlusSheet = true },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(46.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Add, contentDescription = "附件与技能")
                    }
                }
                Spacer(Modifier.width(8.dp))
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
                vm.loadSkills()
                showSkillSheet = true
            },
        )
    }

    // 技能列表
    if (showSkillSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSkillSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Text(
                "选择技能",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            if (skillsLoading && skills.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(Modifier.size(22.dp)) }
            } else if (skills.isEmpty()) {
                Text(
                    "暂无可用技能",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(skills, key = { _, s -> s.id }) { _, s ->
                        ListItem(
                            headlineContent = { Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                if (s.description.isNotBlank()) {
                                    Text(
                                        s.description,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 12.sp,
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.selectedSkill.value = s
                                    showSkillSheet = false
                                },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                    }
                }
            }
            TextButton(
                onClick = { showSkillSheet = false },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            ) { Text("取消") }
        }
    }
}

@Composable
private fun MessageItem(msg: Message, streamInfo: String = "") {
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
                            // 连接状态直接展示在对话里：连接中… / 已连接 / 已连接 · N字 · 心跳N
                            streamInfo.ifBlank { "思考中…" },
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
