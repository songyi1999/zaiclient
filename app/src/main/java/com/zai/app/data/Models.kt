package com.zai.app.data

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class User(
    val id: String,
    val username: String,
    val displayName: String?,
    val email: String?,
    val role: String?,
    val department: String?,
) {
    companion object {
        fun fromJson(o: JSONObject): User = User(
            id = o.optString("id"),
            username = o.optString("username"),
            displayName = o.optString("displayName").ifBlank { null },
            email = o.optString("email").ifBlank { null },
            role = o.optString("role").ifBlank { null },
            department = o.optString("department").ifBlank { null },
        )
    }
}

data class Conversation(
    val id: String,
    val title: String,
    val status: String,
    val updatedAt: String,
    val messageCount: Int,
) {
    val updatedAtMillis: Long by lazy {
        runCatching {
            Instant.parse(updatedAt).toEpochMilli()
        }.getOrDefault(0L)
    }

    /** "10-20 14:30" 样式的相对展示时间 */
    val displayTime: String by lazy {
        if (updatedAtMillis <= 0) return@lazy ""
        val t = Instant.ofEpochMilli(updatedAtMillis).atZone(ZoneId.systemDefault())
        t.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }

    companion object {
        fun fromJson(o: JSONObject): Conversation = Conversation(
            id = o.optString("id"),
            title = o.optString("title").ifBlank { "新对话" },
            status = o.optString("status", "ACTIVE"),
            updatedAt = o.optString("updatedAt"),
            messageCount = o.optJSONObject("_count")?.optInt("messages") ?: 0,
        )
    }
}

data class Message(
    val id: String,
    val role: String,          // USER | ASSISTANT | SYSTEM
    val content: String,
    val createdAt: String,
    val pending: Boolean = false,
) {
    val isUser: Boolean get() = role == "USER"

    /** ISO 时间转毫秒；解析失败返回 0（轮询兜底里用来判断"是否本轮新消息"） */
    val createdAtMillis: Long by lazy {
        runCatching { Instant.parse(createdAt).toEpochMilli() }.getOrDefault(0L)
    }

    companion object {
        fun fromJson(o: JSONObject): Message = Message(
            id = o.optString("id"),
            role = o.optString("role"),
            content = o.optString("content"),
            createdAt = o.optString("createdAt"),
        )
    }
}

data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val scope: String = "",     // GLOBAL | ADMIN_PUSHED | USER
) {
    val scopeLabel: String
        get() = when (scope) {
            "GLOBAL" -> "全局技能"
            "ADMIN_PUSHED" -> "管理员推送"
            "USER" -> "我的技能"
            else -> "技能"
        }

    companion object {
        fun fromJson(o: JSONObject): Skill = Skill(
            id = o.optString("id"),
            name = o.optString("name").ifBlank { "未命名技能" },
            description = o.optString("description"),
            scope = o.optString("scope"),
        )
    }
}

/** 模板（GET /api/templates），使用时把 content 带入聊天输入框 */
data class Template(
    val id: String,
    val title: String,
    val description: String,
    val content: String,
    val category: String,
) {
    companion object {
        fun fromJson(o: JSONObject): Template = Template(
            id = o.optString("id"),
            title = o.optString("title").ifBlank { "未命名模板" },
            description = o.optString("description"),
            content = o.optString("content"),
            category = o.optString("category").ifBlank { "未分类" },
        )
    }
}

/** 知识库语义搜索结果（POST /api/knowledge/search） */
data class KnowledgeResult(
    val documentTitle: String,
    val snippet: String,
    val similarity: Double,
    val ownerName: String,
) {
    companion object {
        fun fromJson(o: JSONObject): KnowledgeResult = KnowledgeResult(
            documentTitle = o.optString("documentTitle").ifBlank { "未命名文档" },
            snippet = o.optString("snippet").ifBlank { o.optString("content") },
            similarity = o.optDouble("similarity", 0.0),
            ownerName = o.optString("ownerName"),
        )
    }
}
