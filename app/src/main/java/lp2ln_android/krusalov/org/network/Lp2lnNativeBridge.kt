package lp2ln_android.krusalov.org.network

internal object Lp2lnNativeBridge {
    private val loadError: Throwable? = runCatching {
        System.loadLibrary("lp2ln_android")
    }.exceptionOrNull()

    val isAvailable: Boolean
        get() = loadError == null

    val unavailableReason: String
        get() = loadError?.message ?: "Native LP2LN library is unavailable"

    external fun nativeStart(dataDirectory: String, bootstrapAddress: String): String
    external fun nativeSnapshot(): String
    external fun nativeStop(): String
    external fun nativeUpload(path: String, name: String, retryId: String): String
    external fun nativeExport(id: String, destination: String): String
}
