package io.legado.app.vault

import android.net.Uri
import io.legado.app.data.entities.Book
import io.legado.app.utils.MD5Utils
import splitties.init.appCtx
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * 保险库存储布局 (程序数据目录下):
 *
 * files/novelVault/
 * ├── vault.info               未加密文件, 只保存 KDF 参数/盐/被口令包裹的主密钥, 不含任何书籍信息
 * └── vault/                   加密文件夹
 *     ├── library.db           SQLCipher 加密数据库(书架/书源/正文缓存/阅读进度)
 *     ├── books/<id>.bin       本地导入书籍正文(AES-256-GCM 加密)
 *     ├── books/<id>/<i>.bin   书源章节正文(AES-256-GCM 加密)
 *     └── covers/<id>.bin      本地书籍封面(AES-256-GCM 加密)
 */
object VaultStorage {

    const val SCHEME = "vault://"
    const val BOOKS_PREFIX = "vault://books/"

    const val ROOT_NAME = "novelVault"
    const val INFO_NAME = "vault.info"
    const val DATA_NAME = "vault"

    val rootDir: File get() = File(appCtx.filesDir, ROOT_NAME)
    val infoFile: File get() = File(rootDir, INFO_NAME)
    val dataDir: File get() = File(rootDir, DATA_NAME)
    val booksDir: File get() = File(dataDir, "books")
    val coversDir: File get() = File(dataDir, "covers")
    const val DATABASE_FILE_NAME = "library.db"

    val databaseFile: File get() = File(dataDir, DATABASE_FILE_NAME)

    /** 解密后的临时工作目录, 锁定时清空 */
    fun workDir(): File = File(appCtx.filesDir, ".vaultwork")

    fun exists(): Boolean = infoFile.exists()

    fun id(text: String): String = MD5Utils.md5Encode16(text)

    fun isVaultUrl(url: String?): Boolean = url != null && url.startsWith(SCHEME)

    fun bookUrlOf(uniqueKey: String): String = BOOKS_PREFIX + id(uniqueKey)

    private fun ensureDirs() {
        booksDir.mkdirs()
        coversDir.mkdirs()
    }

    private fun idOfVaultBook(bookUrl: String): String = bookUrl.removePrefix(BOOKS_PREFIX)

    private fun bookFile(bookUrl: String): File =
        File(booksDir, idOfVaultBook(bookUrl) + ".bin")

    /** 章节目录用 bookUrl 的摘要, 不暴露书名/网址 */
    private fun chapterDir(bookUrl: String): File =
        File(booksDir, id(bookUrl))

    private fun chapterFile(bookUrl: String, index: Int): File =
        File(chapterDir(bookUrl), "$index.bin")

    fun coverFile(id: String): File = File(coversDir, "$id.bin")

    private fun openUri(uri: Uri): InputStream =
        appCtx.contentResolver.openInputStream(uri)
            ?: throw VaultException("无法打开文件: ${uri.path}")

    // ---------------------------------------------------------------- 本地书文件

    /**
     * 把用户选择的文件加密存进保险库, 返回 vault:// 形式的 bookUrl
     * @param uniqueKey 用于生成稳定 id 的键(通常是原始 uri 字符串)
     */
    @Synchronized
    fun storeLocalBook(keyByte: ByteArray, uri: Uri, uniqueKey: String): String {
        ensureDirs()
        val bookUrl = bookUrlOf(uniqueKey)
        val target = bookFile(bookUrl)
        target.parentFile?.mkdirs()
        openUri(uri).use { input ->
            FileOutputStream(target).use { out ->
                VaultCrypto.encryptStream(keyByte, input, out)
            }
        }
        return bookUrl
    }

    @Synchronized
    fun storeLocalBook(keyByte: ByteArray, input: InputStream, uniqueKey: String): String {
        ensureDirs()
        val bookUrl = bookUrlOf(uniqueKey)
        val target = bookFile(bookUrl)
        target.parentFile?.mkdirs()
        input.use { ins ->
            FileOutputStream(target).use { out ->
                VaultCrypto.encryptStream(keyByte, ins, out)
            }
        }
        return bookUrl
    }

    fun deleteBook(bookUrl: String) {
        bookFile(bookUrl).delete()
        chapterDir(bookUrl).deleteRecursively()
    }

    fun bookLastModified(bookUrl: String): Long {
        val f = bookFile(bookUrl)
        return if (f.exists()) f.lastModified() else 0
    }

    fun isBookStored(bookUrl: String): Boolean = bookFile(bookUrl).exists()

    /**
     * 取出解密后的书籍文件, 供现有解析器(epub/pdf/mobi/txt)使用
     */
    @Synchronized
    fun materialize(keyByte: ByteArray, bookUrl: String, originName: String): File {
        val target = File(workDir(), "books/" + idOfVaultBook(bookUrl) + suffixOf(originName))
        if (target.exists() && target.length() > 0) {
            return target
        }
        val src = bookFile(bookUrl)
        if (!src.exists()) throw VaultException("书籍文件不存在")
        target.parentFile?.mkdirs()
        val tmp = File(target.absolutePath + ".tmp")
        src.inputStream().use { input ->
            FileOutputStream(tmp).use { out ->
                VaultCrypto.decryptStream(keyByte, input, out)
            }
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return target
    }

    private fun suffixOf(fileName: String): String {
        val idx = fileName.lastIndexOf('.')
        return if (idx in 0 until fileName.length - 1) fileName.substring(idx) else ".bin"
    }

    // ---------------------------------------------------------------- 章节正文

    private fun chapterFile(bookUrl: String, fileName: String): File =
        File(chapterDir(bookUrl), fileName)

    @Synchronized
    fun writeChapter(keyByte: ByteArray, book: Book, fileName: String, content: String) {
        ensureDirs()
        val dir = chapterDir(book.bookUrl)
        dir.mkdirs()
        val target = chapterFile(book.bookUrl, fileName)
        val tmp = File(target.absolutePath + ".tmp")
        FileOutputStream(tmp).use { out ->
            VaultCrypto.encryptStream(
                keyByte,
                content.toByteArray(Charsets.UTF_8).inputStream(),
                out
            )
        }
        replace(tmp, target)
    }

    fun readChapter(keyByte: ByteArray, book: Book, fileName: String): String? {
        val file = chapterFile(book.bookUrl, fileName)
        if (!file.exists()) return null
        return kotlin.runCatching {
            val out = ByteArrayOutputStream()
            file.inputStream().use { input ->
                VaultCrypto.decryptStream(keyByte, input, out)
            }
            out.toString("UTF-8")
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun hasChapter(book: Book, fileName: String): Boolean =
        chapterFile(book.bookUrl, fileName).exists()

    fun listChapterFiles(book: Book): HashSet<String> {
        val names = hashSetOf<String>()
        chapterDir(book.bookUrl).list()?.forEach {
            if (!it.endsWith(".tmp")) names.add(it)
        }
        return names
    }

    fun deleteChapter(book: Book, fileName: String) {
        chapterFile(book.bookUrl, fileName).delete()
    }

    fun deleteChapters(book: Book) {
        chapterDir(book.bookUrl).deleteRecursively()
    }

    fun deleteAllChapters() {
        booksDir.listFiles()?.forEach {
            if (it.isDirectory) it.deleteRecursively()
        }
    }

    private fun replace(tmp: File, target: File) {
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    // ---------------------------------------------------------------- 封面

    @Synchronized
    fun writeCover(keyByte: ByteArray, id: String, input: InputStream) {
        ensureDirs()
        val target = coverFile(id)
        val tmp = File(target.absolutePath + ".tmp")
        input.use { ins ->
            FileOutputStream(tmp).use { out ->
                VaultCrypto.encryptStream(keyByte, ins, out)
            }
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * 封面解密到临时目录, 返回文件(没有则 null)
     */
    @Synchronized
    fun materializeCover(keyByte: ByteArray, id: String): File? {
        val src = coverFile(id)
        if (!src.exists()) return null
        val target = File(workDir(), "covers/$id.jpg")
        if (target.exists() && target.length() > 0) return target
        target.parentFile?.mkdirs()
        val tmp = File(target.absolutePath + ".tmp")
        src.inputStream().use { input ->
            FileOutputStream(tmp).use { out ->
                VaultCrypto.decryptStream(keyByte, input, out)
            }
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return target
    }

    // ---------------------------------------------------------------- 工具

    fun wipeWorkDir() {
        kotlin.runCatching { workDir().deleteRecursively() }
    }

    fun wipeCaches() {
        wipeWorkDir()
        kotlin.runCatching {
            appCtx.externalCacheDir?.deleteRecursively()
        }
        kotlin.runCatching {
            appCtx.cacheDir.deleteRecursively()
        }
    }
}
