package app.seamlessclip.protocol

/** Constants shared with the Windows app. See docs/PROTOCOL.md. */
object Protocol {
    const val NAME = "seamless-clip"
    const val VERSION = 1
    const val DEFAULT_PORT = 45700
    const val BEACON_PORT = 45701
    const val HANDSHAKE_NONCE_LENGTH = 16
    const val PAIRING_KEY_LENGTH = 32
    const val MAX_HANDSHAKE_FRAME = 4 * 1024
    const val MAX_FRAME = 8 * 1024 * 1024
    const val MAX_TEXT_BYTES = 4 * 1024 * 1024

    const val TYPE_AUTH = "auth"
    const val TYPE_AUTH_OK = "auth_ok"
    const val TYPE_CLIP = "clip"
    const val TYPE_PING = "ping"
    const val TYPE_PONG = "pong"
}
