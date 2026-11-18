package com.notio.shared.transfer

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password protection of a backup (D134). A protected file is a wrapper around the ordinary backup ZIP:
 *
 *     "NOTIOENC" | version(1) | iterations(4) | salt(16) | nonce prefix(8) | chunk*
 *     chunk = length(4, high bit = last chunk) | AES-256-GCM(ciphertext + tag)
 *
 * The key is PBKDF2-HMAC-SHA256 of the password and the random salt. The file is cut into 64 KB chunks with their own
 * nonce (prefix + counter) and the "last chunk" flag in the authenticated data, so a big backup is never held in memory
 * and a cut or reordered file is refused, not read half. An unprotected backup stays exactly the ZIP it always was; an
 * older app version that meets a protected file simply says it is not a backup.
 */
object BackupCrypto {
    private val MAGIC = "NOTIOENC".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    /** New protected backups use 600 000 rounds; the count is written in the file, so older backups (200 000) still open. */
    private const val ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val NONCE_PREFIX_BYTES = 8
    private const val CHUNK = 64 * 1024
    private const val TAG_BYTES = 16
    private const val LAST = 0x80000000.toInt()

    /** How many bytes [isProtected] needs to see. */
    const val HEAD_BYTES = 8

    fun isProtected(head: ByteArray): Boolean = head.size >= MAGIC.size && MAGIC.indices.all { head[it] == MAGIC[it] }

    private fun key(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    private fun nonce(prefix: ByteArray, counter: Int): ByteArray =
        prefix + byteArrayOf((counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte())

    private fun aad(last: Boolean) = byteArrayOf(if (last) 1 else 0)

    /** An output stream that writes [out] encrypted; closing it finishes the file (and closes [out]). */
    fun encrypt(out: OutputStream, password: String, random: SecureRandom = SecureRandom()): OutputStream {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val prefix = ByteArray(NONCE_PREFIX_BYTES).also(random::nextBytes)
        val key = key(password, salt, ITERATIONS)
        out.write(MAGIC)
        out.write(VERSION)
        out.write(intBytes(ITERATIONS))
        out.write(salt)
        out.write(prefix)
        return object : OutputStream() {
            private val buffer = ByteArray(CHUNK)
            private var filled = 0
            private var counter = 0
            private var closed = false

            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                var at = off
                var left = len
                while (left > 0) {
                    // A full chunk is only written once more data follows, so the LAST chunk can be marked at close.
                    if (filled == CHUNK) emit(last = false)
                    val n = minOf(left, CHUNK - filled)
                    System.arraycopy(b, at, buffer, filled, n)
                    filled += n
                    at += n
                    left -= n
                }
            }

            private fun emit(last: Boolean) {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce(prefix, counter++)))
                cipher.updateAAD(aad(last))
                val sealed = cipher.doFinal(buffer, 0, filled)
                out.write(intBytes(if (last) sealed.size or LAST else sealed.size))
                out.write(sealed)
                filled = 0
            }

            override fun flush() = out.flush()

            override fun close() {
                if (closed) return
                closed = true
                emit(last = true)
                out.flush()
                out.close()
            }
        }
    }

    /**
     * The plain backup inside a protected file. The first chunk is opened at once, so a wrong password is found here
     * ([TransferError.WRONG_PASSWORD]) and not half way through the import.
     */
    fun decrypt(input: InputStream, password: String): InputStream {
        val data = DataInputStream(input)
        val head = ByteArray(MAGIC.size)
        try {
            data.readFully(head)
            if (!isProtected(head)) throw TransferException(TransferError.NOT_A_BACKUP)
            val version = data.readUnsignedByte()
            if (version != VERSION) throw TransferException(TransferError.UNSUPPORTED_VERSION)
            val iterations = data.readInt()
            if (iterations !in 1_000..10_000_000) throw TransferException(TransferError.CORRUPT)
            val salt = ByteArray(SALT_BYTES).also(data::readFully)
            val prefix = ByteArray(NONCE_PREFIX_BYTES).also(data::readFully)
            val key = key(password, salt, iterations)
            return Decrypting(data, key, prefix)
        } catch (e: EOFException) {
            throw TransferException(TransferError.NOT_A_BACKUP, e)
        }
    }

    private class Decrypting(private val data: DataInputStream, private val key: SecretKeySpec, private val prefix: ByteArray) : InputStream() {
        private var current = ByteArray(0)
        private var pos = 0
        private var counter = 0
        private var finished = false

        init {
            // Opens the first chunk now: a wrong password fails here.
            nextChunk(first = true)
        }

        private fun nextChunk(first: Boolean) {
            if (finished) {
                current = ByteArray(0)
                pos = 0
                return
            }
            val raw = try {
                data.readInt()
            } catch (e: EOFException) {
                throw TransferException(TransferError.CORRUPT, e)
            }
            val last = raw and LAST != 0
            val length = raw and LAST.inv()
            if (length < TAG_BYTES || length > CHUNK + TAG_BYTES) throw TransferException(TransferError.CORRUPT)
            val sealed = ByteArray(length)
            try {
                data.readFully(sealed)
            } catch (e: EOFException) {
                throw TransferException(TransferError.CORRUPT, e)
            }
            current = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce(prefix, counter++)))
                cipher.updateAAD(aad(last))
                cipher.doFinal(sealed)
            } catch (e: GeneralSecurityException) {
                throw TransferException(if (first) TransferError.WRONG_PASSWORD else TransferError.CORRUPT, e)
            }
            pos = 0
            if (last) {
                finished = true
                if (data.read() != -1) throw TransferException(TransferError.CORRUPT)
            }
        }

        override fun read(): Int {
            while (pos >= current.size) {
                if (finished) return -1
                nextChunk(first = false)
            }
            return current[pos++].toInt() and 0xFF
        }

        @Suppress("SuspiciousIndentation") // a false positive of the lint check
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= current.size) {
                if (finished) return -1
                nextChunk(first = false)
            }
            val n = minOf(len, current.size - pos)
            System.arraycopy(current, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() = data.close()
    }

    private fun intBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /** Reads the first bytes of [input] without losing them: the returned stream still starts at the beginning. */
    fun peek(input: InputStream): Pair<InputStream, Boolean> {
        val buffered = if (input.markSupported()) input else BufferedInputStream(input)
        buffered.mark(HEAD_BYTES)
        val head = ByteArray(HEAD_BYTES)
        var read = 0
        try {
            while (read < HEAD_BYTES) {
                val n = buffered.read(head, read, HEAD_BYTES - read)
                if (n < 0) break
                read += n
            }
        } catch (e: IOException) {
            throw TransferException(TransferError.NOT_A_BACKUP, e)
        }
        buffered.reset()
        return buffered to isProtected(head.copyOf(read))
    }
}
