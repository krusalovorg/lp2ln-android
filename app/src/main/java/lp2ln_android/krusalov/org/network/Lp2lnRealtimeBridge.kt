package lp2ln_android.krusalov.org.network

/** Direct Flow preview. Run blocking calls on an I/O thread. */
internal object Lp2lnRealtimeBridge {
    init {
        System.loadLibrary("lp2ln_android")
    }

    external fun nativeStatus(): String
    external fun nativeRevoke(peerId: String, protocol: Int): String
    external fun nativeAuthorize(peerId: String, protocol: Int): String
    external fun nativeOpen(peerId: String, address: String, protocol: Int): String
    external fun nativeAccept(timeoutMs: Int): String
    external fun nativeOffer(handle: Long, payload: ByteArray, ttlMs: Int): Int

    /** Returns null on timeout. A closed or invalid handle raises an exception. */
    external fun nativeReceive(handle: Long, timeoutMs: Int): ByteArray?
    external fun nativeClose(handle: Long): String
}
