package app.seamlessclip.protocol

import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/** JSON message builders. Field names must match WireMessage/HelloMessage in the Windows app. */
object Messages {
    fun clientHello(clientId: String, nonce: ByteArray): JSONObject = JSONObject()
        .put("proto", Protocol.NAME)
        .put("v", Protocol.VERSION)
        .put("clientId", clientId)
        .put("nonce", Base64.getEncoder().encodeToString(nonce))

    fun auth(deviceName: String): JSONObject = JSONObject()
        .put("type", Protocol.TYPE_AUTH)
        .put("device", deviceName)

    fun clip(text: String, deviceName: String): JSONObject = JSONObject()
        .put("type", Protocol.TYPE_CLIP)
        .put("id", UUID.randomUUID().toString().replace("-", ""))
        .put("mime", "text/plain")
        .put("text", text)
        .put("ts", System.currentTimeMillis())
        .put("device", deviceName)

    fun ping(): JSONObject = JSONObject().put("type", Protocol.TYPE_PING)
    fun pong(): JSONObject = JSONObject().put("type", Protocol.TYPE_PONG)
}
