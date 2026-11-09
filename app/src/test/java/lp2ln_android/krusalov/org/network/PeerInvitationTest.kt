package lp2ln_android.krusalov.org.network

import org.junit.Assert.*
import org.junit.Test

class PeerInvitationTest {
    private val peer = "02" + "ab".repeat(32)

    @Test fun qrAndManualPeerIdRoundTrip() {
        assertEquals(peer, PeerInvitation.parse(PeerInvitation.encode(peer)))
        assertEquals(peer, PeerInvitation.parse("  ${peer.uppercase()}  "))
    }

    @Test fun rejectsOtherQrCodesAndExtraFields() {
        listOf("", "https://example.org", "lp2ln://peer/$peer?key=secret", "04" + "ab".repeat(32), "ab".repeat(32))
            .forEach { assertNull(it, PeerInvitation.parse(it)) }
    }
}
