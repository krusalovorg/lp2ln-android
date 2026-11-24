package lp2ln_android.krusalov.org.network

internal object VoiceBridge {
    init { System.loadLibrary("lp2ln_android") }
    external fun begin(handle: Long, caller: Boolean): String
    external fun decide(handle: Long, accept: Boolean): String
    external fun step(handle: Long): String
    external fun rotate(handle: Long): String
    external fun startMedia(handle: Long): String
    external fun capture(handle: Long, pcm: ShortArray, ageMs: Int): Int
    external fun ingress(handle: Long): Int
    external fun play(handle: Long, pcm: ShortArray): Int
    external fun stats(handle: Long): String
}
