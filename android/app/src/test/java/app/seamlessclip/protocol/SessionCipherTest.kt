package app.seamlessclip.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException

class SessionCipherTest {
    private val pairingKey = ByteArray(32) { it.toByte() }
    private val serverNonce = ByteArray(16) { (0xA0 + it).toByte() }
    private val clientNonce = ByteArray(16) { (0x50 + it).toByte() }

    private fun pair() = SessionCipher(pairingKey, serverNonce, clientNonce, isServer = true) to
        SessionCipher(pairingKey, serverNonce, clientNonce, isServer = false)

    @Test
    fun roundTripsInBothDirectionsAcrossManyFrames() {
        val (server, client) = pair()
        repeat(5) { i ->
            val up = "phone $i".toByteArray()
            assertArrayEquals(up, server.open(client.seal(up)))
            val down = "pc $i".toByteArray()
            assertArrayEquals(down, client.open(server.seal(down)))
        }
    }

    @Test
    fun rejectsTamperedFrame() {
        val (server, client) = pair()
        val frame = client.seal("hello".toByteArray())
        frame[0] = (frame[0].toInt() xor 1).toByte()
        assertThrows(GeneralSecurityException::class.java) { server.open(frame) }
    }

    @Test
    fun rejectsReplayedFrame() {
        val (server, client) = pair()
        val frame = client.seal("once".toByteArray())
        server.open(frame)
        assertThrows(GeneralSecurityException::class.java) { server.open(frame) }
    }

    @Test
    fun rejectsReflectedFrame() {
        // A frame the client sent must not be accepted by the client itself (direction is in the nonce).
        val (_, client) = pair()
        val frame = client.seal("mirror".toByteArray())
        assertThrows(GeneralSecurityException::class.java) { client.open(frame) }
    }

    @Test
    fun rejectsWrongPairingKey() {
        val (_, client) = pair()
        val impostor = SessionCipher(ByteArray(32), serverNonce, clientNonce, isServer = true)
        assertThrows(GeneralSecurityException::class.java) { impostor.open(client.seal("secret".toByteArray())) }
    }
}
