package com.zai.app.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zai.app.data.User
import org.json.JSONObject

/**
 * 登录态与服务器配置的唯一来源。Token 持久化在 SharedPreferences，
 * loggedIn / user 是 Compose 状态，驱动导航在登录页与主界面间切换。
 */
object Session {
    private lateinit var prefs: SharedPreferences

    var loggedIn by mutableStateOf(false)
        private set
    var user by mutableStateOf<User?>(null)
        private set
    var serverUrl by mutableStateOf("")
        private set

    /** 服务器地址变更（未登录时在登录页修改、登录后在设置页修改都会走到这里） */
    var serverUrlChanged by mutableStateOf(0)
        private set

    private val lock = Any()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("zai_session", Context.MODE_PRIVATE)
        serverUrl = prefs.getString("serverUrl", DEFAULT_SERVER) ?: DEFAULT_SERVER
        val access = prefs.getString("accessToken", null)
        val refresh = prefs.getString("refreshToken", null)
        if (access != null && refresh != null) {
            loggedIn = true
            user = prefs.getString("userJson", null)?.let { runCatching { User.fromJson(JSONObject(it)) }.getOrNull() }
        }
    }

    val apiBase: String get() = serverUrl.trimEnd('/') + "/api"

    fun accessToken(): String? = prefs.getString("accessToken", null)

    fun refreshToken(): String? = prefs.getString("refreshToken", null)

    fun updateServerUrl(url: String) {
        val normalized = url.trim().trimEnd('/')
        if (normalized == serverUrl.trimEnd('/')) return
        prefs.edit().putString("serverUrl", normalized).apply()
        serverUrl = normalized
        serverUrlChanged++
    }

    fun saveLogin(access: String, refresh: String, user: User?) {
        prefs.edit()
            .putString("accessToken", access)
            .putString("refreshToken", refresh)
            .putString("userJson", user?.let { u ->
                JSONObject().apply {
                    put("id", u.id); put("username", u.username)
                    u.displayName?.let { put("displayName", it) }
                    u.email?.let { put("email", it) }
                    u.role?.let { put("role", it) }
                    u.department?.let { put("department", it) }
                }.toString()
            })
            .apply()
        this.user = user
        loggedIn = true
    }

    fun saveTokens(access: String, refresh: String) {
        prefs.edit()
            .putString("accessToken", access)
            .putString("refreshToken", refresh)
            .apply()
    }

    fun logout() {
        prefs.edit()
            .remove("accessToken")
            .remove("refreshToken")
            .remove("userJson")
            .apply()
        user = null
        loggedIn = false
    }

    // ------------------------------------------------- 记住密码（登录页勾选）

    /** 退出登录不清除记住的账号密码，方便下次直接登录 */
    val rememberedUsername: String?
        get() = prefs.getString("rememberedUsername", null)

    val rememberedPassword: String?
        get() = prefs.getString("rememberedPassword", null)

    fun saveRemembered(username: String, password: String) {
        prefs.edit()
            .putString("rememberedUsername", username)
            .putString("rememberedPassword", password)
            .apply()
    }

    fun clearRemembered() {
        prefs.edit()
            .remove("rememberedUsername")
            .remove("rememberedPassword")
            .apply()
    }

    const val DEFAULT_SERVER = "http://zai.xinpanmen.com:9002"
}
