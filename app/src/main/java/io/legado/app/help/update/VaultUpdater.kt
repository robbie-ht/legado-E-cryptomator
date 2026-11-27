package io.legado.app.help.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.legado.app.constant.AppConst
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * 自动更新: 从 GitHub Releases 拉取最新版本
 *
 * 约定 release 里的 APK 资源名形如 legado-vault-<versionName>.apk,
 * 版本号直接取文件名, 避免 tag 命名不统一导致版本比较出错.
 */
object VaultUpdater {

    private const val REPO = "robbie-ht/legado-E-cryptomator"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val API_RELEASES = "https://api.github.com/repos/$REPO/releases?per_page=5"

    private val assetRegex = Regex("legado-vault-(\\d+(?:\\.\\d+){2})\\.apk", RegexOption.IGNORE_CASE)

    data class Release(
        val version: String,
        val notes: String,
        val downloadUrl: String,
        val fileName: String
    )

    /**
     * 检查是否有新版本, 没有返回 null
     */
    fun check(scope: CoroutineScope): Coroutine<Release?> {
        return Coroutine.async(scope) {
            val body = runCatching {
                okHttpClient.newCall(Request.Builder().url(API_LATEST).build())
                    .execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        resp.body.string()
                    }
            }.getOrNull() ?: return@async null
            val release = parseRelease(body) ?: return@async null
            if (isNewer(release.version, AppConst.appInfo.versionName)) release else null
        }
    }

    /**
     * 拉取最近几个 release(用于 latest 里没有 APK 时的兜底)
     */
    private fun parseRelease(body: String): Release? {
        return runCatching {
            val json = JSONObject(body)
            val notes = json.optString("body")
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                assetRegex.find(name)?.let { match ->
                    if (url.isNotBlank()) {
                        return Release(match.groupValues[1], notes, url, name)
                    }
                }
            }
            null
        }.getOrNull()
    }

    /**
     * 远端版本是否比本地新
     * versionName 形如 3.26.112711 (3 + yy.MMddHH), 逐段做数值比较
     */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").mapNotNull { it.trim().toIntOrNull() }
        val l = local.split(".").mapNotNull { it.trim().toIntOrNull() }
        if (r.isEmpty() || l.isEmpty()) return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /**
     * 下载并拉起系统安装界面
     */
    fun downloadAndInstall(
        context: Context,
        release: Release,
        onProgress: ((Int) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        Coroutine.async {
            try {
                val dir = context.getExternalFilesDir("update") ?: File(context.cacheDir, "update")
                dir.mkdirs()
                val file = File(dir, release.fileName)
                okHttpClient.newCall(Request.Builder().url(release.downloadUrl).build())
                    .execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw IllegalStateException("下载失败(${resp.code})")
                        }
                        val body = resp.body
                        val total = body.contentLength()
                        body.byteStream().use { input ->
                            file.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var read = 0L
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n <= 0) break
                                    output.write(buffer, 0, n)
                                    read += n
                                    if (total > 0) {
                                        onProgress?.invoke((read * 100 / total).toInt())
                                    }
                                }
                            }
                        }
                    }
                withContext(Main) {
                    installApk(context, file) {
                        onError?.invoke("没找到可用的安装器, 请到文件管理里手动安装\n${file.absolutePath}")
                    }
                }
            } catch (e: Exception) {
                withContext(Main) {
                    onError?.invoke(e.localizedMessage ?: "下载失败")
                }
            }
        }
    }

    /**
     * 拉起系统安装器
     */
    fun installApk(context: Context, file: File, onFail: (() -> Unit)? = null) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileProvider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            context.toastOnUi("打开安装器失败: ${it.localizedMessage}")
            onFail?.invoke()
        }
    }
}