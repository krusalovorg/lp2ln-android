package lp2ln_android.krusalov.org.network

import java.io.File
import org.json.JSONObject

class Lp2lnRepository {
    fun start(dataDirectory: File, bootstrapAddress: String): NetworkState {
        if (!Lp2lnNativeBridge.isAvailable) {
            return NetworkState(
                phase = ConnectionPhase.ERROR,
                bootstrapAddress = bootstrapAddress,
                error = Lp2lnNativeBridge.unavailableReason,
            )
        }
        val response = Lp2lnNativeBridge.nativeStart(dataDirectory.absolutePath, bootstrapAddress)
        return parseSnapshot(response, bootstrapAddress)
    }

    fun snapshot(bootstrapAddress: String): NetworkState {
        if (!Lp2lnNativeBridge.isAvailable) {
            return NetworkState(
                phase = ConnectionPhase.ERROR,
                bootstrapAddress = bootstrapAddress,
                error = Lp2lnNativeBridge.unavailableReason,
            )
        }
        return parseSnapshot(Lp2lnNativeBridge.nativeSnapshot(), bootstrapAddress)
    }

    fun stop() {
        if (Lp2lnNativeBridge.isAvailable) {
            Lp2lnNativeBridge.nativeStop()
        }
    }

    private fun parseSnapshot(payload: String, bootstrapAddress: String): NetworkState = runCatching {
        val json = JSONObject(payload)
        if (!json.optBoolean("ok", true)) {
            return@runCatching NetworkState(
                phase = ConnectionPhase.ERROR,
                bootstrapAddress = bootstrapAddress,
                error = json.optString("error", "LP2LN connection failed"),
            )
        }

        val sessionsJson = json.optJSONArray("sessions")
        val sessions = buildList {
            if (sessionsJson != null) {
                for (index in 0 until sessionsJson.length()) {
                    val item = sessionsJson.getJSONObject(index)
                    add(
                        NetworkSession(
                            peerId = item.optString("peerId", "Handshake"),
                            protocol = item.optString("protocol", "unknown"),
                            isActive = item.optBoolean("active"),
                            bytesSent = item.optLong("bytesSent"),
                            bytesReceived = item.optLong("bytesReceived"),
                            lastActivitySeconds = item.optLong("lastActivitySeconds"),
                        ),
                    )
                }
            }
        }
        val lifecycle = json.optString("lifecycle")
        val activePeers = json.optInt("activePeers")
        val phase = when {
            json.optBoolean("degraded") -> ConnectionPhase.DEGRADED
            lifecycle == "running" && activePeers > 0 -> ConnectionPhase.CONNECTED
            lifecycle == "running" -> ConnectionPhase.SEARCHING
            lifecycle == "stopped" -> ConnectionPhase.STOPPED
            else -> ConnectionPhase.STARTING
        }
        NetworkState(
            phase = phase,
            peerId = json.optString("peerId"),
            activePeers = activePeers,
            activeConnections = json.optInt("activeConnections"),
            bytesSent = json.optLong("bytesSent"),
            bytesReceived = json.optLong("bytesReceived"),
            uptimeSeconds = json.optLong("uptimeSeconds"),
            bootstrapAddress = bootstrapAddress,
            sessions = sessions,
            error = json.optString("lastError").ifBlank { null },
        )
    }.getOrElse { error ->
        NetworkState(
            phase = ConnectionPhase.ERROR,
            bootstrapAddress = bootstrapAddress,
            error = "Invalid response from LP2LN: ${error.message}",
        )
    }
}
