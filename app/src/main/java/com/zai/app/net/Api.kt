package com.zai.app.net

import com.zai.app.auth.Session
import com.zai.app.data.Conversation
import com.zai.app.data.Message
import com.zai.app.data.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
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
        // SSE 长连接：读超时必须大于后端 15s 心跳间隔，留足余量
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
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

    // ----------------------------------------------------------------- chat

    /**
     * SSE 流式对话。POST /api/agents/default/chat
     * 事件流见后端 agent.controller：conversation → token/reasoning* → done。
     * 取消收集（离开页面）会 cancel 底层 OkHttp call，立即断开连接。
     */
    fun chat(message: String, conversationId: String?): Flow<ChatEvent> = channelFlow {
        val body = JSONObject().apply {
            put("message", message)
            conversationId?.let { put("conversationId", it) }
        }.toString().toRequestBody(JSON)
        val req = Request.Builder()
            .url("${Session.apiBase}/agents/default/chat")
            .post(body)
            .build()
        val call = authedClient.newCall(req)

        launch {
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        send(ChatEvent.Error(errorMessage(resp.body?.string(), "请求失败 (HTTP ${resp.code})")))
                        return@launch
                    }
                    val source = resp.body?.source() ?: run {
                        send(ChatEvent.Error("空响应"))
                        return@launch
                    }
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        parseSseLine(line)?.let { send(it) }
                    }
                }
            } catch (e: Exception) {
                // call.cancel() 触发的 IOException 是正常取消，不算错误
                if (!call.isCanceled()) {
                    send(ChatEvent.Error(e.message ?: "网络错误"))
                }
            } finally {
                close()
            }
        }
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun parseSseLine(line: String): ChatEvent? {
        // ": heartbeat" 注释行与空行直接忽略
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
