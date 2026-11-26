package io.legado.app.vault

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 保险库加解密原语
 * - 口令派生: PBKDF2-HMAC-SHA256
 * - 数据加密: AES-256-GCM
 */
internal object VaultCrypto {

    const val VERSION = 1

    /** KDF 迭代次数 */
    const val KDF_ITERATIONS = 200_000
    const val SALT_SIZE = 16
    const val KEY_SIZE = 32
    const val IV_SIZE = 12
    const val TAG_BITS = 128

    private const val AES_MODE = "AES/GCM/NoPadding"
    private const val HMAC = "HmacSHA256"

    /** 文件头魔数, 便于识别, 不含任何书籍信息 */
    val FILE_MAGIC = byteArrayOf('L'.code.toByte(), 'V'.code.toByte(), 'F'.code.toByte(), 1)

    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        return bytes
    }

    /**
     * PBKDF2-HMAC-SHA256, 兼容 API 21
     */
    fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(String(password).toByteArray(Charsets.UTF_8), HMAC))
        val blocks = (keyLength + 31) / 32
        val out = ByteArray(blocks * 32)
        val block = ByteArray(salt.size + 4)
        System.arraycopy(salt, 0, block, 0, salt.size)
        for (i in 1..blocks) {
            block[salt.size] = (i ushr 24).toByte()
            block[salt.size + 1] = (i ushr 16).toByte()
            block[salt.size + 2] = (i ushr 8).toByte()
            block[salt.size + 3] = i.toByte()
            var u = mac.doFinal(block)
            val t = u.copyOf()
            for (round in 2..iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) {
                    t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
                }
            }
            System.arraycopy(t, 0, out, (i - 1) * 32, 32)
        }
        return out.copyOf(keyLength)
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it) }
        return digest.digest()
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        return mac.doFinal(data)
    }

    private fun gcmEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray, aad: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )
        aad?.let { cipher.updateAAD(it) }
        return cipher.doFinal(data)
    }

    private fun gcmDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray, aad: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )
        aad?.let { cipher.updateAAD(it) }
        return cipher.doFinal(data)
    }

    /**
     * 小数据加密, 输出 iv + 密文(含tag)
     */
    fun seal(key: ByteArray, data: ByteArray, aad: ByteArray? = null): ByteArray {
        val iv = randomBytes(IV_SIZE)
        val cipherText = gcmEncrypt(key, iv, data, aad)
        return iv + cipherText
    }

    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray {
        require(sealed.size > IV_SIZE) { "密文长度错误" }
        val iv = sealed.copyOfRange(0, IV_SIZE)
        val cipherText = sealed.copyOfRange(IV_SIZE, sealed.size)
        return gcmDecrypt(key, iv, cipherText, aad)
    }

    /**
     * 大文件分块加密: 每一块使用独立 nonce(前缀+块号) 与 块号作 AAD, 防止重排/截断
     */
    fun encryptStream(key: ByteArray, input: java.io.InputStream, output: java.io.OutputStream) {
        val prefix = randomBytes(8)
        output.write(FILE_MAGIC)
        output.write(prefix)
        val buffer = ByteArray(CHUNK + 1024)
        var index = 0L
        var finish = false
        input.use { ins ->
            while (!finish) {
                var read = 0
                while (read < CHUNK) {
                    val r = ins.read(buffer, read, CHUNK - read)
                    if (r < 0) {
                        finish = true
                        break
                    }
                    read += r
                }
                if (read > 0) {
                    output.write(encryptChunk(key, prefix, index, buffer, read))
                    index++
                }
            }
        }
        output.flush()
    }

    private const val CHUNK = 64 * 1024

    private fun encryptChunk(
        key: ByteArray,
        prefix: ByteArray,
        index: Long,
        data: ByteArray,
        length: Int
    ): ByteArray = gcmEncrypt(key, nonce(prefix, index), data.copyOf(length), aad(index))

    fun decryptStream(key: ByteArray, input: java.io.InputStream, output: java.io.OutputStream) {
        val header = ByteArray(FILE_MAGIC.size + 8)
        readFully(input, header)
        if (header[0] != FILE_MAGIC[0] || header[1] != FILE_MAGIC[1]
            || header[2] != FILE_MAGIC[2] || header[3] != FILE_MAGIC[3]
        ) {
            throw VaultException("文件格式错误")
        }
        val prefix = header.copyOfRange(FILE_MAGIC.size, header.size)
        var index = 0L
        val buffer = ByteArray(CHUNK + 16)
        while (true) {
            val read = fillBuffer(input, buffer)
            if (read <= 0) break
            output.write(
                gcmDecrypt(
                    key,
                    nonce(prefix, index),
                    buffer.copyOf(read),
                    aad(index)
                )
            )
            index++
        }
        output.flush()
    }

    private fun nonce(prefix: ByteArray, index: Long): ByteArray {
        val nonce = ByteArray(IV_SIZE)
        System.arraycopy(prefix, 0, nonce, 0, 8)
        nonce[8] = (index ushr 24).toByte()
        nonce[9] = (index ushr 16).toByte()
        nonce[10] = (index ushr 8).toByte()
        nonce[11] = index.toByte()
        return nonce
    }

    private fun aad(index: Long): ByteArray {
        val aad = ByteArray(8)
        aad[0] = (index ushr 56).toByte()
        aad[1] = (index ushr 48).toByte()
        aad[2] = (index ushr 40).toByte()
        aad[3] = (index ushr 32).toByte()
        aad[4] = (index ushr 24).toByte()
        aad[5] = (index ushr 16).toByte()
        aad[6] = (index ushr 8).toByte()
        aad[7] = index.toByte()
        return aad
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray) {
        var read = 0
        while (read < buffer.size) {
            val r = input.read(buffer, read, buffer.size - read)
            if (r < 0) throw VaultException("文件已损坏")
            read += r
        }
    }

    /** 尽量填满缓冲, 用于分块解密 */
    private fun fillBuffer(input: java.io.InputStream, buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) {
            val r = input.read(buffer, read, buffer.size - read)
            if (r < 0) break
            read += r
        }
        return read
    }
}

class VaultException(message: String, cause: Throwable? = null) : Exception(message, cause)
