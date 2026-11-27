package io.legado.app.model.localBook

import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset

/**
 * MHT / MHTML 单文件网页解析
 *
 * MHT 是 MIME multipart 格式: 一个 HTML 正文 + 若干内嵌资源(图片/CSS)。
 * 阅读只需要正文, 因此这里把 HTML 部分取出来转成纯文本, 再按 TXT 目录规则分章。
 */
object MhtParser {

    private const val CRLF = "\r\n"

    fun isMht(fileName: String): Boolean =
        fileName.endsWith(".mht", true) || fileName.endsWith(".mhtml", true)

    /**
     * 把 mht 输入流转成纯文本
     */
    fun toPlainText(input: InputStream): String {
        val bytes = input.use { it.readBytes() }
        if (bytes.isEmpty()) return ""
        // 用 ISO-8859-1 做结构解析: 1 字节对应 1 字符, 可无损还原
        val raw = String(bytes, Charsets.ISO_8859_1)
        val splitIndex = indexOfHeaderEnd(raw)
        if (splitIndex < 0) {
            // 没有 MIME 头, 直接当 HTML
            return htmlToText(decodeWithCharset(bytes, findCharset(raw) ?: "utf-8"))
        }
        val header = raw.substring(0, splitIndex)
        val bodyStart = when {
            raw.startsWith("$CRLF$CRLF", splitIndex) -> splitIndex + 4
            raw.startsWith("\n\n", splitIndex) -> splitIndex + 2
            else -> splitIndex + 2
        }
        val body = raw.substring(bodyStart)
        val boundary = findBoundary(header)
        val html = if (boundary != null) {
            findHtmlPart(body, boundary)
        } else {
            decodePartBody(body, findHeaderValue(header, "Content-Transfer-Encoding"), findCharset(header))
        }
        val text = html ?: decodePartBody(body, null, findCharset(header))
        return htmlToText(text)
    }

    // ---------------------------------------------------------------- MIME 结构

    private fun indexOfHeaderEnd(raw: String): Int {
        val crlf = raw.indexOf("$CRLF$CRLF")
        val lf = raw.indexOf("\n\n")
        return when {
            crlf >= 0 && (lf < 0 || crlf <= lf) -> crlf
            lf >= 0 -> lf
            else -> -1
        }
    }

    private fun findBoundary(header: String): String? {
        val contentType = findHeaderValue(header, "Content-Type") ?: return null
        val match = Regex("boundary\\s*=\\s*(\"([^\"]+)\"|([^;\\s]+))", RegexOption.IGNORE_CASE)
            .find(contentType) ?: return null
        return (match.groupValues[2].ifEmpty { match.groupValues[3] }).trim()
    }

    private fun findHeaderValue(header: String, key: String): String? {
        val regex = Regex("^${Regex.escape(key)}\\s*:\\s*(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        return regex.find(header)?.groupValues?.get(1)?.trim()
    }

    private fun findCharset(text: String): String? {
        val match = Regex("charset\\s*=\\s*\"?([\\w-]+)\"?", RegexOption.IGNORE_CASE).find(text)
        return match?.groupValues?.get(1)
    }

    /**
     * 在 multipart 正文里找第一个 text/html 分段
     */
    private fun findHtmlPart(body: String, boundary: String): String? {
        var html: String? = null
        val delimiter = "--$boundary"
        val segments = body.split(delimiter)
        for (segment in segments) {
            val seg = segment.trimStart('\r', '\n')
            if (seg.startsWith("--")) break // 结束标记
            if (seg.isBlank()) continue
            val headEnd = indexOfHeaderEnd(seg)
            if (headEnd < 0) continue
            val head = seg.substring(0, headEnd)
            val partBody = seg.substring(headEnd + if (seg.startsWith("$CRLF$CRLF", headEnd)) 4 else 2)
            val contentType = findHeaderValue(head, "Content-Type") ?: ""
            val isHtml = contentType.contains("text/html", true)
            val isAttachment = (findHeaderValue(head, "Content-Disposition") ?: "")
                .contains("attachment", true)
            if (isAttachment && !isHtml) continue
            if (isHtml || (html == null && contentType.contains("text/", true))) {
                val decoded = decodePartBody(
                    partBody,
                    findHeaderValue(head, "Content-Transfer-Encoding"),
                    findCharset(contentType) ?: "utf-8"
                )
                if (isHtml) return decoded
                if (html == null) html = decoded
            }
        }
        return html
    }

    // ---------------------------------------------------------------- 编码解码

    private fun decodePartBody(body: String, encoding: String?, charsetName: String?): String {
        val clean = body.trim('\r', '\n')
        val bytes = clean.toByteArray(Charsets.ISO_8859_1)
        val decoded = when {
            encoding.isNullOrBlank() -> bytes
            encoding.contains("base64", true) -> decodeBase64(bytes)
            encoding.contains("quoted-printable", true) -> decodeQuotedPrintable(bytes)
            else -> bytes
        }
        return decodeWithCharset(decoded, charsetName ?: "utf-8")
    }

    private fun decodeBase64(bytes: ByteArray): ByteArray {
        val clean = String(bytes, Charsets.ISO_8859_1)
            .replace(Regex("[^A-Za-z0-9+/=]"), "")
        return kotlin.runCatching {
            java.util.Base64.getDecoder().decode(clean)
        }.getOrElse {
            android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
        }
    }

    private fun decodeQuotedPrintable(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b == '='.code) {
                // 软换行 =CRLF / =\n
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

    private fun decodeWithCharset(bytes: ByteArray, charsetName: String): String {
        val name = charsetName.trim().ifBlank { "utf-8" }
        return kotlin.runCatching {
            String(bytes, Charset.forName(name))
        }.getOrElse {
            String(bytes, Charset.forName("utf-8"))
        }
    }

    // ---------------------------------------------------------------- HTML 转文本

    private fun htmlToText(html: String): String {
        if (html.isBlank()) return ""
        return kotlin.runCatching {
            val doc = Jsoup.parse(html)
            doc.select("script,style,noscript,iframe,head").remove()
            doc.outputSettings().prettyPrint(false)
            doc.select("script,style,noscript,iframe,head").remove()
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
            // Jsoup 失败时退化为正则去标签
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