package lp2ln_android.krusalov.org.network

import android.os.SystemClock
import kotlinx.coroutines.*
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Both roles use the same configuration. IDs are checked after authenticated handoff. */
internal object DuplexProbe {
    const val protocol = 0xff81
    suspend fun run(handle: Long, listener: Boolean, seconds: Int, pps: Int, size: Int,
                    progress: (String) -> Unit = {}): JSONObject = withContext(Dispatchers.IO) {
        require(seconds in 4..1800 && pps in listOf(50,500) && size in 60..4096)
        val slots = seconds * pps
        val period = 1_000_000_000L / pps
        val start = SystemClock.elapsedRealtimeNanos() + 300_000_000L
        val end = start + seconds * 1_000_000_000L
        val sent = async(Dispatchers.IO) {
            val bytes = ByteArray(size) { if(listener) 0x41 else 0x42 }
            val wire = ByteBuffer.wrap(bytes)
            var admitted = 0
            var i = 0
            while(i < slots) {
                ensureActive()
                val now = SystemClock.elapsedRealtimeNanos()
                val remaining = start + i * period - now
                if(remaining > 0) {
                    TimeUnit.NANOSECONDS.sleep(minOf(remaining,20_000_000L))
                    continue
                }
                i = maxOf(i,((now - start) / period).toInt())
                if(i >= slots) break
                wire.putInt(0,i)
                if(Lp2lnRealtimeBridge.nativeOffer(handle,bytes,180)==0) admitted++
                i++
            }
            admitted
        }
        val rotation = launch(Dispatchers.IO) {
            while(!listener && SystemClock.elapsedRealtimeNanos()+60_000_000_000L < end) {
                delay(60_000)
                val result=JSONObject(VoiceBridge.rotate(handle))
                check(result.optBoolean("ok")) { result.optString("error") }
            }
        }
        val seen = BooleanArray(slots)
        var received = 0
        var previous = 0L
        var previousSeq = 0
        var jitterNs = 0.0
        var reportAt = start
        val expected = if(listener) 0x42.toByte() else 0x41.toByte()
        while(received < slots && SystemClock.elapsedRealtimeNanos() < end+1_000_000_000L) {
            ensureActive()
            val bytes = Lp2lnRealtimeBridge.nativeReceive(handle,100) ?: continue
            check(bytes.size==size)
            val seq = ByteBuffer.wrap(bytes).getInt(0)
            check(seq in 0 until slots && !seen[seq])
            for(index in 4 until size) check(bytes[index]==expected)
            seen[seq]=true; received++
            val now=SystemClock.elapsedRealtimeNanos()
            if(previous!=0L) jitterNs+=(abs(now-previous-(seq-previousSeq)*period)-jitterNs)/16.0
            previous=now; previousSeq=seq
            if(now>=reportAt) { progress("RX $received · $pps PPS"); reportAt=now+1_000_000_000L }
        }
        val admitted=sent.await()
        rotation.join()
        // Keep the transport open for the other side's observation window.
        val remaining = end+1_500_000_000L-SystemClock.elapsedRealtimeNanos()
        if(remaining>0) delay(remaining/1_000_000)
        JSONObject().put("role",if(listener)"listener" else "dialer")
            .put("seconds",seconds).put("pps",pps).put("bytes",size)
            .put("offeredSlots",slots).put("localAdmission",admitted).put("remoteDelivered",received)
            .put("jitterMs",jitterNs/1e6).put("goodputBps",received.toLong()*size*8/seconds)
            .put("accepted",received >= slots*0.95)
    }
}
