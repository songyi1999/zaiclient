package com.zai.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zai.app.auth.Session
import com.zai.app.net.Updater
import com.zai.app.screens.ChatScreen
import com.zai.app.screens.ConversationsScreen
import com.zai.app.screens.LoginScreen
import com.zai.app.screens.SettingsScreen
import com.zai.app.ui.UpdateDialog
import com.zai.app.ui.ZaiTheme
import com.zai.app.ui.silentCheck
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZaiTheme {
                AppRoot()
            }
        }
    }
}

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val startDestination = if (Session.loggedIn) "chats" else "login"

    // 启动时后台检查更新（每次冷启动一次，失败静默），有新版弹窗提醒
    var updateRelease by remember { mutableStateOf<Updater.Release?>(null) }
    LaunchedEffect(Unit) {
        updateRelease = silentCheck()
    }

    NavHost(navController = nav, startDestination = startDestination) {
        composable("login") {
            LoginScreen(onLoginSuccess = {
                nav.navigate("chats") {
                    popUpTo("login") { inclusive = true }
                }
            })
        }

        composable("chats") {
            ConversationsScreen(
                onOpenChat = { id, title ->
                    nav.navigate("chat/$id?title=${encodeTitle(title)}")
                },
                onOpenSettings = { nav.navigate("settings") },
            )
        }

        composable(
            route = "chat/{conversationId}?title={title}",
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType },
                navArgument("title") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val id = entry.arguments?.getString("conversationId") ?: "new"
            val title = entry.arguments?.getString("title")?.let { URLDecoder.decode(it, "UTF-8") }
            ChatScreen(
                conversationIdArg = id,
                titleArg = title,
                onBack = { nav.popBackStack() },
            )
        }

        composable("settings") {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onLogout = {
                    nav.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
    }

    updateRelease?.let { release ->
        UpdateDialog(release = release, onDismiss = { updateRelease = null })
    }
}

/** 会话标题放进路由参数前先编码，避免 / 和特殊字符破坏路由 */
fun encodeTitle(title: String): String = URLEncoder.encode(title, "UTF-8")
