package com.zai.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zai.app.auth.Session
import com.zai.app.net.Updater
import com.zai.app.screens.ChatHomeScreen
import com.zai.app.screens.ChatScreen
import com.zai.app.screens.ConversationsScreen
import com.zai.app.screens.DiscoverScreen
import com.zai.app.screens.KnowledgeScreen
import com.zai.app.screens.LoginScreen
import com.zai.app.screens.MeScreen
import com.zai.app.screens.SkillsScreen
import com.zai.app.screens.TemplatesScreen
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

private data class Tab(val route: String, val label: String, val icon: ImageVector)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val startDestination = if (Session.loggedIn) "home" else "login"

    // 启动时后台检查更新（每次冷启动一次，失败静默），有新版弹窗提醒
    var updateRelease by remember { mutableStateOf<Updater.Release?>(null) }
    LaunchedEffect(Unit) {
        updateRelease = silentCheck()
    }

    val tabs = listOf(
        Tab("home", "对话", Icons.AutoMirrored.Filled.Chat),
        Tab("records", "记录", Icons.Filled.History),
        Tab("discover", "发现", Icons.Filled.Explore),
        Tab("me", "我的", Icons.Filled.Person),
    )
    val tabRoutes = tabs.map { it.route }.toSet()

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val goTab: (String) -> Unit = { route ->
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // WorkBuddy 式外观：底板色上浮一块顶部圆角的内容面板，底部导航与之同色
    val backdrop = if (Session.themeMode == "dark") {
        com.zai.app.ui.PanelBackdropDark
    } else {
        com.zai.app.ui.PanelBackdropLight
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(backdrop),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                NavHost(
                    navController = nav,
                    startDestination = startDestination,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
                ) {
                    composable("login") {
                        LoginScreen(onLoginSuccess = {
                            nav.navigate("home") {
                                popUpTo("login") { inclusive = true }
                            }
                        })
                    }

                    composable("home") {
                        ChatHomeScreen(
                            onOpenChat = { id, title ->
                                nav.navigate("chat/$id?title=${encodeTitle(title)}")
                            },
                            onSendMessage = { prompt ->
                                nav.navigate("chat/new?prompt=${encodeTitle(prompt)}")
                            },
                            onStartWithDraft = { nav.navigate("chat/new") },
                        )
                    }

                    composable("records") {
                        ConversationsScreen(
                            onOpenChat = { id, title ->
                                nav.navigate("chat/$id?title=${encodeTitle(title)}")
                            },
                        )
                    }

                    composable("discover") {
                        DiscoverScreen(
                            onOpenTab = { route -> goTab(route) },
                            onOpenSub = { route -> nav.navigate(route) },
                        )
                    }

                    composable("skills") {
                        SkillsScreen(
                            onBack = { nav.popBackStack() },
                            onUseSkill = {
                                nav.navigate("chat/new") {
                                    popUpTo("home")
                                }
                            },
                        )
                    }

                    composable("knowledge") {
                        KnowledgeScreen(onBack = { nav.popBackStack() })
                    }

                    composable("templates") {
                        TemplatesScreen(
                            onBack = { nav.popBackStack() },
                            onUseTemplate = {
                                nav.navigate("chat/new") {
                                    popUpTo("home")
                                }
                            },
                        )
                    }

                    composable("me") {
                        MeScreen(onLogout = {
                            nav.navigate("login") {
                                popUpTo(0) { inclusive = true }
                            }
                        })
                    }

                    composable(
                        route = "chat/{conversationId}?title={title}&prompt={prompt}",
                        arguments = listOf(
                            navArgument("conversationId") { type = NavType.StringType },
                            navArgument("title") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                            navArgument("prompt") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                        ),
                    ) { entry ->
                        val id = entry.arguments?.getString("conversationId") ?: "new"
                        val title = entry.arguments?.getString("title")
                            ?.let { URLDecoder.decode(it, "UTF-8") }
                        val prompt = entry.arguments?.getString("prompt")
                            ?.let { URLDecoder.decode(it, "UTF-8") }
                        ChatScreen(
                            conversationIdArg = id,
                            titleArg = title,
                            promptArg = prompt,
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
            }

            if (currentRoute in tabRoutes) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                if (currentRoute != tab.route) goTab(tab.route)
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        }
    }

    updateRelease?.let { release ->
        UpdateDialog(release = release, onDismiss = { updateRelease = null })
    }
}

/** 标题/提示语放进路由参数前先编码，避免 / 和特殊字符破坏路由 */
fun encodeTitle(text: String): String = URLEncoder.encode(text, "UTF-8")
