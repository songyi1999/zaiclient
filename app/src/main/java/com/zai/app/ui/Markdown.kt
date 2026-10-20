package com.zai.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

/** 行内样式：`code`、**bold**、*italic*、~~strike~~ */
fun markdownInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val s = text
    while (i < s.length) {
        when {
            s.startsWith("`", i) -> {
                val end = s.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x14808080))) {
                        append(s.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
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

@Composable
fun MarkdownText(content: String, modifier: Modifier = Modifier) {
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
                        val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
                        val bullet = Regex("^\\s*[-*•]\\s+(.*)$").find(line)
                        when {
                            heading != null -> {
                                val level = heading.groupValues[1].length.coerceAtMost(3)
                                Text(
                                    text = markdownInline(heading.groupValues[2]),
                                    fontSize = (18 - level * 2).sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                                )
                            }

                            bullet != null -> {
                                Text(
                                    text = buildAnnotatedString {
                                        append("•  ")
                                        append(markdownInline(bullet.groupValues[1]))
                                    },
                                    modifier = Modifier.padding(start = 4.dp, bottom = 1.dp),
                                )
                            }

                            line.isEmpty() -> androidx.compose.foundation.layout.Spacer(
                                modifier = Modifier.padding(2.dp),
                            )

                            else -> Text(
                                text = markdownInline(line),
                                modifier = Modifier.padding(bottom = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
