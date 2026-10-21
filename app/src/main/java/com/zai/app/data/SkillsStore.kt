package com.zai.app.data

import com.zai.app.net.Api
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 技能列表的进程级共享缓存：首页和聊天页的技能选择弹层共用 */
object SkillsStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _items = MutableStateFlow<List<Skill>>(emptyList())
    val items: StateFlow<List<Skill>> = _items

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private var loaded = false
    private var inFlight: Job? = null

    fun load(force: Boolean = false) {
        if (loaded && !force) return
        if (inFlight?.isActive == true) return
        inFlight = scope.launch {
            _loading.value = true
            runCatching { Api.skills() }
                .onSuccess {
                    _items.value = it
                    loaded = it.isNotEmpty()
                }
            _loading.value = false
        }
    }
}
