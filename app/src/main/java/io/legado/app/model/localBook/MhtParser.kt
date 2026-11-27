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
        val bytes = input.use { it.readBytes() }
        if (bytes.isEmpty()) return ""
        val text = parseWithMime4j(bytes) ?: fallback(bytes)
        // 正文内容不进日志, 只记长度
        LogUtils.d(TAG, "parsed ${text.length} chars")
        return text
    }

    // ---------------------------------------------------------------- mime4j

    private fun parseWithMime4j(bytes: ByteArray): String? {
        val message = runCatching {
            val builder = DefaultMessageBuilder()
            builder.setMimeEntityConfig(MimeConfig.PERMISSIVE)
            builder.parseMessage(ByteArrayInputStream(bytes))
        }.getOrElse {
            LogUtils.d(TAG, "mime4j parse failed: ${it.message}")
            return null
        }
        val html = findHtml(message) ?: findPlainText(message) ?: return null
        val text = htmlToText(html)
        return text.takeIf { it.isNotBlank() && !looksLikeMime(it) }
    }

    /**
     * 递归找 text/html 分段, mime4j 已按 charset 解码内容
     */
    private fun findHtml(entity: Entity): String? {
        val body = entity.body
        if (body is Multipart) {
            body.bodyParts.forEach { part ->
                findHtml(part)?.let { return it }
            }
            return null
        }
        val mimeType = entity.mimeType ?: return null
        if (!mimeType.contains("text/html", true)) return null
        return (body as? TextBody)?.let { readTextBody(it) }
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
        val body = entity.body
        if (body is Multipart) {
            body.bodyParts.forEach { part ->
                findPlainText(part)?.let { return it }
            }
            return null
        }
        val mimeType = entity.mimeType ?: return null
        if (!mimeType.contains("text/plain", true)) return null
        return (body as? TextBody)?.let { readTextBody(it) }
    }

    // ---------------------------------------------------------------- 兜底

    private fun fallback(bytes: ByteArray): String {
        val raw = String(bytes, Charsets.ISO_8859_1)
        extractRawHtml(raw)?.let { return it }
        return htmlToText(cleanFallback(String(bytes, Charsets.UTF_8)))
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

    // ---------------------------------------------------------------- HTML 转文本

    private fun htmlToText(html: String): String {
        if (html.isBlank()) return ""
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