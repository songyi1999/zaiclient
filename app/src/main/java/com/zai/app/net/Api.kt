package com.zai.app.net

import com.zai.app.auth.Session
import com.zai.app.data.Conversation
import com.zai.app.data.Message
import com.zai.app.data.Skill
import com.zai.app.data.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 聊天 SSE 事件（与后端 agent.controller 的 wire format 一一对应） */
sealed class ChatEvent {
    data class ConversationCreated(val conversationId: String) : ChatEvent()
    data class Token(val content: String) : ChatEvent()
    data class Reasoning(val content: String) : ChatEvent()
    data class Done(val conversationId: String) : ChatEvent()
    data class Error(val message: String) : ChatEvent()

    /** SSE 注释行（后端 15s 心跳）。仅用于流状态展示，不影响内容。 */
    object Heartbeat : ChatEvent()
}

class ApiException(val code: Int, message: String) : Exception(message)

object Api {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 无鉴权客户端：login / refresh 专用，避免拦截器递归 */
    private val bareClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 鉴权客户端：自动带 Bearer，401 时单次刷新重试 */
    private val authedClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * SSE 长连接专用：读超时放大到 5 分钟（多工具调用可能长时间无 token，
     * 后端 15s 心跳会持续重置该计时器，这里只是兜底）。
     */
    private val sseClient: OkHttpClient = authedClient.newBuilder()
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    private class AuthInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val orig = chain.request()
            val token = Session.accessToken()
                ?: return chain.proceed(orig)
            val req = orig.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
            var resp = chain.proceed(req)
            // access token 过期 → 刷新后重放一次（仅非 /auth/ 路径，防循环）
            if (resp.code == 401 && !orig.url.encodedPath.startsWith("/auth")) {
                val refreshed = SessionManager.refreshTokensBlocking()
                if (refreshed) {
                    resp.close()
                    val newToken = Session.accessToken() ?: return resp
                    resp = chain.proceed(
                        orig.newBuilder()
                            .header("Authorization", "Bearer $newToken")
                            .build(),
                    )
                }
            }
            return resp
        }
    }

    // ------------------------------------------------------------------ auth

    suspend fun login(username: String, password: String): User = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .toString()
            .toRequestBody(JSON)
        val req = Request.Builder()
            .url("${Session.apiBase}/auth/login")
            .post(body)
            .build()
        val resp = bareClient.newCall(req).execute()
        resp.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw ApiException(it.code, errorMessage(text, "登录失败"))
            val o = JSONObject(text)
            Session.saveLogin(
                o.getString("accessToken"),
                o.getString("refreshToken"),
                User.fromJson(o.getJSONObject("user")),
            )
            Session.user ?: throw ApiException(it.code, "响应缺少用户信息")
        }
    }

    suspend fun me(): User = withContext(Dispatchers.IO) {
        val resp = authedGet("/auth/me")
        resp.use {
            if (!it.isSuccessful) throw ApiException(it.code, errorMessage(it.body?.string(), "获取用户信息失败"))
            User.fromJson(JSONObject(it.body!!.string()))
        }
    }

    // -------------------------------------------------------- conversations

    suspend fun conversations(): List<Conversation> = withContext(Dispatchers.IO) {
        val resp = authedGet("/conversations")
        resp.use {
            if (!it.isSuccessful) throw ApiException(it.code, errorMessage(it.body?.string(), "获取会话列表失败"))
            val arr = JSONArray(it.body!!.string())
            (0 until arr.length()).map { Conversation.fromJson(arr.getJSONObject(it)) }
        }
    }

    suspend fun messages(conversationId: String): List<Message> = withContext(Dispatchers.IO) {
        val resp = authedGet("/conversations/$conversationId/messages")
        resp.use {
            if (!it.isSuccessful) throw ApiException(it.code, errorMessage(it.body?.string(), "获取消息失败"))
            val arr = JSONArray(it.body!!.string())
            (0 until arr.length()).map { Message.fromJson(arr.getJSONObject(it)) }
        }
    }

    suspend fun archiveConversation(conversationId: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("archived", true).toString().toRequestBody(JSON)
        val req = Request.Builder()
            .url("${Session.apiBase}/conversations/$conversationId")
            .patch(body)
            .build()
        val resp = authedClient.newCall(req).execute()
        resp.use {
            if (!it.isSuccessful) throw ApiException(it.code, errorMessage(it.body?.string(), "归档失败"))
        }
    }

    /** 新建会话，返回 id（发附件前需要先有会话） */
    suspend fun createConversation(): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${Session.apiBase}/conversations")
            .post("{}".toRequestBody(JSON))
            .build()
        val resp = authedClient.newCall(req).execute()
        resp.use {
            if (!it.isSuccessful) throw ApiException(it.code, errorMessage(it.body?.string(), "创建会话失败"))
            JSONObject(it.body!!.string()).getString("id")
        }
    }

    // ----------------------------------------------------------------- chat

    /**
     * SSE 流式对话。POST /api/agents/default/chat
     * 事件流见后端 agent.controller：conversation → token/reasoning* → done。
     *
     * 实现要点（对比早期版本的坑）：
     * - 直接在 flow 构建器里同步 execute + 逐行读，emit 跨上下文交给 flowOn 处理，
     *   不引入 channelFlow/子协程等额外机制；
     * - 显式 `Accept-Encoding: identity` 禁用 OkHttp 透明 gzip 与任何中间层压缩，
     *   防止 SSE 被缓冲成"一次性到达"；
     * - 收集结束/取消时通过 invokeOnCompletion 立刻 call.cancel() 断开连接。
     */
    fun chat(message: String, conversationId: String?): Flow<ChatEvent> = flow {
        val body = JSONObject().apply {
            put("message", message)
            conversationId?.let { put("conversationId", it) }
        }.toString().toRequestBody(JSON)
        val req = Request.Builder()
            .url("${Session.apiBase}/agents/default/chat")
            .post(body)
            .header("Accept", "text/event-stream")
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache")
            .build()
        val call = sseClient.newCall(req)
        val startedAt = System.currentTimeMillis()

        // 收集方离开页面/取消收集时，立即断开底层连接（解除阻塞读）
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }

        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit(
                        ChatEvent.Error(
                            errorMessage(resp.body?.string(), "请求失败 (HTTP ${resp.code})"),
                        ),
                    )
                    return@use
                }
                val source = resp.body?.source()
                if (source == null) {
                    emit(ChatEvent.Error("空响应"))
                    return@use
                }
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.startsWith(":") -> emit(ChatEvent.Heartbeat)
                        else -> parseSseLine(line)?.let { emit(it) }
                    }
                }
            }
        } catch (e: Exception) {
            if (!call.isCanceled()) {
                val elapsed = (System.currentTimeMillis() - startedAt) / 1000
                emit(
                    ChatEvent.Error(
                        "${e.javaClass.simpleName}: ${e.message ?: "网络错误"}（${elapsed}s）",
                    ),
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun parseSseLine(line: String): ChatEvent? {
        if (!line.startsWith("data:")) return null
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty()) return null
        return runCatching {
            val o = JSONObject(payload)
            when (o.optString("type")) {
                "conversation" -> ChatEvent.ConversationCreated(o.optString("conversationId"))
                "token" -> ChatEvent.Token(o.optString("content"))
                "reasoning" -> ChatEvent.Reasoning(o.optString("content"))
                "done" -> ChatEvent.Done(o.optString("conversationId"))
                "error" -> ChatEvent.Error(o.optString("message", "服务器错误"))
                else -> null
            }
        }.getOrNull()
    }

    // ---------------------------------------------------------- attachments

    /**
     * 上传聊天附件。POST /api/chat-attachments（multipart: files + conversationId）
     * 返回附件 id。后端按会话注入上下文。
     */
    suspend fun uploadAttachment(
        conversationId: String,
        fileName: String,
        mime: String,
        bytes: ByteArray,
    ): String = withContext(Dispatchers.IO) {
        val part = MultipartBody.Part.createFormData(
            "files",
            fileName,
            bytes.toRequestBody(mime.toMediaType()),
        )
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("conversationId", conversationId)
            .addPart(part)
            .build()
        val req = Request.Builder()
            .url("${Session.apiBase}/chat-attachments")
            .post(body)
            .build()
        val resp = authedClient.newCall(req).execute()
        resp.use {
            if (!it.isSuccessful) throw ApiException(it.code, errorMessage(it.body?.string(), "上传失败"))
            val arr = JSONObject(it.body!!.string()).optJSONArray("attachments")
            if (arr == null || arr.length() == 0) throw ApiException(it.code, "上传响应为空")
            arr.getJSONObject(0).optString("id")
        }
    }

    suspend fun deleteAttachment(id: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${Session.apiBase}/chat-attachments/$id")
            .delete()
            .build()
        authedClient.newCall(req).execute().use { /* 尽力删除，失败不阻塞 */ }
    }

    // --------------------------------------------------------------- skills

    /** 当前用户可用技能（GET /api/skills/mine） */
    suspend fun skills(): List<Skill> = withContext(Dispatchers.IO) {
        val resp = authedGet("/skills/mine")
        resp.use {
            if (!it.isSuccessful) throw ApiException(it.code, errorMessage(it.body?.string(), "获取技能失败"))
            val text = it.body!!.string()
            val arr = runCatching { JSONObject(text).optJSONArray("skills") }.getOrNull() ?: JSONArray(text)
            (0 until arr.length()).map { Skill.fromJson(arr.getJSONObject(it)) }
        }
    }

    // -------------------------------------------------------------- helpers

    private fun authedGet(path: String): Response =
        authedClient.newCall(
            Request.Builder().url("${Session.apiBase}$path").get().build(),
        ).execute()

    /** Nest 异常体 {"message"|"error": "..."}，取不出来就用兜底文案 */
    private fun errorMessage(body: String?, fallback: String): String =
        runCatching {
            val o = JSONObject(body!!)
            o.optString("message").ifBlank { o.optString("error") }.ifBlank { fallback }
        }.getOrDefault(fallback)
}

/** 拦截器回调的刷新入口（synchronized 单飞，避免并发 401 重复刷新） */
private object SessionManager {
    private val lock = Any()

    fun refreshTokensBlocking(): Boolean = synchronized(lock) {
        val refresh = Session.refreshToken() ?: return false
        return runCatching {
            val body = JSONObject().put("refreshToken", refresh).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("${Session.apiBase}/auth/refresh")
                .post(body)
                .build()
            val resp = OkHttpClient().newCall(req).execute()
            resp.use {
                if (!it.isSuccessful) {
                    Session.logout()
                    return false
                }
                val o = JSONObject(it.body!!.string())
                // 后端只轮换 accessToken，refreshToken 不变
                Session.saveTokens(o.getString("accessToken"), refresh)
            }
            true
        }.getOrDefault(false)
    }
}
