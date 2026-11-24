package lp2ln_android.krusalov.org.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapAddressTest {
    @Test
    fun acceptsHostPortPairs() {
        listOf("192.0.2.20:18080", "seed.example.org:443", "a:1", "node-1.example.com:65535")
            .forEach { assertTrue(it, NetworkViewModel.isValidBootstrap(it)) }
    }

    @Test
    fun rejectsMalformedAddresses() {
        listOf("", "192.0.2.20", ":18080", "host:0", "host:65536", "host:abc", "ho st:80", "-host:80")
            .forEach { assertFalse(it, NetworkViewModel.isValidBootstrap(it)) }
    }
}
