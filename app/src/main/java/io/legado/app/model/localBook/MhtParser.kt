package io.legado.app.model.localBook

import io.legado.app.utils.LogUtils
import org.apache.james.mime4j.dom.Entity
import org.apache.james.mime4j.dom.Multipart
import org.apache.james.mime4j.dom.TextBody
import org.apache.james.mime4j.message.DefaultMessageBuilder
import org.apache.james.mime4j.stream.MimeConfig
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * MHT / MHTML 单文件网页解析
 *
 * MIME 结构交给成熟的 apache-mime4j 处理(兼容 quoted-printable / base64 /
 * 畸形 boundary / GBK 等 charset), 这里只负责:
 * 1. 找出 text/html 分段
 * 2. 用 Jsoup 把 HTML 转成纯文本
 * 3. mime4j 解析失败时按纯文本兜底
 */
object MhtParser {

    private const val TAG = "MhtParser"

    fun isMht(fileName: String): Boolean =
        fileName.endsWith(".mht", true) || fileName.endsWith(".mhtml", true)

    fun toPlainText(input: InputStream): String {
        val startAt = System.currentTimeMillis()
        val bytes = input.use { it.readBytes() }
        if (bytes.isEmpty()) return ""
        var source = "mime4j"
        var text = parseWithMime4j(bytes)
        if (text == null) {
            source = "fallback"
            text = fallback(bytes)
        }
        LogUtils.d(
            TAG,
            "source=$source, size=${bytes.size / 1024}KB, " +
                    "cost=${System.currentTimeMillis() - startAt}ms, chars=${text.length}"
        )
        // 正文内容不进日志, 只记长度
        LogUtils.d(TAG, "parsed ${text.length} chars")
        return text
    }

    // ---------------------------------------------------------------- mime4j

    private fun parseWithMime4j(bytes: ByteArray): String? {
        val t0 = System.currentTimeMillis()
        val message = runCatching {
            val builder = DefaultMessageBuilder()
            builder.setMimeEntityConfig(MimeConfig.PERMISSIVE)
            builder.parseMessage(ByteArrayInputStream(bytes))
        }.getOrElse {
            LogUtils.d(TAG, "mime4j parse failed: ${it.message}")
            return null
        }
        val mimeCost = System.currentTimeMillis() - t0
        val html = findHtml(message) ?: findPlainText(message) ?: return null
        val t1 = System.currentTimeMillis()
        val text = htmlToText(html)
        LogUtils.d(
            TAG,
            "mime4j=${mimeCost}ms, html2text=${System.currentTimeMillis() - t1}ms, " +
                    "html=${html.length} chars"
        )
        return text.takeIf { it.isNotBlank() && !looksLikeMime(it) }
    }

    /**
     * 递归找 text/html 分段, mime4j 已按 charset 解码内容
     */
    private fun findHtml(entity: Entity): String? {
        // 先看类型再取 body: 访问 body 会触发该分段解码,
        // 一个网页快照常有上百个 CSS/图片分段, 逐个解码会非常慢
        val mimeType = entity.mimeType.orEmpty()
        if (mimeType.startsWith("multipart/", true)) {
            val body = entity.body as? Multipart ?: return null
            body.bodyParts.forEach { part ->
                findHtml(part)?.let { return it }
            }
            return null
        }
        if (!mimeType.contains("text/html", true)) return null
        return (entity.body as? TextBody)?.let { readTextBody(it) }
    }

    /**
     * 读文本分段.
     * 不能直接用 TextBody.reader: mht 的 part 头常常没写 charset, mime4j 会按
     * US-ASCII 解码, 非 ASCII 字节直接变成替换字符, 信息就丢了.
     * 这里取 transfer-encoding 解码后的原始字节, 再自己按 charset 解码:
     * 优先 part 头里的 charset, 其次 HTML meta 里声明的, 最后 UTF-8.
     */
    private fun readTextBody(textBody: TextBody): String? {
        val bytes = runCatching {
            val out = java.io.ByteArrayOutputStream()
            textBody.writeTo(out)
            out.toByteArray()
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val headerCharset = textBody.mimeCharset
        val charsetName = if (!headerCharset.isNullOrBlank()
            && !headerCharset.equals("us-ascii", true)
            && !headerCharset.equals("iso-8859-1", true)
        ) {
            headerCharset
        } else {
            val head = String(bytes, Charsets.ISO_8859_1).take(4096)
            Regex("(?i)charset\\s*=\\s*[\"']?([\\w-]+)").find(head)?.groupValues?.get(1)
        }
        val charset = runCatching {
            java.nio.charset.Charset.forName(charsetName ?: "utf-8")
        }.getOrDefault(Charsets.UTF_8)
        return String(bytes, charset)
    }

    private fun findPlainText(entity: Entity): String? {
        val mimeType = entity.mimeType.orEmpty()
        if (mimeType.startsWith("multipart/", true)) {
            val body = entity.body as? Multipart ?: return null
            body.bodyParts.forEach { part ->
                findPlainText(part)?.let { return it }
            }
            return null
        }
        if (!mimeType.contains("text/plain", true)) return null
        return (entity.body as? TextBody)?.let { readTextBody(it) }
    }

    // ---------------------------------------------------------------- 兜底

    /**
     * 快速路径: 定位 text/html 分段 -> 按 Transfer-Encoding 解码 -> 转文本
     */
    private fun fastExtract(bytes: ByteArray): String? {
        val raw = String(bytes, Charsets.ISO_8859_1)
        extractRawHtml(raw)?.let { return it }
        return extractHtmlByLooseMime(raw)
    }

    private fun fallback(bytes: ByteArray): String {
        val raw = String(bytes, Charsets.ISO_8859_1)
        // 1) 明文 HTML 直接截取
        extractRawHtml(raw)?.let { return it }
        // 2) 宽松 MIME: 不看 boundary, 直接按行找 text/html 分段并解码
        extractHtmlByLooseMime(raw)?.let { return it }
        // 3) 整个文件当作 quoted-printable 解一次, 再找 HTML
        decodeQuotedPrintable(raw.toByteArray(Charsets.ISO_8859_1)).let { decoded ->
            extractRawHtml(String(decoded, Charsets.ISO_8859_1))?.let { return it }
        }
        // 4) 最后兜底: 清洗成纯文本
        return htmlToText(cleanFallback(String(bytes, Charsets.UTF_8)))
    }

    /**
     * 宽松 MIME 扫描: 逐行找 Content-Type: text/html 的分段头, 取其后的空行到
     * 下一个 boundary / 文件结尾之间的内容, 按 Content-Transfer-Encoding 解码.
     * 不依赖 boundary 参数解析, 兼容 header 折行、畸形分隔符等真实文件.
     */
    private fun extractHtmlByLooseMime(raw: String): String? {
        val boundary = parseBoundaryFromHead(raw)
        val lines = raw.split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("Content-Type:", true) && line.contains("text/html", true)) {
                // 收集这一段头(含折行续行)
                var cte: String? = null
                var charset: String? = Regex("charset\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.get(1)
                var j = i + 1
                var headerEnd = -1
                while (j < lines.size) {
                    val l = lines[j]
                    val t = l.trim()
                    if (t.isEmpty()) {
                        headerEnd = j
                        break
                    }
                    // 折行续行(以空白开头)
                    if (l.isNotEmpty() && (l[0] == ' ' || l[0] == '\t')) {
                        if (charset == null) {
                            charset = Regex("charset\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
                                .find(t)?.groupValues?.get(1)
                        }
                        j++
                        continue
                    }
                    if (t.startsWith("--")) break
                    if (t.startsWith("Content-Transfer-Encoding:", true)) {
                        cte = t.substringAfter(':').trim()
                    } else if (charset == null && t.startsWith("Content-Type:", true)) {
                        charset = Regex("charset\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
                            .find(t)?.groupValues?.get(1)
                    }
                    j++
                }
                if (headerEnd < 0) return null
                // 取正文直到下一个分隔行
                val bodySb = StringBuilder()
                var k = headerEnd + 1
                while (k < lines.size) {
                    val l = lines[k]
                    val t = l.trimStart()
                    if (t.startsWith("--")) {
                        // 分隔行: "--" + boundary, 或者没有 boundary 时的长分隔串
                        if (boundary != null && t.startsWith("--$boundary")) break
                        if (boundary == null && t.length > 8) break
                    }
                    bodySb.append(l).append('\n')
                    k++
                }
                val bodyBytes = bodySb.toString().toByteArray(Charsets.ISO_8859_1)
                val decoded = when {
                    cte.isNullOrBlank() -> bodyBytes
                    cte.contains("base64", true) -> runCatching {
                        java.util.Base64.getDecoder().decode(
                            String(bodyBytes, Charsets.ISO_8859_1).replace(Regex("[^A-Za-z0-9+/=]"), "")
                        )
                    }.getOrDefault(bodyBytes)

                    cte.contains("quoted-printable", true) -> decodeQuotedPrintable(bodyBytes)
                    else -> bodyBytes
                }
                val text = decodeWithCharsetName(decoded, charset)
                val out = htmlToText(text)
                if (out.isNotBlank() && !looksLikeMime(out) && !looksLikeQpGarbage(out)) {
                    return out
                }
            }
            i++
        }
        return null
    }

    /**
     * 从文件开头解析 multipart boundary(可能折行)
     */
    private fun parseBoundaryFromHead(raw: String): String? {
        val head = raw.take(4096)
        val match = Regex(
            "boundary\\s*=\\s*(?:\"([^\"]+)\"|([^;\\s\r\\n]+))",
            RegexOption.IGNORE_CASE
        ).find(head) ?: return null
        return (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim().ifEmpty { null }
    }

    private fun decodeWithCharsetName(bytes: ByteArray, charsetName: String?): String {
        val name = charsetName?.takeIf { it.isNotBlank() } ?: "utf-8"
        return runCatching {
            String(bytes, java.nio.charset.Charset.forName(name))
        }.getOrElse { String(bytes, Charsets.UTF_8) }
    }

    /**
     * 结果里大量 =XX 转义, 说明 quoted-printable 没解码
     */
    private fun looksLikeQpGarbage(text: String): Boolean {
        val hits = Regex("=[0-9A-Fa-f]{2}").findAll(text).count()
        return hits > 10 && hits * 4 > text.length / 6
    }

    private fun decodeQuotedPrintable(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b == '='.code) {
                if (i + 2 < bytes.size && bytes[i + 1].toInt() == '\r'.code && bytes[i + 2].toInt() == '\n'.code) {
                    i += 3
                    continue
                }
                if (i + 1 < bytes.size && bytes[i + 1].toInt() == '\n'.code) {
                    i += 2
                    continue
                }
                if (i + 2 < bytes.size) {
                    val hex = String(bytes, i + 1, 2, Charsets.US_ASCII)
                    val value = hex.toIntOrNull(16)
                    if (value != null) {
                        out.write(value)
                        i += 3
                        continue
                    }
                }
            }
            out.write(b)
            i++
        }
        return out.toByteArray()
    }

    private fun extractRawHtml(raw: String): String? {
        val lower = raw.lowercase()
        val start = when {
            lower.contains("<html") -> lower.indexOf("<html")
            lower.contains("<!doctype html") -> lower.indexOf("<!doctype html")
            else -> return null
        }
        val end = lower.lastIndexOf("</html>")
        if (end <= start) return null
        val fragment = raw.substring(start, end + 7)
        val charset = Regex("charset\\s*=\\s*\"?([\\w-]+)", RegexOption.IGNORE_CASE)
            .find(fragment)?.groupValues?.get(1) ?: "utf-8"
        val html = runCatching {
            String(
                fragment.toByteArray(Charsets.ISO_8859_1),
                java.nio.charset.Charset.forName(charset)
            )
        }.getOrDefault(fragment)
        return htmlToText(html).takeIf { it.isNotBlank() && !looksLikeMime(it) }
    }

    private fun looksLikeMime(text: String): Boolean {
        if (text.contains("Content-Transfer-Encoding:", true)) return true
        if (text.contains("Content-Type: multipart", true)) return true
        if (text.contains("MIME-Version:", true)) return true
        return Regex("------=_?NextPart").containsMatchIn(text)
    }

    private fun cleanFallback(text: String): String {
        val out = StringBuilder()
        text.lineSequence().forEach { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> out.append('\n')

                t.startsWith("Content-Type:", true) ||
                        t.startsWith("Content-Transfer-Encoding:", true) ||
                        t.startsWith("Content-Location:", true) ||
                        t.startsWith("Content-Disposition:", true) ||
                        t.startsWith("MIME-Version:", true) ||
                        t.startsWith("boundary=", true) -> Unit

                t.startsWith("------=_") || t.startsWith("----=_") -> Unit

                Regex("^(https?://|file:///|www\\.)\\S+$", RegexOption.IGNORE_CASE).matches(t) -> Unit

                t.length > 60 && t.matches(Regex("[A-Za-z0-9+/=]+")) -> Unit

                else -> out.append(t).append('\n')
            }
        }
        return out.toString()
    }

    /** 超过这个长度改用轻量提取, 避免构建 DOM */
    private const val FAST_TEXT_THRESHOLD = 100_000

    /**
     * 轻量 HTML 取文本: 只做标签剥离 + 实体解码, 不建 DOM.
     * 网页快照 300KB 量级可省掉大半解析时间.
     */
    private fun htmlToTextFast(html: String): String? {
        return runCatching {
            var t = html
            t = Regex("(?is)<script[^>]*>.*?</script>").replace(t, " ")
            t = Regex("(?is)<style[^>]*>.*?</style>").replace(t, " ")
            t = Regex("(?is)<noscript[^>]*>.*?</noscript>").replace(t, " ")
            t = Regex("(?is)<head[^>]*>.*?</head>").replace(t, " ")
            t = Regex("(?s)<!--.*?-->").replace(t, " ")
            // 块级标签换成换行, 保证标题独立成行
            t = Regex("(?i)<br\\s*/?>").replace(t, "\n")
            t = Regex("(?i)</?(p|div|h[1-6]|li|tr|ul|ol|dl|dt|dd|section|article|blockquote|pre|table|thead|tbody|header|footer|nav)\\b[^>]*>")
                .replace(t, "\n")
            // 其余标签直接去掉
            t = Regex("<[^>]*>").replace(t, "")
            // 实体解码交给 jsoup(不建 DOM, 很快)
            t = org.jsoup.parser.Parser.unescapeEntities(t, false)
            t.replace('\u00a0', ' ')
                .replace(Regex("[ \\t]+\\n"), "\n")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
        }.getOrNull()
    }

    // ---------------------------------------------------------------- HTML 转文本

    private fun htmlToText(html: String): String {
        if (html.isBlank()) return ""
        // 大文档(网页快照常见 300KB+)构建 DOM 很慢, 直接按标签切更快
        if (html.length > FAST_TEXT_THRESHOLD) {
            htmlToTextFast(html)?.let { if (it.isNotBlank()) return it }
        }
        return runCatching {
            val doc = Jsoup.parse(html)
            doc.select("script,style,noscript,iframe,head").remove()
            doc.outputSettings().prettyPrint(false)
            // 块级元素前后都补换行, 保证标题单独成行, TXT 目录规则才能识别章节
            doc.select("br").after("\n")
            doc.select("p,div,h1,h2,h3,h4,h5,h6,li,tr,section,article,blockquote,pre")
                .prepend("\n")
                .append("\n")
            doc.body().wholeText()
                .replace('\u00a0', ' ')
                .replace(Regex("[ \\t]+\\n"), "\n")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
        }.getOrElse {
            html.replace(Regex("(?is)<script.*?</script>"), "")
                .replace(Regex("(?is)<style.*?</style>"), "")
                .replace(Regex("(?i)<br\\s*/?>"), "\n")
                .replace(Regex("(?i)</(p|div|h\\d|li|tr)>"), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .trim()
        }
    }
}