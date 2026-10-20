package com.zai.app.data

import com.zai.app.net.Api
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 会话列表的进程级共享数据源：首页抽屉和「记录」Tab 共用，
 * 聊天结束（Done/出错）后调用 refresh() 让列表自动更新。
 */
object ConvosStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _items = MutableStateFlow<List<Conversation>>(emptyList())
    val items: StateFlow<List<Conversation>> = _items

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var inFlight: Job? = null

    fun refresh() {
        if (inFlight?.isActive == true) return
        inFlight = scope.launch {
            _loading.value = true
            runCatching { Api.conversations() }
                .onSuccess {
                    _items.value = it
                    _error.value = null
                }
                .onFailure { _error.value = it.message }
            _loading.value = false
        }
    }

    fun archive(id: String) {
        scope.launch {
            runCatching { Api.archiveConversation(id) }
            refresh()
        }
    }
}
