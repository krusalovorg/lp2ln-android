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
    val files: List<StoredFile> = emptyList(),
    val transfer: FileTransfer = FileTransfer(),
) {
    companion object {
        val DEFAULT_BOOTSTRAP: String = lp2ln_android.krusalov.org.BuildConfig.LP2LN_BOOTSTRAP
    }
}

data class StoredFile(
    val id: String, val name: String, val size: Long, val uploaded: Boolean,
    val replicas: Int, val error: String? = null,
)
data class FileTransfer(
    val busy: Boolean = false, val name: String = "", val stage: String = "",
    val done: Long = 0, val total: Long = 0, val error: String? = null,
)
