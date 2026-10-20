package com.zai.app.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.zai.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 应用自更新（OTA）：检查 GitHub Releases → 下载 APK → 拉起系统安装器。
 * 下载文件放 cacheDir/updates，经 FileProvider 授权给系统安装器读取。
 */
object Updater {

    private const val LATEST_API = "https://api.github.com/repos/songyi1999/zaiclient/releases/latest"

    data class Release(
        val tag: String,
        val version: String,
        val name: String,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
    )

    /** 下载/查更新共用的无鉴权客户端；读超时放大到 60s 适应慢速网络 */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun fetchLatest(): Release = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(LATEST_API)
            .header("Accept", "application/vnd.github+json")
            .build()
        val resp = client.newCall(req).execute()
        resp.use {
            if (!it.isSuccessful) throw IOException("获取版本信息失败 (HTTP ${it.code})")
            val o = JSONObject(it.body!!.string())
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) {
                throw IOException("没有正式发布版本")
            }
            val tag = o.optString("tag_name")
            val assets = o.optJSONArray("assets") ?: throw IOException("发布缺少安装包")
            val apk = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { a -> a.optString("name").endsWith(".apk") }
                ?: throw IOException("发布缺少安装包")
            Release(
                tag = tag,
                version = tag.removePrefix("v"),
                name = o.optString("name"),
                notes = o.optString("body"),
                apkUrl = apk.optString("browser_download_url"),
                apkSize = apk.optLong("size"),
            )
        }
    }

    /** 逐段比较版本号：v0.10.0 > v0.9.1 > v0.9 */
    fun isNewer(remoteVersion: String, current: String = BuildConfig.VERSION_NAME): Boolean {
        val r = remoteVersion.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val c = current.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    /** 流式下载 APK 到 cacheDir/updates，onProgress 回调百分比 0-100 */
    suspend fun download(
        context: Context,
        release: Release,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "ZaiClient-${release.version}.apk")
        val tmp = File(dir, "download.tmp")
        val req = Request.Builder().url(release.apkUrl).build()
        val resp = client.newCall(req).execute()
        resp.use {
            if (!it.isSuccessful) throw IOException("下载失败 (HTTP ${it.code})")
            val body = it.body ?: throw IOException("空响应")
            val total = body.contentLength().takeIf { n -> n > 0 } ?: release.apkSize
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = body.byteStream().read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress((done * 100 / total).toInt().coerceAtMost(100))
                }
            }
            if (tmp.length() == 0L) throw IOException("下载内容为空")
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IOException("保存安装包失败")
        }
        target
    }

    /** 是否已获得"安装未知应用"授权（Android 8+ 必需） */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** 跳到系统的"允许此来源安装"设置页 */
    fun requestInstallPermission(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** 拉起系统安装器安装 APK */
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
