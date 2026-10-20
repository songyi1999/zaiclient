package com.zai.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zai.app.net.Updater
import kotlinx.coroutines.launch
import java.io.File

/**
 * 新版本弹窗：展示版本说明 → 下载（带进度条）→ 授权并拉起系统安装器。
 * 启动时的自动检查和设置页的手动"查看最新版本"共用此弹窗。
 */
@Composable
fun UpdateDialog(
    release: Updater.Release,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var apkFile by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun startInstall() {
        val file = apkFile ?: return
        if (!Updater.canInstall(context)) {
            // Android 8+ 首次安装需用户授予"允许此来源安装"，授权后回来再点一次安装
            Updater.requestInstallPermission(context)
            return
        }
        Updater.install(context, file)
    }

    fun startDownload() {
        if (downloading) return
        apkFile?.let { startInstall(); return }
        downloading = true
        error = null
        progress = 0
        scope.launch {
            try {
                apkFile = Updater.download(context, release) { progress = it }
                startInstall()
            } catch (e: Exception) {
                error = e.message ?: "下载失败"
            } finally {
                downloading = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("发现新版本 v${release.version}") },
        text = {
            Column {
                Text(
                    release.notes.ifBlank { "问题修复与体验优化" },
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (release.apkSize > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "安装包大小 ${"%.1f".format(release.apkSize / 1048576.0)} MB",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (downloading) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "正在下载… $progress%",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { startDownload() },
                enabled = !downloading,
            ) {
                Text(
                    when {
                        apkFile != null -> "安装"
                        downloading -> "下载中…"
                        else -> "立即更新"
                    },
                )
            }
        },
        dismissButton = {
            if (!downloading) {
                TextButton(onClick = onDismiss) { Text(if (apkFile != null) "关闭" else "下次再说") }
            }
        },
    )
}

/** 启动时静默检查；有新版本返回 Release，否则返回 null。失败静默（不打扰用户）。 */
suspend fun silentCheck(): Updater.Release? = runCatching {
    val latest = Updater.fetchLatest()
    if (Updater.isNewer(latest.version)) latest else null
}.getOrNull()
