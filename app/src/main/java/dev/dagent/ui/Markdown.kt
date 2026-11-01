package dev.dagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface Block {
    data class Para(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Bullet(val indent: Int, val marker: String, val text: String) : Block
    data class Code(val lang: String, val text: String) : Block
    data class Quote(val text: String) : Block
    data class Table(val rows: List<List<String>>) : Block
    data object Rule : Block
}

private fun parse(md: String): List<Block> {
    val out = mutableListOf<Block>()
    val lines = md.replace("\r", "").lines()
    var i = 0
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += Block.Para(para.toString().trim()); para.setLength(0) }
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        when {
            t.startsWith("```") -> {
                flush()
                val lang = t.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) { code.append(lines[i]).append('\n'); i++ }
                out += Block.Code(lang, code.toString().trimEnd())
            }
            t.isEmpty() -> flush()
            Regex("^#{1,6}\\s").containsMatchIn(t) -> { flush(); out += Block.Heading(t.takeWhile { it == '#' }.length, t.trimStart('#').trim()) }
            t == "---" || t == "***" || t == "___" -> { flush(); out += Block.Rule }
            Regex("^([-*+]|\\d+[.)])\\s+").containsMatchIn(t) -> {
                flush()
                val m = Regex("^([-*+]|\\d+[.)])\\s+(.*)").find(t)!!
                val marker = m.groupValues[1].let { if (it in setOf("-", "*", "+")) "•" else it }
                out += Block.Bullet((line.length - line.trimStart().length) / 2, marker, m.groupValues[2])
            }
            t.startsWith(">") -> { flush(); out += Block.Quote(t.trimStart('>').trim()) }
            t.startsWith("|") && i + 1 < lines.size && Regex("^\\|?\\s*:?-{2,}").containsMatchIn(lines[i + 1].trim()) -> {
                flush()
                val rows = mutableListOf<List<String>>()
                rows += cells(t)
                i += 2
                while (i < lines.size && lines[i].trim().startsWith("|")) { rows += cells(lines[i].trim()); i++ }
                out += Block.Table(rows)
                continue
            }
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
        }
        i++
    }
    flush()
    return out
}

private fun cells(row: String) = row.trim().trim('|').split('|').map { it.trim() }

/** Bold, italics, inline code, links and bare URLs. */
fun inline(text: String, accent: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    val rx = Regex("(\\*\\*[^*]+\\*\\*)|(__[^_]+__)|(`[^`]+`)|(\\[[^]]+]\\([^)\\s]+\\))|(https?://[^\\s)<>]+)|(\\*[^*\\s][^*]*\\*)|(~~[^~]+~~)")
    var last = 0
    val linkStyle = TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline))
    for (m in rx.findAll(text)) {
        append(text.substring(last, m.range.first))
        val s = m.value
        when {
            s.startsWith("**") || s.startsWith("__") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(2, s.length - 2)) }
            s.startsWith("`") -> withStyle(SpanStyle(fontFamily = Mono, background = codeBg, fontSize = 14.sp)) { append(" " + s.trim('`') + " ") }
            s.startsWith("[") -> {
                val label = s.substring(1, s.indexOf("]("))
                val url = s.substring(s.indexOf("](") + 2, s.length - 1)
                if (url.startsWith("http")) withLink(LinkAnnotation.Url(url, linkStyle)) { append(label) } else append(label)
            }
            s.startsWith("http") -> withLink(LinkAnnotation.Url(s, linkStyle)) { append(s) }
            s.startsWith("~~") -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(s.substring(2, s.length - 2)) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(1, s.length - 1)) }
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onBackground) {
    val p = LocalPalette.current
    val blocks = remember(text) { parse(text) }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (b in blocks) when (b) {
                is Block.Para -> Text(inline(b.text, p.accent, p.surface2), color = color, style = MaterialTheme.typography.bodyLarge)
                is Block.Heading -> Text(
                    inline(b.text, p.accent, p.surface2), color = color, fontWeight = FontWeight.Bold,
                    fontSize = when (b.level) { 1 -> 22.sp; 2 -> 19.sp; else -> 17.sp },
                    modifier = Modifier.padding(top = 4.dp),
                )
                is Block.Bullet -> Row(Modifier.padding(start = (b.indent * 14).dp)) {
                    Text(b.marker, color = p.accent, modifier = Modifier.width(if (b.marker == "•") 16.dp else 26.dp), style = MaterialTheme.typography.bodyLarge)
                    Text(inline(b.text, p.accent, p.surface2), color = color, style = MaterialTheme.typography.bodyLarge)
                }
                is Block.Code -> Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0F1014)).padding(12.dp),
                ) {
                    if (b.lang.isNotBlank()) Text(b.lang, color = p.muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
                    Text(b.text, fontFamily = Mono, fontSize = 13.sp, color = Color(0xFFE6E6E6), modifier = Modifier.horizontalScroll(rememberScrollState()))
                }
                is Block.Quote -> Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.width(3.dp).height(22.dp).background(p.accent))
                    Text(inline(b.text, p.accent, p.surface2), color = p.muted, modifier = Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyLarge)
                }
                is Block.Table -> Column(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(p.surface).horizontalScroll(rememberScrollState()).padding(8.dp),
                ) {
                    val widths = (0 until (b.rows.maxOfOrNull { it.size } ?: 0)).map { c -> (b.rows.maxOfOrNull { it.getOrNull(c)?.length ?: 0 } ?: 0).coerceIn(4, 28) }
                    b.rows.forEachIndexed { r, row ->
                        Row {
                            row.forEachIndexed { c, cell ->
                                Text(
                                    inline(cell, p.accent, p.surface2), color = color, fontSize = 14.sp,
                                    fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.width((widths.getOrElse(c) { 8 } * 8 + 16).dp).padding(vertical = 4.dp, horizontal = 4.dp),
                                )
                            }
                        }
                    }
                }
                Block.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(p.line))
            }
        }
    }
}
