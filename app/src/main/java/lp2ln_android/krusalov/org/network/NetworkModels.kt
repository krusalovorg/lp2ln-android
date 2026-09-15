package lp2ln_android.krusalov.org.network

data class NetworkSession(
    val peerId: String,
    val protocol: String,
    val isActive: Boolean,
    val bytesSent: Long,
    val bytesReceived: Long,
    val lastActivitySeconds: Long,
)

enum class ConnectionPhase {
    STARTING,
    SEARCHING,
    CONNECTED,
    DEGRADED,
    STOPPED,
    ERROR,
}

data class NetworkState(
    val phase: ConnectionPhase = ConnectionPhase.STARTING,
    val peerId: String = "",
    val activePeers: Int = 0,
    val activeConnections: Int = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val uptimeSeconds: Long = 0,
    val bootstrapAddress: String = DEFAULT_BOOTSTRAP,
    val sessions: List<NetworkSession> = emptyList(),
    val error: String? = null,
) {
    companion object {
        const val DEFAULT_BOOTSTRAP = "83.136.233.187:18080"
    }
}
