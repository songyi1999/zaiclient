package com.zai.app.net

import com.zai.app.auth.Session
import com.zai.app.data.Conversation
import com.zai.app.data.Message
import com.zai.app.data.Skill
import com.zai.app.data.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 聊天 SSE 事件（与后端 agent.controller / 网页端 sse.ts 的 wire format 一一对应） */
sealed class ChatEvent {
    /** SSE 连接已建立（收到响应头） */
    object Connected : ChatEvent()
    data class ConversationCreated(val conversationId: String) : ChatEvent()
    data class Token(val content: String) : ChatEvent()

    /** 服务端要求用 content 整体替换已流出的内容（清理泄漏的 JSON 等） */
    data class ReplaceContent(val content: String) : ChatEvent()
    data class Reasoning(val content: String) : ChatEvent()
    data class Done(val conversationId: String) : ChatEvent()
    data class Error(val message: String) : ChatEvent()
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
     *
     * 使用 OkHttp 官方 okhttp3-sse 模块（EventSources 工厂支持 POST 请求），
     * SSE 协议解析（事件切分/多行 data 拼接/注释行忽略）全部交给官方实现。
     * 事件语义与网页端 sse.ts 一一对应；流结束但没收到 done 时显式报错。
     * 兜底：ChatViewModel 的轮询（网络缓冲流式时的保底方案）。
     */
    fun chat(message: String, conversationId: String?): Flow<ChatEvent> = callbackFlow {
        val body = JSONObject().apply {
            put("message", message)
            conversationId?.let { put("conversationId", it) }
        }.toString().toRequestBody(JSON)
        val req = Request.Builder()
            .url("${Session.apiBase}/agents/default/chat")
            .post(body)
            .build()

        var sawDone = false
        var sawAnyEvent = false

        val es = EventSources.createFactory(sseClient).newEventSource(
            req,
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    trySend(ChatEvent.Connected)
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    val json = runCatching { JSONObject(data) }.getOrNull()
                    if (json == null) {
                        trySend(ChatEvent.Error("收到无法解析的事件: ${data.take(120)}"))
                        return
                    }
                    val ev = parseEvent(json) ?: return
                    sawAnyEvent = true
                    if (ev is ChatEvent.Done) sawDone = true
                    trySend(ev)
                }

                override fun onClosed(eventSource: EventSource) {
                    if (!sawDone) {
                        trySend(
                            ChatEvent.Error(
                                if (sawAnyEvent) "连接被中断，回复不完整"
                                else "服务器关闭了连接，未收到任何数据",
                            ),
                        )
                    }
                    close()
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    val msg = when {
                        response != null && !response.isSuccessful ->
                            errorMessage(response.body?.string(), "请求失败 (HTTP ${response.code})")

                        else -> "${t?.javaClass?.simpleName ?: "网络错误"}: ${t?.message ?: ""}"
                    }
                    trySend(ChatEvent.Error(msg))
                    close()
                }
            },
        )

        awaitClose { es.cancel() }
    }.flowOn(Dispatchers.IO)

    /** 与网页端 sse.ts 的 switch 一一对应（含网页端处理的全部事件类型） */
    private fun parseEvent(o: JSONObject): ChatEvent? = when (o.optString("type")) {
        "conversation" -> ChatEvent.ConversationCreated(o.optString("conversationId"))
        "token" -> ChatEvent.Token(o.optString("content"))
        "replace_content" -> ChatEvent.ReplaceContent(o.optString("content"))
        "reasoning" -> ChatEvent.Reasoning(o.optString("content"))
        "done" -> ChatEvent.Done(o.optString("conversationId"))
        "error" -> ChatEvent.Error(o.optString("message", "服务器错误"))
        // hitl_request / document_updated / template_updated：网页端有专门 UI，
        // App 暂无——先忽略不报错
        "hitl_request", "document_updated", "template_updated" -> null
        // 无 type 字段的事件（如 user 消息回执）两端都忽略
        else -> null
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

    /** 模板库（GET /api/templates） */
    suspend fun templates(): List<com.zai.app.data.Template> = withContext(Dispatchers.IO) {
        val resp = authedGet("/templates")
        resp.use {
            if (!it.isSuccessful) throw ApiException(it.code, errorMessage(it.body?.string(), "获取模板失败"))
            val arr = JSONArray(it.body!!.string())
            (0 until arr.length()).map { com.zai.app.data.Template.fromJson(arr.getJSONObject(it)) }
        }
    }

    /** 知识库语义搜索（POST /api/knowledge/search） */
    suspend fun knowledgeSearch(query: String): List<com.zai.app.data.KnowledgeResult> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("query", query).put("limit", 20).toString().toRequestBody(JSON)
            val req = Request.Builder()
                .url("${Session.apiBase}/knowledge/search")
                .post(body)
                .build()
            val resp = authedClient.newCall(req).execute()
            resp.use {
                if (!it.isSuccessful) throw ApiException(it.code, errorMessage(it.body?.string(), "搜索失败"))
                val arr = JSONArray(it.body!!.string())
                (0 until arr.length()).map { com.zai.app.data.KnowledgeResult.fromJson(arr.getJSONObject(it)) }
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
