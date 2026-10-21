package com.zai.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.zai.app.auth.Session

/**
 * 轻量 Markdown 渲染：代码块（``` 围栏）、行内代码、粗体、斜体、
 * 标题（#~###）、无序列表（- / *）。覆盖 AI 回复的主流排版，
 * 不追求完整 CommonMark。
 */

private sealed class Block {
    data class Code(val lines: List<String>, val lang: String) : Block()
    data class Paragraph(val lines: List<String>) : Block()
}

private fun parseMarkdown(content: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val para = mutableListOf<String>()
    var i = 0
    val lines = content.lines()

    fun flushPara() {
        if (para.isNotEmpty()) {
            blocks += Block.Paragraph(para.toList())
            para.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        if (line.trimStart().startsWith("```")) {
            flushPara()
            val lang = line.trimStart().removePrefix("```").trim()
            val code = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                code += lines[i]
                i++
            }
            i++ // 跳过收尾 ```
            blocks += Block.Code(code, lang)
        } else {
            para += line
            i++
        }
    }
    flushPara()
    return blocks
}

/** 行内样式：`code`、**bold**、*italic*、~~strike~~、[label](url) 与可点击的 http(s)/相对路径链接 */
fun markdownInline(text: String, linkColor: Color = Color.Unspecified): AnnotatedString = buildAnnotatedString {
    fun linkStyles() = if (linkColor == Color.Unspecified) null
    else TextLinkStyles(
        SpanStyle(
            color = linkColor,
            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
        ),
    )

    fun appendLink(url: String, label: String) {
        val abs = if (url.startsWith("http")) url else Session.serverUrl.trimEnd('/') + url
        withLink(LinkAnnotation.Url(abs, linkStyles())) { append(label.ifBlank { url }) }
    }

    var i = 0
    val s = text
    while (i < s.length) {
        when {
            // [label](url) 标准链接语法
            s[i] == '[' -> {
                val close = s.indexOf("](", i)
                val done = if (close > i) s.indexOf(')', close) else -1
                if (close > i && done > close) {
                    val label = s.substring(i + 1, close)
                    val url = s.substring(close + 2, done)
                    if (url.startsWith("http") || url.startsWith("/")) {
                        appendLink(url, label)
                        i = done + 1
                    } else {
                        append(s[i]); i++
                    }
                } else {
                    append(s[i]); i++
                }
            }

            // 反引号行内代码：内容若是服务端路径（如 /api/slides/xxx）则渲染成链接
            s.startsWith("`", i) -> {
                val end = s.indexOf('`', i + 1)
                if (end > i) {
                    val code = s.substring(i + 1, end)
                    val labeled = serverPathLabel(code)
                    if (labeled != null) {
                        appendLink(code, labeled)
                    } else {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x14808080))) {
                            append(code)
                        }
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }

            (s.startsWith("http://", i) || s.startsWith("https://", i)) -> {
                // 吃掉从当前位置开始的整段 URL，去掉粘在末尾的标点
                val m = URL_AT_START.find(s.substring(i))
                var url = m?.value ?: s.substring(i)
                url = url.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '）', '】', '」', '。', '，', '、')
                if (url.length < 12) {
                    append(s[i]); i++
                } else {
                    appendLink(url, url)
                    i += url.length
                }
            }

            // 裸相对路径（/api/...、/share/...、/images/...）也做成链接
            s[i] == '/' && RELATIVE_PATH_AT_START.containsMatchIn(s.substring(i)) -> {
                val m = RELATIVE_PATH_AT_START.find(s.substring(i))
                var path = m!!.value
                path = path.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '）', '】', '」', '。', '，', '、')
                val labeled = serverPathLabel(path)
                appendLink(path, labeled ?: path)
                i += path.length
            }

            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }
                    i = end + 2
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("~~", i) -> {
                val end = s.indexOf("~~", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)) {
                        append(s.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(s[i]); i++
                }
            }
            s[i] == '*' && i + 1 < s.length && s[i + 1] != '*' && s[i + 1] != ' ' -> {
                val end = s.indexOf('*', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }
            else -> {
                append(s[i]); i++
            }
        }
    }
}

private val URL_AT_START = Regex("^https?://[^\\s<>\"'\\[\\]()]+")

/** 相对路径形状：/word/word… 至少两段（避免误伤普通文字里的斜杠） */
private val RELATIVE_PATH_AT_START = Regex("^/[a-zA-Z][\\w\\-]*/[\\w\\-./%~]+")

/**
 * 服务端路径 → 友好链接文案（对齐网页端 MarkdownRenderer 的预处理）。
 * 不是可链接的路径时返回 null。
 */
private fun serverPathLabel(path: String): String? = when {
    Regex("^/api/slides/[a-f0-9-]+/download$", RegexOption.IGNORE_CASE).matches(path) -> "📥 下载 PPT"
    Regex("^/api/slides/[a-f0-9-]+$", RegexOption.IGNORE_CASE).matches(path) -> "🔗 在线查看 PPT"
    Regex("^/api/exports/[\\w\\-.]+/download$").matches(path) -> "📄 下载文件"
    Regex("^/(api|share|images|videos|documents|templates)/").containsMatchIn(path) -> path
    else -> null
}

/** Markdown 图片语法 ![alt](url)；AI 生成图片的回复里就是这种 */
private val IMAGE_REGEX = Regex("!\\[[^\\]]*\\]\\(([^)\\s]+)\\)")

/** 相对路径（/images/...）补全为服务器绝对地址 */
private fun resolveImageUrl(url: String): String =
    if (url.startsWith("http")) url else Session.serverUrl.trimEnd('/') + url

@Composable
fun MarkdownText(content: String, modifier: Modifier = Modifier) {
    // 点击图片后的全屏预览
    var previewUrl by remember { mutableStateOf<String?>(null) }
    val linkColor = MaterialTheme.colorScheme.primary

    Column(modifier = modifier) {
        parseMarkdown(content).forEach { block ->
            when (block) {
                is Block.Code -> Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = block.lines.joinToString("\n"),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(10.dp),
                    )
                }

                is Block.Paragraph -> Column {
                    block.lines.forEach { raw ->
                        val line = raw.trimEnd()
                        val images = IMAGE_REGEX.findAll(line).toList()
                        if (images.isNotEmpty()) {
                            // 图片行：图片前的文字照常渲染，图片内联展示
                            var cursor = 0
                            images.forEach { m ->
                                val before = line.substring(cursor, m.range.first).trim()
                                if (before.isNotBlank()) {
                                    Text(
                                        text = markdownInline(before, linkColor),
                                        modifier = Modifier.padding(bottom = 1.dp),
                                    )
                                }
                                MarkdownImage(
                                    url = resolveImageUrl(m.groupValues[1]),
                                    onClick = { previewUrl = resolveImageUrl(m.groupValues[1]) },
                                )
                                cursor = m.range.last + 1
                            }
                            val after = line.substring(cursor).trim()
                            if (after.isNotBlank()) {
                                Text(text = markdownInline(after, linkColor), modifier = Modifier.padding(bottom = 1.dp))
                            }
                            return@forEach
                        }
                        val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
                        val bullet = Regex("^\\s*[-*•]\\s+(.*)$").find(line)
                        when {
                            heading != null -> {
                                val level = heading.groupValues[1].length.coerceAtMost(3)
                                Text(
                                    text = markdownInline(heading.groupValues[2], linkColor),
                                    fontSize = (18 - level * 2).sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                                )
                            }

                            bullet != null -> {
                                Text(
                                    text = buildAnnotatedString {
                                        append("•  ")
                                        append(markdownInline(bullet.groupValues[1], linkColor))
                                    },
                                    modifier = Modifier.padding(start = 4.dp, bottom = 1.dp),
                                )
                            }

                            line.isEmpty() -> androidx.compose.foundation.layout.Spacer(
                                modifier = Modifier.padding(2.dp),
                            )

                            else -> Text(
                                text = markdownInline(line, linkColor),
                                modifier = Modifier.padding(bottom = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    previewUrl?.let { url ->
        Dialog(onDismissRequest = { previewUrl = null }) {
            AsyncImage(
                model = url,
                contentDescription = "图片预览",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { previewUrl = null },
            )
        }
    }
}

/** 聊天内联图片：圆角、满宽、点击全屏 */
@Composable
private fun MarkdownImage(url: String, onClick: () -> Unit) {
    AsyncImage(
        model = url,
        contentDescription = "AI 生成图片",
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    )
}
