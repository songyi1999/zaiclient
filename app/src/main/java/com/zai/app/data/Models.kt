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
) {
    companion object {
        fun fromJson(o: JSONObject): Skill = Skill(
            id = o.optString("id"),
            name = o.optString("name").ifBlank { "未命名技能" },
            description = o.optString("description"),
        )
    }
}
