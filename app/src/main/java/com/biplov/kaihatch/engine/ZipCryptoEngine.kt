package com.biplov.kaihatch.engine

import java.util.Random

/**
 * Standard PKWARE ZipCrypto stream cipher for password-protected ZIP / ZIP64 entries.
 */
class ZipCryptoCipher(password: String) {

    private var key0: Int = 0x12345678
    private var key1: Int = 0x23456789
    private var key2: Int = 0x34567890

    companion object {
        private val CRC_TABLE = IntArray(256).apply {
            for (i in 0 until 256) {
                var r = i
                for (j in 0 until 8) {
                    r = if ((r and 1) != 0) (r ushr 1) xor 0xEDB88320.toInt() else (r ushr 1)
                }
                this[i] = r
            }
        }

        private fun crc32Byte(oldCrc: Int, byteVal: Int): Int {
            return (oldCrc ushr 8) xor CRC_TABLE[(oldCrc xor byteVal) and 0xFF]
        }
    }

    init {
        for (b in password.toByteArray(Charsets.UTF_8)) {
            updateKeys(b.toInt() and 0xFF)
        }
    }

    private fun updateKeys(byteVal: Int) {
        key0 = crc32Byte(key0, byteVal)
        key1 = (key1 + (key0 and 0xFF)) * 134775813 + 1
        key2 = crc32Byte(key2, (key1 ushr 24) and 0xFF)
    }

    private fun decryptByte(): Int {
        val temp = (key2 and 0xFFFF) or 2
        return ((temp * (temp xor 1)) ushr 8) and 0xFF
    }

    fun decryptHeaderAndVerify(header12: ByteArray, expectedCrc: Long): Boolean {
        if (header12.size < 12) return false
        var lastByte = 0
        for (i in 0 until 12) {
            val c = (header12[i].toInt() and 0xFF) xor decryptByte()
            updateKeys(c)
            header12[i] = c.toByte()
            lastByte = c
        }
        // Check byte is either high byte of CRC32 or high byte of DOS time
        val expectedCheck = ((expectedCrc ushr 24) and 0xFFL).toInt()
        return expectedCrc == 0L || lastByte == expectedCheck
    }

    fun generateEncryptedHeader(crc32: Long): ByteArray {
        val header = ByteArray(12)
        val rnd = Random()
        rnd.nextBytes(header)
        header[11] = ((crc32 ushr 24) and 0xFFL).toByte()
        encryptBytesInPlace(header, 0, 12)
        return header
    }

    fun decryptBytesInPlace(buffer: ByteArray, offset: Int, length: Int) {
        for (i in offset until (offset + length)) {
            val plain = (buffer[i].toInt() and 0xFF) xor decryptByte()
            updateKeys(plain)
            buffer[i] = plain.toByte()
        }
    }

    fun encryptBytesInPlace(buffer: ByteArray, offset: Int, length: Int) {
        for (i in offset until (offset + length)) {
            val plain = buffer[i].toInt() and 0xFF
            val cipher = plain xor decryptByte()
            updateKeys(plain)
            buffer[i] = cipher.toByte()
        }
    }
}
