package io.legado.app.vault

import android.util.Base64
import io.legado.app.data.AppDbHolder
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 主密钥文件(未加密)内容, 不含任何书籍信息
 */
data class VaultInfo(
    var version: Int = 1,
    var kdf: String = "PBKDF2-HMAC-SHA256",
    var iterations: Int = VaultCrypto.KDF_ITERATIONS,
    var salt: String = "",
    var wrappedKey: String = "",
    var verifier: String = "",
    var createdAt: Long = 0
)

/**
 * 密码 → 主密钥 → 打开数据库, 全程只在内存里持有主密钥
 */
object VaultSession {

    private const val VERIFY_TEXT = "legado-vault-v1"
    private const val DB_KEY_TAG = "legado-db-key-v1"

    @Volatile
    private var masterKey: ByteArray? = null

    /** 界面是否已通过解锁页 */
    @Volatile
    var uiUnlocked: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    private var failedCount = 0

    /** 用户主动跳去系统文件选择器的宽限期(毫秒时间戳) */
    @Volatile
    private var externalGraceUntil = 0L

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * 用户在我们 App 内点了"选择文件/目录", 会短暂离开到系统选择器。
     * 这段离开不是"退到后台", 给一个宽限期, 返回后不必重新输密码。
     * 宽限只在本次跳转有效, 且屏幕熄灭会立即作废。
     */
    fun grantExternalGrace(millis: Long = DEFAULT_EXTERNAL_GRACE) {
        externalGraceUntil = System.currentTimeMillis() + millis
    }

    fun hasExternalGrace(): Boolean =
        externalGraceUntil > 0 && System.currentTimeMillis() < externalGraceUntil

    /** 宽限剩余时间(毫秒), 已失效返回 0 */
    fun externalGraceRemain(): Long =
        (externalGraceUntil - System.currentTimeMillis()).coerceAtLeast(0)

    fun consumeExternalGrace() {
        externalGraceUntil = 0L
    }

    private const val DEFAULT_EXTERNAL_GRACE = 120_000L

    fun hasVault(): Boolean = VaultStorage.exists()

    val isUnlocked: Boolean get() = masterKey != null

    fun addLockListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeLockListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * 首次使用, 创建保险库
     */
    @Synchronized
    @Throws(VaultException::class)
    fun createVault(password: CharArray) {
        if (password.size < 4) throw VaultException("密码至少 4 位")
        val salt = VaultCrypto.randomBytes(VaultCrypto.SALT_SIZE)
        val key = VaultCrypto.randomBytes(VaultCrypto.KEY_SIZE)
        val kdfKey = VaultCrypto.pbkdf2(
            password, salt, VaultCrypto.KDF_ITERATIONS, VaultCrypto.KEY_SIZE
        )
        val info = VaultInfo(
            version = VaultCrypto.VERSION,
            iterations = VaultCrypto.KDF_ITERATIONS,
            salt = salt.base64(),
            wrappedKey = VaultCrypto.seal(kdfKey, key, VERIFY_TEXT.toByteArray()).base64(),
            verifier = VaultCrypto.seal(kdfKey, VERIFY_TEXT.toByteArray()).base64(),
            createdAt = System.currentTimeMillis()
        )
        kdfKey.fill(0)
        VaultStorage.rootDir.mkdirs()
        VaultStorage.dataDir.mkdirs()
        VaultStorage.infoFile.writeText(GSON.toJson(info))
        // 旧的明文数据不再保留
        clearLegacyPlainData()
        masterKey = key
        AppDbHolder.open()
        uiUnlocked = true
    }

    /**
     * 用密码解锁, 成功后才能访问数据库和书籍文件
     */
    @Synchronized
    fun unlock(password: CharArray): Boolean {
        val info = readInfo() ?: run {
            lastError = "保险库不存在"
            return false
        }
        val salt = info.salt.unbase64() ?: return false.also { lastError = "保险库信息损坏" }
        val kdfKey = VaultCrypto.pbkdf2(password, salt, info.iterations, VaultCrypto.KEY_SIZE)
        val key = try {
            VaultCrypto.open(
                kdfKey,
                info.wrappedKey.unbase64() ?: throw VaultException("保险库信息损坏"),
                VERIFY_TEXT.toByteArray()
            )
        } catch (e: Exception) {
            null
        } finally {
            kdfKey.fill(0)
        }
        if (key == null || key.size != VaultCrypto.KEY_SIZE) {
            failedCount++
            delayOnFailed()
            lastError = "密码错误"
            return false
        }
        masterKey = key
        return try {
            AppDbHolder.open()
            uiUnlocked = true
            failedCount = 0
            lastError = null
            true
        } catch (e: Exception) {
            masterKey = null
            lastError = "数据库打开失败: ${e.message}"
            false
        }
    }

    fun markUiUnlocked() {
        uiUnlocked = true
    }

    /**
     * 锁定: 关闭数据库, 清除内存密钥, 删除所有解密临时文件
     */
    @Synchronized
    fun lock(wipeCache: Boolean = true) {
        uiUnlocked = false
        externalGraceUntil = 0L
        kotlin.runCatching { AppDbHolder.close() }
        masterKey?.fill(0)
        masterKey = null
        if (wipeCache) {
            kotlin.runCatching { VaultStorage.wipeCaches() }
        }
        listeners.forEach { kotlin.runCatching { it() } }
    }

    /** 只锁界面(保留密钥, 后台服务还在用) */
    fun markUiLocked() {
        uiUnlocked = false
    }

    /** 退到后台: 清掉界面状态; 没有后台服务时连密钥一起清掉 */
    fun onAppToBackground() {
        // 用户可能还在系统文件选择器里选书, 宽限期内不锁
        if (hasExternalGrace()) return
        uiUnlocked = false
        // Web 服务会把书架内容直接暴露在局域网, 退到后台立即关闭
        kotlin.runCatching {
            if (io.legado.app.service.WebService.isRun) {
                io.legado.app.service.WebService.stop(appCtx)
            }
        }
        if (io.legado.app.help.LifecycleHelp.hasRunningServices()) {
            return
        }
        if (isUnlocked || AppDbHolder.isOpen) {
            lock(wipeCache = true)
        }
    }

    /** 最后一个后台服务也停了 */
    fun onAllServicesStopped() {
        if (!uiUnlocked) {
            kotlin.runCatching { lock(wipeCache = true) }
        }
    }

    /** 数据库口令, 由主密钥派生, 与用户密码无关 */
    fun dbPassphrase(): ByteArray {
        val key = masterKey ?: throw VaultLockedException()
        return VaultCrypto.sha256(key, DB_KEY_TAG.toByteArray())
    }

    /** 当前主密钥, 用于文件加解密 */
    fun requireKey(): ByteArray = masterKey ?: throw VaultLockedException()

    fun readInfo(): VaultInfo? {
        return kotlin.runCatching {
            GSON.fromJson(VaultStorage.infoFile.readText(), VaultInfo::class.java)
        }.getOrNull()
    }

    /** 修改密码: 重新包裹同一个主密钥 */
    @Synchronized
    @Throws(VaultException::class)
    fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean {
        if (!unlock(oldPassword)) return false
        val key = masterKey ?: return false
        val info = readInfo() ?: return false
        val salt = VaultCrypto.randomBytes(VaultCrypto.SALT_SIZE)
        val kdfKey = VaultCrypto.pbkdf2(
            newPassword, salt, VaultCrypto.KDF_ITERATIONS, VaultCrypto.KEY_SIZE
        )
        info.salt = salt.base64()
        info.iterations = VaultCrypto.KDF_ITERATIONS
        info.wrappedKey = VaultCrypto.seal(kdfKey, key, VERIFY_TEXT.toByteArray()).base64()
        info.verifier = VaultCrypto.seal(kdfKey, VERIFY_TEXT.toByteArray()).base64()
        kdfKey.fill(0)
        VaultStorage.infoFile.writeText(GSON.toJson(info))
        return true
    }

    private fun delayOnFailed() {
        kotlin.runCatching {
            Thread.sleep(minOf(300L * failedCount, 3000L))
        }
    }

    /**
     * 首次创建保险库时, 把明文时代的数据库/缓存加密归档后删除, 避免书名泄露
     */
    private fun clearLegacyPlainData() {
        val key = masterKey ?: return
        kotlin.runCatching {
            val legacyDir = File(VaultStorage.dataDir, "legacy")
            val sources = arrayListOf<File>()
            fun collect(dir: File?) {
                dir?.listFiles()?.forEach {
                    if (it.isFile) sources.add(it) else collect(it)
                }
            }
            File(appCtx.applicationInfo.dataDir, "databases").listFiles()?.forEach {
                if (it.isFile && !it.name.endsWith("-wal") && !it.name.endsWith("-shm")) {
                    sources.add(it)
                }
            }
            collect(File(appCtx.externalFiles, "book_cache"))
            collect(File(appCtx.externalFiles, "covers"))
            if (sources.isNotEmpty()) {
                legacyDir.mkdirs()
                sources.forEach { file ->
                    kotlin.runCatching {
                        val target = File(legacyDir, "${VaultStorage.id(file.absolutePath)}.bin")
                        FileOutputStream(target).use { out ->
                            VaultCrypto.encryptStream(key, file.inputStream(), out)
                        }
                    }
                }
            }
            File(appCtx.applicationInfo.dataDir, "databases").deleteRecursively()
            File(appCtx.externalFiles, "book_cache").deleteRecursively()
            File(appCtx.externalFiles, "covers").deleteRecursively()
        }
    }
}

class VaultLockedException : IllegalStateException("保险库已锁定, 请输入密码")

private fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

private fun String.unbase64(): ByteArray? =
    kotlin.runCatching { Base64.decode(this, Base64.NO_WRAP) }.getOrNull()
