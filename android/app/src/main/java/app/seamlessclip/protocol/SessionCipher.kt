package app.seamlessclip.protocol

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Per-connection AES-256-GCM with counter nonces; session key from HKDF over the pairing key
 * and both handshake nonces. Byte-for-byte compatible with SessionCipher.cs.
 *
 * Not thread-safe: callers serialise [seal] (write lock) and [open] (single reader).
 */
class SessionCipher(
    pairingKey: ByteArray,
    serverNonce: ByteArray,
    clientNonce: ByteArray,
    isServer: Boolean,
) {
    private val key: SecretKeySpec
    private val sendDirection: Int
    private val receiveDirection: Int
    private var sendCounter = 0L
    private var receiveCounter = 0L

    init {
        val derived = Hkdf.sha256(pairingKey, serverNonce + clientNonce, HKDF_INFO.toByteArray(Charsets.US_ASCII), 32)
        key = SecretKeySpec(derived, "AES")
        derived.fill(0)
        sendDirection = if (isServer) SERVER_TO_CLIENT else CLIENT_TO_SERVER
        receiveDirection = if (isServer) CLIENT_TO_SERVER else SERVER_TO_CLIENT
    }

    fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(sendDirection, sendCounter++)))
        return cipher.doFinal(plaintext) // ciphertext || 16-byte tag
    }

    /** @throws GeneralSecurityException (usually AEADBadTagException) if the frame doesn't authenticate. */
    fun open(frame: ByteArray): ByteArray {
        if (frame.size < TAG_BITS / 8) throw GeneralSecurityException("Encrypted frame shorter than GCM tag")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(receiveDirection, receiveCounter)))
        val plaintext = cipher.doFinal(frame)
        receiveCounter++
        return plaintext
    }

    private fun nonce(direction: Int, counter: Long): ByteArray =
        ByteBuffer.allocate(12).putInt(direction).putLong(counter).array() // big-endian

    companion object {
        const val HKDF_INFO = "seamless-clip v1 session"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val SERVER_TO_CLIENT = 1
        private const val CLIENT_TO_SERVER = 2
    }
}
