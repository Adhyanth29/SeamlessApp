package app.seamlessclip.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Length-prefixed frames: uint32 big-endian length, then payload. */
object Framing {
    fun read(input: DataInputStream, maxLength: Int): ByteArray {
        val length = input.readInt()
        if (length < 0 || length > maxLength) throw IOException("Frame length $length exceeds limit $maxLength")
        val payload = ByteArray(length)
        input.readFully(payload)
        return payload
    }

    fun write(output: DataOutputStream, payload: ByteArray) {
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }
}
