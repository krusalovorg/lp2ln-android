package lp2ln_android.krusalov.org.network

object PeerInvitation {
    private const val PREFIX = "lp2ln://peer/"
    private val PEER_ID = Regex("(?:02|03)[a-fA-F0-9]{64}")

    fun encode(peerId: String): String {
        require(PEER_ID.matches(peerId)) { "Неверный Peer ID" }
        return PREFIX + peerId.lowercase()
    }

    fun parse(value: String): String? {
        val text = value.trim()
        val id = if (text.startsWith(PREFIX)) text.removePrefix(PREFIX) else text
        return id.takeIf { PEER_ID.matches(it) }?.lowercase()
    }
}
