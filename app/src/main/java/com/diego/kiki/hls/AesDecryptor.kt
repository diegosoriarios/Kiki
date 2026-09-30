package com.diego.kiki.hls

import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AesDecryptor {

    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"

    /**
     * Decrypts an AES-128 HLS segment. PKCS5 == PKCS7 for 16-byte blocks in JCA.
     */
    fun decrypt(data: ByteArray, key: ByteArray, ivHex: String?): ByteArray {
        val iv = ivHex?.let { parseHexIv(it) } ?: return decrypt(data, key, ByteArray(16))
        return decrypt(data, key, iv)
    }

    fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(key.size == 16) { "AES-128 key must be 16 bytes" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /**
     * Per RFC 8216: when IV attribute is absent, the IV is the 128-bit
     * big-endian media sequence number of the segment.
     */
    fun sequenceIv(sequence: Long): ByteArray {
        return BigInteger.valueOf(sequence).toByteArray().let { bytes ->
            // BigInteger strips leading zeros; right-align into 16 bytes
            ByteArray(16).also { result ->
                bytes.copyInto(result, destinationOffset = 16 - bytes.size)
            }
        }
    }

    fun parseHexIv(hex: String): ByteArray {
        val cleaned = hex.removePrefix("0x").removePrefix("0X").replace(" ", "")
        require(cleaned.length in 2..32) { "Invalid IV length" }
        val padded = cleaned.padStart(32, '0')
        return ByteArray(16) { i ->
            ((Character.digit(padded[i * 2], 16) shl 4) +
                    Character.digit(padded[i * 2 + 1], 16)).toByte()
        }
    }
}
