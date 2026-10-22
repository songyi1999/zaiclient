package com.zai.app.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zai.app.data.Skill
import com.zai.app.data.SkillsStore
import com.zai.app.data.Template
import com.zai.app.net.Api
import com.zai.app.ui.PendingInput
import kotlinx.coroutines.launch

/** 三个发现页功能的通用顶栏页面骨架 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverSubScreen(
    title: String,
    onBack: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
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
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) { content() }
    }
}

// ---------------------------------------------------------------- 技能中心

/** 技能中心：按范围分组（全局/管理员推送/我的），点击可在对话中使用 */
@Composable
fun SkillsScreen(
    onBack: () -> Unit,
    onUseSkill: () -> Unit,
) {
    LaunchedEffect(Unit) { SkillsStore.load(true) }
    val skills by SkillsStore.items.collectAsState()
    val loading by SkillsStore.loading.collectAsState()
    var useTarget by remember { mutableStateOf<Skill?>(null) }

    DiscoverSubScreen(title = "技能中心", onBack = onBack) {
        when {
            loading && skills.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            skills.isEmpty() -> Text(
                "暂无可用技能",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val grouped = skills.groupBy { it.scope }
                listOf("GLOBAL", "ADMIN_PUSHED", "USER").forEach { scope ->
                    val list = grouped[scope] ?: return@forEach
                    item(key = "header-$scope") {
                        Text(
                            list.first().scopeLabel,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    items(list, key = { it.id }) { s ->
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { useTarget = s },
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(s.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                if (s.description.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        s.description,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        lineHeight = 17.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    useTarget?.let { skill ->
        AlertDialog(
            onDismissRequest = { useTarget = null },
            title = { Text(skill.name) },
            text = {
                Text(
                    skill.description.ifBlank { "（无描述）" },
                    fontSize = 13.sp,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    PendingInput.skill = skill
                    useTarget = null
                    onUseSkill()
                }) { Text("在对话中使用") }
            },
            dismissButton = {
                TextButton(onClick = { useTarget = null }) { Text("取消") }
            },
        )
    }
}

// ---------------------------------------------------------------- 资料库

/** 资料库：知识库语义搜索 */
@Composable
fun KnowledgeScreen(onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<com.zai.app.data.KnowledgeResult>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    DiscoverSubScreen(title = "资料库", onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("输入关键词或问题…") },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (query.isNotBlank() && !searching) {
                            searching = true
                            error = null
                            scope.launch {
                                runCatching { Api.knowledgeSearch(query.trim()) }
                                    .onSuccess { results = it; searched = true }
                                    .onFailure { error = it.message }
                                searching = false
                            }
                        }
                    },
                    enabled = query.isNotBlank() && !searching,
                ) {
                    if (searching) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("搜索")
                    }
                }
            }

            when {
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }

                !searched -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "语义检索企业知识库，快速找到相关文档",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }

                results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有找到相关内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(results.size) { idx ->
                        val r = results[idx]
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Row {
                                    Text(
                                        r.documentTitle,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "相关度 ${(r.similarity * 100).toInt()}%",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    r.snippet,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                    lineHeight = 17.sp,
                                )
                                if (r.ownerName.isNotBlank()) {
                                    Spacer(Modifier.height(5.dp))
                                    Text(
                                        "来源：${r.ownerName}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 模板库

/** 模板库：分类筛选，点击查看并带入对话使用（对齐网页端 sessionStorage 流程） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplatesScreen(
    onBack: () -> Unit,
    onUseTemplate: () -> Unit,
) {
    var templates by remember { mutableStateOf<List<Template>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Template?>(null) }

    LaunchedEffect(Unit) {
        runCatching { Api.templates() }
            .onSuccess { templates = it }
            .onFailure { error = it.message }
        loading = false
    }

    val categories = remember(templates) {
        listOfNotNull(null) + templates.map { it.category }.distinct()
    }
    val filtered = if (category == null) templates else templates.filter { it.category == category }

    DiscoverSubScreen(title = "模板库", onBack = onBack) {
        when {
            loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            error != null -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }

            templates.isEmpty() -> Text(
                "暂无模板",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> Column(Modifier.fillMaxSize()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    categories.forEach { c ->
                        FilterChip(
                            selected = category == c,
                            onClick = { category = if (category == c) null else c },
                            label = { Text(c ?: "全部") },
                        )
                    }
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(filtered, key = { it.id }) { t ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { preview = t },
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        t.title,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        t.category,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (t.description.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        t.description,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    preview?.let { t ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(t.title, fontSize = 17.sp) },
            text = {
                Column(Modifier.heightIn(max = 380.dp)) {
                    if (t.description.isNotBlank()) {
                        Text(
                            t.description,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    ) {
                        Text(
                            t.content,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier
                                .padding(10.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    PendingInput.draft = t.content
                    preview = null
                    onUseTemplate()
                }) { Text("使用模板") }
            },
            dismissButton = {
                TextButton(onClick = { preview = null }) { Text("关闭") }
            },
        )
    }
}
