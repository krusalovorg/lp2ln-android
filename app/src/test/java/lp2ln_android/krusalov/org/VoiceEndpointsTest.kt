package lp2ln_android.krusalov.org

import lp2ln_android.krusalov.org.network.KnownNodeAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceEndpointsTest {
    @Test fun dialOnlyDistinctQuicAddressesBelongingToSelectedPeer() {
        val nodes = listOf(KnownNodeAddress("other", "wrong", "quic"),
            KnownNodeAddress("peer", "stream", "tcp"), KnownNodeAddress("peer", "", "quic")) +
            listOf("first", "first", "second", "third", "fourth").map { KnownNodeAddress("peer", it, "quic") }
        assertEquals(listOf("first", "second", "third"), voiceEndpoints("peer", nodes))
        assertEquals(emptyList<String>(), voiceEndpoints("missing", nodes))
    }
}
