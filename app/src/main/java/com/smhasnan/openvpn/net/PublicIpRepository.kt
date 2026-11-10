package com.smhasnan.openvpn.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the public egress IP through the current Android network. When the VPN
 * is connected this request is routed through the tunnel by VpnService, so the
 * result is meaningful evidence of the egress path. It is not, by itself, a
 * complete leak test.
 */
class PublicIpRepository {
    suspend fun fetch(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "OpenVPNClient-Android/1.0")
                instanceFollowRedirects = false
            }
            try {
                if (connection.responseCode !in 200..299) {
                    error("IP service returned HTTP ${connection.responseCode}.")
                }
                val body = connection.inputStream.bufferedReader().use { reader ->
                    val text = StringBuilder()
                    val buffer = CharArray(2048)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        text.append(buffer, 0, read)
                        if (text.length > 32_768) error("IP service response was too large.")
                    }
                    text.toString()
                }
                val candidate = runCatching { JSONObject(body).optString("ip") }
                    .getOrElse { body.trim() }
                    .trim()
                require(candidate.isNotBlank() && candidate.length <= 64) { "IP service returned an invalid address." }
                require(candidate.all { it.isDigit() || it in "abcdefABCDEF:." }) {
                    "IP service returned an invalid address."
                }
                candidate
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        const val ENDPOINT = "https://api.ipify.org?format=json"
    }
}
