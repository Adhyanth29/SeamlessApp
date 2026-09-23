package app.seamlessclip.protocol

import java.io.ByteArrayOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF-SHA256 (RFC 5869). Matches System.Security.Cryptography.HKDF on Windows. */
object Hkdf {
    private const val ALGORITHM = "HmacSHA256"
    private const val HASH_LENGTH = 32

    fun sha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_LENGTH) { "Invalid HKDF output length $length" }
        val mac = Mac.getInstance(ALGORITHM)

        // Extract
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(HASH_LENGTH) else salt, ALGORITHM))
        val prk = mac.doFinal(ikm)

        // Expand
        mac.init(SecretKeySpec(prk, ALGORITHM))
        val output = ByteArrayOutputStream(length)
        var block = ByteArray(0)
        var counter = 1
        while (output.size() < length) {
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            output.write(block)
            counter++
        }
        return output.toByteArray().copyOf(length)
    }
}
