package lp2ln_android.krusalov.org.network

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import java.io.File

@RunWith(AndroidJUnit4::class)
class FlowDuplexDeviceTest {
    private val protocol = 0xff81
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()

    @Test
    fun duplex() {
        val role = arguments.getString("flowRole")
        assumeTrue(role == "identity" || role == "listener" || role == "dialer")
        val dir = File(instrument.targetContext.filesDir, "flow-device-smoke").apply { mkdirs() }
        success(Lp2lnNativeBridge.nativeStart(dir.absolutePath, ""))
        try {
            val status = ready()
            instrument.sendStatus(1, Bundle().apply {
                putString("flowPeerId", status.getString("peerId"))
                putInt("flowPort", status.getInt("port"))
            })
            if (role == "identity") return
            val other = requireNotNull(arguments.getString("flowPeer"))
            val opened = if (role == "listener") {
                success(Lp2lnRealtimeBridge.nativeAuthorize(other, protocol))
                success(Lp2lnRealtimeBridge.nativeAccept(120_000))
            } else {
                success(Lp2lnRealtimeBridge.nativeOpen(
                    other, requireNotNull(arguments.getString("flowAddress")), protocol
                ))
            }
            assertEquals(other, opened.getString("peerId"))
            val maxPayload = opened.getInt("maxPayload")
            val handle = opened.getLong("handle")
            try {
                val seconds = arguments.getString("seconds")?.toInt() ?: 4
                val pps = arguments.getString("pps")?.toInt() ?: 50
                val size = arguments.getString("size")?.toInt() ?: minOf(1000,maxPayload)
                require(size <= maxPayload)
                val result = runBlocking { DuplexProbe.run(handle,role=="listener",seconds,pps,size) }
                File(instrument.targetContext.filesDir,"flow-result.json").writeText(result.toString())
                instrument.sendStatus(1,Bundle().apply { putString("flowResult",result.toString()) })
                assertTrue(result.toString(),result.getBoolean("accepted"))
            } finally {
                Lp2lnRealtimeBridge.nativeClose(handle)
            }
        } finally {
            Lp2lnNativeBridge.nativeStop()
        }
    }

    private fun ready(): JSONObject {
        repeat(30) {
            val state = JSONObject(Lp2lnRealtimeBridge.nativeStatus())
            if (state.optBoolean("ok")) return state
            SystemClock.sleep(100)
        }
        error("QUIC listener did not become ready")
    }

    private fun success(value: String): JSONObject = JSONObject(value).also {
        assertTrue(it.optString("error"), it.optBoolean("ok"))
    }
}
