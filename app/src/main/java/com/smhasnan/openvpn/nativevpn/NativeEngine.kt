package com.smhasnan.openvpn.nativevpn

import com.smhasnan.openvpn.config.MergeResult
import com.smhasnan.openvpn.config.NativeEvalResult
import com.smhasnan.openvpn.config.ProfileMetadata
import com.smhasnan.openvpn.util.LogSanitizer
import com.smhasnan.openvpn.vpn.ConnectionDetails
import com.smhasnan.openvpn.vpn.TrafficStats
import org.json.JSONObject

/** Stateless native helper calls used for profile validation and diagnostics. */
object NativeEngine {
    fun evalConfig(content: String): NativeEvalResult {
        if (!OpenVpnNative.isAvailable) {
            return NativeEvalResult(
                error = true,
                message = OpenVpnNative.loadError ?: "Native OpenVPN engine is not available.",
                metadata = ProfileMetadata(),
            )
        }
        return runCatching {
            val json = JSONObject(OpenVpnNative.evalConfig(content))
            NativeEvalResult(
                error = json.optBoolean("error"),
                message = json.optString("message"),
                metadata = ProfileMetadata(
                    serverHost = json.stringOrNull("remoteHost"),
                    serverPort = json.stringOrNull("remotePort"),
                    protocol = json.stringOrNull("remoteProto"),
                    autologin = json.optBoolean("autologin"),
                    needsPassword = !json.optBoolean("autologin"),
                    userlockedUsername = json.stringOrNull("userlockedUsername"),
                    staticChallenge = json.stringOrNull("staticChallenge"),
                    staticChallengeEcho = json.optBoolean("staticChallengeEcho"),
                    privateKeyPasswordRequired = json.optBoolean("privateKeyPasswordRequired"),
                    allowPasswordSave = json.optBoolean("allowPasswordSave", true),
                    externalPki = json.optBoolean("externalPki"),
                    profileName = json.stringOrNull("profileName"),
                    friendlyName = json.stringOrNull("friendlyName"),
                ),
            )
        }.getOrElse {
            NativeEvalResult(
                error = true,
                message = "The profile could not be validated by the native engine.",
                metadata = ProfileMetadata(),
            )
        }
    }

    fun mergeConfig(path: String): MergeResult {
        if (!OpenVpnNative.isAvailable) {
            return MergeResult(
                success = false,
                content = null,
                message = OpenVpnNative.loadError ?: "Native OpenVPN engine is not available.",
                refCount = 0,
            )
        }
        return runCatching {
            val json = JSONObject(OpenVpnNative.mergeConfig(path))
            val status = json.optString("status")
            val success = status == "MERGE_SUCCESS"
            MergeResult(
                success = success,
                content = if (success) json.optString("content").takeIf { it.isNotBlank() } else null,
                message = if (success) null else json.optString("errorText").ifBlank {
                    "The profile could not be read."
                },
                refCount = json.optInt("refCount"),
            )
        }.getOrElse {
            MergeResult(false, null, "The profile could not be parsed.", 0)
        }
    }

    fun connectionDetails(json: String?): ConnectionDetails {
        if (json.isNullOrBlank()) return ConnectionDetails()
        return runCatching {
            val root = JSONObject(json)
            if (!root.optBoolean("defined")) return ConnectionDetails()
            ConnectionDetails(
                serverHost = root.stringOrNull("serverHost"),
                serverPort = root.stringOrNull("serverPort"),
                protocol = root.stringOrNull("serverProto"),
                serverIp = root.stringOrNull("serverIp"),
                vpnIp4 = root.stringOrNull("vpnIp4"),
                vpnIp6 = root.stringOrNull("vpnIp6"),
                mtu = root.stringOrNull("vpnMtu"),
                tunName = root.stringOrNull("tunName"),
            )
        }.getOrDefault(ConnectionDetails())
    }

    fun stats(json: String?): TrafficStats {
        if (json.isNullOrBlank()) return TrafficStats()
        return runCatching {
            val root = JSONObject(json)
            val tunBytesIn = root.optLong("TUN_BYTES_IN", 0L)
            val tunBytesOut = root.optLong("TUN_BYTES_OUT", 0L)
            val tunPacketsIn = root.optLong("TUN_PACKETS_IN", 0L)
            val tunPacketsOut = root.optLong("TUN_PACKETS_OUT", 0L)
            // TUN counters are not populated by every Core 3.9 path. Fall back
            // to real transport counters rather than showing a fake zero.
            TrafficStats(
                bytesIn = if (tunBytesIn > 0L) tunBytesIn else root.optLong("BYTES_IN", 0L),
                bytesOut = if (tunBytesOut > 0L) tunBytesOut else root.optLong("BYTES_OUT", 0L),
                packetsIn = if (tunPacketsIn > 0L) tunPacketsIn else root.optLong("PACKETS_IN", 0L),
                packetsOut = if (tunPacketsOut > 0L) tunPacketsOut else root.optLong("PACKETS_OUT", 0L),
            )
        }.getOrDefault(TrafficStats())
    }

    fun engineInfo(): Map<String, String> {
        if (!OpenVpnNative.isAvailable) {
            return mapOf("coreVersion" to "unavailable", "platform" to "unknown", "copyright" to "")
        }
        return runCatching {
            val json = JSONObject(OpenVpnNative.engineInfo())
            mapOf(
                "coreVersion" to json.optString("coreVersion"),
                "platform" to json.optString("platform"),
                "copyright" to json.optString("copyright"),
            )
        }.getOrDefault(mapOf("coreVersion" to "unknown", "platform" to "unknown", "copyright" to ""))
    }

    /** Runs OpenVPN Core's built-in crypto self-test. */
    fun selfTest(): Result<String> {
        if (!OpenVpnNative.isAvailable) {
            return Result.failure(IllegalStateException("Native engine is unavailable."))
        }
        return runCatching {
            val json = JSONObject(OpenVpnNative.selfTest())
            val ok = json.optBoolean("ok")
            val message = LogSanitizer.sanitize(json.optString("message"))
            if (ok) "OpenVPN Core crypto self-test passed." else message.take(500).ifBlank { "Crypto self-test failed." }
        }
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
