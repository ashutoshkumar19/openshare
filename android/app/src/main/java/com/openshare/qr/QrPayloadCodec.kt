package com.openshare.qr

import com.openshare.core.HotspotQrPayload
import org.json.JSONObject

object QrPayloadCodec {
    fun encode(p: HotspotQrPayload): String = JSONObject()
        .put("mode", p.mode).put("v", p.v).put("ssid", p.ssid)
        .put("passphrase", p.passphrase).put("host", p.host).put("port", p.port)
        .put("token", p.token).put("name", p.name).toString()

    fun decode(json: String): HotspotQrPayload {
        val o = JSONObject(json)
        return HotspotQrPayload(
            mode = o.getString("mode"), v = o.getInt("v"),
            ssid = o.getString("ssid"),
            passphrase = if (o.isNull("passphrase")) null else o.getString("passphrase"),
            host = o.getString("host"), port = o.getInt("port"),
            token = o.getString("token"), name = o.optString("name")
        )
    }
}
