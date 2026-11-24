package lp2ln_android.krusalov.org

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import lp2ln_android.krusalov.org.network.*
import org.json.JSONObject

internal fun checked(text: String): JSONObject = JSONObject(text).also {
    check(it.optBoolean("ok")) { it.optString("error", "Native call failed") }
}

class VoiceService : Service() {
    companion object {
        internal const val PROTOCOL = 0xff82
        internal var instance by mutableStateOf<VoiceService?>(null)
        internal var summary by mutableStateOf("Остановлен")
        internal var detail by mutableStateOf("")
        internal var incoming by mutableStateOf(false)
        internal var busy by mutableStateOf(false)
        internal var diagnostics by mutableStateOf(false)
        internal var latestReport by mutableStateOf("")
        internal var muted by mutableStateOf(false)
        internal var loud by mutableStateOf(false)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var selected = ""
    private var self = ""
    private var pendingDial = 0L
    private var ticket = 0L
    private var configured = false
    private var outgoing = false
    private var audio: VoiceAudio? = null
    private var activeHandle = 0L
    private var worker: Job? = null
    private lateinit var wake: PowerManager.WakeLock
    private lateinit var multicast: android.net.wifi.WifiManager.MulticastLock
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notices: NotificationManager
    private fun notice(message: String): Notification {
        val open = PendingIntent.getActivity(this, 7, Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 8, Intent(this, VoiceService::class.java)
            .setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "voice").setSmallIcon(R.drawable.ic_lp2ln_status)
            .setContentTitle("LP2LN · Голос").setContentText(message).setOngoing(true)
            .setOnlyAlertOnce(true).setContentIntent(open).addAction(0,"Остановить",stop).build()
    }
    override fun onCreate() {
        super.onCreate()
        notices = getSystemService(NotificationManager::class.java)
        notices.createNotificationChannel(NotificationChannel("voice","Голос LP2LN",NotificationManager.IMPORTANCE_DEFAULT))
        startForeground(72, notice("Подготовка"))
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"lp2ln:voice")
        multicast = (applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
            .createMulticastLock("lp2ln-voice-discovery")
        multicast.acquire()
        NodeSession.acquire().connect(filesDir)
        instance = this
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        val peer = intent?.getStringExtra("peer").orEmpty().trim()
        val address = intent?.getStringExtra("endpoint").orEmpty().trim()
        if (configured && selected != peer) { summary = "Сначала остановите приём"; return START_NOT_STICKY }
        if (!configured && peer.isNotEmpty()) {
            configured = true; selected = peer
            scope.launch {
                try {
                    val status = withContext(Dispatchers.IO) { checked(Lp2lnRealtimeBridge.nativeStatus()) }
                    self = status.getString("peerId")
                    require(peer != self)
                    withContext(Dispatchers.IO) { checked(Lp2lnRealtimeBridge.nativeAuthorize(peer,PROTOCOL)) }
                    summary = "Ожидание вызова"
                    launch { listen() }
                    if (address.isNotEmpty()) dial(address)
                } catch (e: Exception) { summary = "Ошибка: ${e.message}"; stopSelf() }
            }
        } else if (configured && self.isNotEmpty() && address.isNotEmpty() && !busy) dial(address)
        return START_NOT_STICKY
    }
    private suspend fun listen() {
        while (currentCoroutineContext().isActive) {
            val raw = withContext(NonCancellable + Dispatchers.IO) { JSONObject(Lp2lnRealtimeBridge.nativeAccept(1000)) }
            if (!raw.optBoolean("ok")) { delay(100); continue }
            val h = raw.getLong("handle")
            if (!currentCoroutineContext().isActive) { closeHandle(h); return }
            if (raw.getString("peerId") != selected || raw.getInt("protocol") != PROTOCOL ||
                (busy && (self < selected || audio != null || (activeHandle != 0L && !outgoing)))) {
                closeHandle(h); continue
            }
            // Both peers dialled. The lower Peer ID remains the initiator.
            ++ticket; pendingDial = 0
            worker?.cancelAndJoin()
            begin(raw, false)
        }
    }
    private fun dial(address: String) {
        if (busy) return
        val generation = ++ticket
        pendingDial = generation
        busy = true; summary = "Соединение"
        scope.launch {
            try {
                val raw = withContext(NonCancellable + Dispatchers.IO) { checked(Lp2lnRealtimeBridge.nativeOpen(selected,address,PROTOCOL)) }
                if (!isActive || pendingDial != generation) closeHandle(raw.getLong("handle"))
                else { pendingDial = 0; begin(raw, true) }
            } catch (e: Exception) {
                if (pendingDial == generation) { pendingDial = 0; busy = false; summary = "Соединение: ${e.message}" }
            }
        }
    }
    private fun begin(raw: JSONObject, caller: Boolean) {
        val h = raw.getLong("handle")
        val flow = raw.getJSONArray("flowId").let { a -> (0 until a.length()).joinToString("") { "%02x".format(a.getInt(it)) } }
        outgoing=caller; activeHandle=h; busy=true; muted=false; loud=false
        summary = if (caller) "Вызов…" else "Проверка приглашения"
        worker = scope.launch {
            val began = SystemClock.elapsedRealtime()
            var activeAt = 0L
            var rotated = began
            var lastStats = 0L
            var endingAt = 0L
            var sampledSecond = -15L
            val samples = java.util.ArrayDeque<JSONObject>()
            latestReport=""
            try {
                checked(VoiceBridge.begin(h,caller))
                while (isActive) {
                    val now = SystemClock.elapsedRealtime()
                    val event=withContext(Dispatchers.IO){checked(VoiceBridge.step(h))}
                    val state=event.getString("state")
                    if(state=="Hangup" || state=="Cancel" || state=="Reject") {
                        if(endingAt==0L) {
                            endingAt=now
                            audio?.stopCapture()
                            summary="Завершение · $state"
                        }
                        if(now-endingAt>3000) error("Нет подтверждения завершения")
                    }
                    if(event.getBoolean("finished")) {
                        // The responder's last reliable reply needs time to reach the initiator
                        // before dropping the enclosing QUIC connection.
                        if(!caller)delay(250)
                        summary="Завершён · $state"
                        break
                    }
                    if(event.getBoolean("incoming") && !incoming) {
                        incoming=true; summary="Входящий от $selected"
                        notices.notify(72,notice(summary))
                    }
                    if(state=="Active" && activeAt==0L) {
                        val engine = VoiceAudio(this@VoiceService,h) { reason ->
                            handler.post { if (activeHandle==h) {summary=reason;VoiceBridge.decide(h,false)} }
                        }
                        audio=engine
                        engine.start()
                        activeAt=now
                        wake.acquire(4*60*60*1000L)
                        summary="Разговор"; notices.notify(72,notice(summary))
                    }
                    if (caller && now-rotated>=60_000) {
                        withContext(Dispatchers.IO){ checked(VoiceBridge.rotate(h)) }; rotated=now
                    }
                    if (now-lastStats>=1000) {
                        val stats=withContext(Dispatchers.IO){ checked(VoiceBridge.stats(h)) }
                        val duration=if(activeAt==0L) 0L else (now-activeAt)/1000
                        val pss=Debug.getNativeHeapAllocatedSize()/1024
                        detail="CallID / FlowID $flow\nDirect QUIC · Noise XX · ${duration}s\n" +
                            "RTT %.1f ms · jitter %.1f ms · Opus %.1f kbit/s\n".format(
                                stats.getDouble("rttMs"),stats.getDouble("jitterMs"),
                                if(duration>0)stats.getLong("encodedBytes")*0.008/duration else 0.0) +
                            "RX ${stats.getLong("received")} · пропуски ≈${stats.getLong("missingEstimate")} · PLC ${stats.getLong("plc")} · late ${stats.getLong("late")}\n" +
                            "buffer ${stats.getInt("buffer")} / ${stats.getInt("target")} frames · auth errors ${stats.getLong("invalid")}\n" +
                            "AudioTrack underruns ${audio?.underruns ?: 0} · native heap $pss KiB · ${audio?.processing.orEmpty()}"
                        if (diagnostics && duration >= sampledSecond+15) {
                            sampledSecond=duration
                            stats.put("elapsedSeconds",duration).put("nativeHeapKiB",pss)
                                .put("audioTrackUnderruns",audio?.underruns ?: 0)
                                .put("processCpuMs",Process.getElapsedCpuTime())
                            if(samples.size==480)samples.removeFirst()
                            samples.addLast(stats)
                            android.util.Log.i("LP2LNVoice",stats.toString())
                        }
                        lastStats=now
                    }
                    delay(if(activeAt==0L) 150 else 450)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { summary="Звонок завершён: ${e.message}" }
            finally {
                val engine=audio
                audio=null
                withContext(NonCancellable + Dispatchers.IO) { try {engine?.close()} finally {Lp2lnRealtimeBridge.nativeClose(h)} }
                if(samples.isNotEmpty()) {
                    val report=JSONObject().put("callId",flow).put("flowId",flow)
                        .put("path","direct QUIC").put("e2e","Noise XX").put("caller",caller)
                        .put("samples",org.json.JSONArray(samples)).toString()
                    latestReport=report
                    withContext(NonCancellable+Dispatchers.IO) {
                        runCatching {java.io.File(filesDir,"last-call.json").writeText(report)}
                    }
                }
                if(activeHandle==h && instance===this@VoiceService) {
                    if(wake.isHeld)wake.release()
                    outgoing=false;activeHandle=0;incoming=false;busy=false;muted=false;loud=false
                    notices.notify(72,notice("Ожидание вызова"))
                }
            }
        }
    }
    private suspend fun closeHandle(h:Long) { withContext(NonCancellable+Dispatchers.IO){Lp2lnRealtimeBridge.nativeClose(h)} }
    internal fun accept() { if(activeHandle!=0L)VoiceBridge.decide(activeHandle,true); incoming=false }
    internal fun finishCall() {
        if (pendingDial!=0L) {pendingDial=0;busy=false;summary="Вызов отменён"}
        audio?.stopCapture()
        if(activeHandle!=0L)VoiceBridge.decide(activeHandle,false);incoming=false
    }
    internal fun toggleMute() { audio?.let { muted=!muted;it.mute=muted } }
    internal fun toggleSpeaker() { audio?.let { if(it.speaker(!loud)) loud=!loud } }
    override fun onDestroy() {
        if(selected.isNotEmpty()) runCatching { Lp2lnRealtimeBridge.nativeRevoke(selected,PROTOCOL) }
        audio?.stopCapture()
        scope.cancel()
        if(wake.isHeld)wake.release()
        if(multicast.isHeld)multicast.release()
        instance=null;incoming=false;busy=false;muted=false;loud=false
        NodeSession.release()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) : IBinder? = null
}
