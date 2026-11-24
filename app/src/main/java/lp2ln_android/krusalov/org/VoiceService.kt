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

internal fun voiceEndpoints(peer: String, nodes: List<KnownNodeAddress>): List<String> =
    nodes.asSequence().filter { it.peerId == peer && it.protocol == "quic" }
        .map { it.address }.filter { it.isNotBlank() }.distinct().take(3).toList()

class VoiceService : Service() {
    companion object {
        internal const val PROTOCOL = 0xff82
        internal var instance by mutableStateOf<VoiceService?>(null)
        internal var summary by mutableStateOf("Остановлен")
        internal var callPeer by mutableStateOf("")
        internal var error by mutableStateOf<String?>(null)
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
    private lateinit var node: NetworkViewModel
    private val authorized = mutableSetOf<String>()
    private var manualPeer = ""
    private val lastInvite = mutableMapOf<String,Long>()
    private fun foreground(microphone: Boolean) {
        val types = if(Build.VERSION.SDK_INT >= 34) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING or
                (if(microphone) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } else if(Build.VERSION.SDK_INT>=30 && microphone) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        startForeground(72, notice(if(microphone)"Звонок" else "Входящие звонки включены"), types)
    }
    private fun updateAllowed() {
        val state=node.state.value
        self=state.peerId
        val wanted=linkedSetOf(manualPeer).apply {
            addAll(state.messages.asReversed().take(128).map { it.peerId })
            addAll(state.knownNodes.map { it.peerId })
        }.filter { it.length==66 && it!=self }.take(128).toSet()
        for(p in authorized-wanted) {
            Lp2lnRealtimeBridge.nativeRevoke(p,PROTOCOL); authorized.remove(p);lastInvite.remove(p)
        }
        for(p in wanted-authorized) {
            if(JSONObject(Lp2lnRealtimeBridge.nativeAuthorize(p,PROTOCOL)).optBoolean("ok")) authorized.add(p)
        }
    }
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
        notices.createNotificationChannel(NotificationChannel("voice-incoming","Входящие звонки",NotificationManager.IMPORTANCE_HIGH))
        foreground(false)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"lp2ln:voice")
        multicast = (applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
            .createMulticastLock("lp2ln-voice-discovery")
        multicast.acquire()
        node=NodeSession.acquire()
        node.connect(filesDir)
        scope.launch { listen() }
        instance = this
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        val peer = intent?.getStringExtra("peer").orEmpty().trim()
        val address = intent?.getStringExtra("endpoint").orEmpty().trim()
        if(peer.isNotEmpty() && !busy) {
            manualPeer=peer
            updateAllowed()
            if(address.isNotEmpty() || intent?.action=="call") {
                val addresses=if(address.isNotEmpty())listOf(address) else
                    voiceEndpoints(peer, node.state.value.knownNodes)
                if(addresses.isEmpty()) error="У собеседника пока нет доступного прямого пути для звонка."
                else {
                    selected=peer;callPeer=peer
                    if (enableMicrophoneService()) dial(addresses)
                }
            }
        }
        return START_NOT_STICKY
    }
    private suspend fun listen() {
        while (currentCoroutineContext().isActive) {
            updateAllowed()
            val raw = withContext(NonCancellable + Dispatchers.IO) { JSONObject(Lp2lnRealtimeBridge.nativeAccept(1000)) }
            if (!raw.optBoolean("ok")) { delay(100); continue }
            val h = raw.getLong("handle")
            if (!currentCoroutineContext().isActive) { closeHandle(h); return }
            val remote=raw.getString("peerId")
            if (remote !in authorized || raw.getInt("protocol") != PROTOCOL ||
                (busy && (remote!=selected || self < selected || audio != null || (activeHandle != 0L && !outgoing)))) {
                closeHandle(h); continue
            }
            // Both peers dialled. The lower Peer ID remains the initiator.
            val now=SystemClock.elapsedRealtime()
            if (!busy && now-(lastInvite[remote] ?: -6000L)<5000L) {closeHandle(h);continue}
            lastInvite[remote]=now
            selected=remote;callPeer=remote
            ++ticket; pendingDial = 0
            worker?.cancelAndJoin()
            begin(raw, false)
        }
    }
    private fun dial(addresses: List<String>) {
        if (busy) return
        val peer=selected
        val generation = ++ticket
        pendingDial = generation
        busy = true; summary = "Соединение"
        scope.launch {
            try {
                var raw:JSONObject?=null
                for(address in addresses) {
                    if(!isActive || generation!=pendingDial) break
                    val attempt=withContext(NonCancellable+Dispatchers.IO) {JSONObject(Lp2lnRealtimeBridge.nativeOpen(peer,address,PROTOCOL))}
                    if(attempt.optBoolean("ok")) {raw=attempt;break}
                }
                if(raw==null) throw IllegalStateException("Собеседник недоступен для прямого звонка")
                if (!isActive || pendingDial != generation) closeHandle(raw.getLong("handle"))
                else { pendingDial = 0; begin(raw, true) }
            } catch (e: Exception) {
                if (pendingDial == generation) { pendingDial = 0; busy = false; summary = "Соединение: ${e.message}";error=summary;foreground(false) }
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
                        incoming=true
                        val label=getSharedPreferences("contacts",MODE_PRIVATE).getString(selected,null)
                            ?: node.state.value.messages.lastOrNull { it.peerId==selected && !it.outgoing }?.senderName ?: peerLabel(selected)
                        summary="Входящий от $label"
                        val open=PendingIntent.getActivity(this@VoiceService,7,Intent(this@VoiceService,MainActivity::class.java)
                            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE)
                        notices.notify(73,Notification.Builder(this@VoiceService,"voice-incoming")
                            .setSmallIcon(R.drawable.ic_lp2ln_status).setCategory(Notification.CATEGORY_CALL)
                            .setContentTitle(label).setContentText("Входящий голосовой звонок")
                            .setTimeoutAfter(30000).setContentIntent(open).setAutoCancel(true).build())
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
            catch (e: Exception) { summary="Звонок завершён: ${e.message}";error=summary }
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
                    notices.cancel(73);foreground(false)
                }
            }
        }
    }
    private suspend fun closeHandle(h:Long) { withContext(NonCancellable+Dispatchers.IO){Lp2lnRealtimeBridge.nativeClose(h)} }
    private fun enableMicrophoneService(): Boolean = try {
        foreground(true)
        true
    } catch (denied: SecurityException) {
        error = "Откройте приложение и разрешите доступ к микрофону."
        false
    }

    internal fun accept(expected: Long) {
        if (!incoming || expected != activeHandle) return
        if (!enableMicrophoneService()) { finishCall(); return }
        VoiceBridge.decide(activeHandle, true)
        incoming = false
        notices.cancel(73)
    }
    internal fun ringingHandle()=activeHandle
    internal fun finishCall() {
        if (pendingDial!=0L) {pendingDial=0;busy=false;summary="Вызов отменён";foreground(false)}
        audio?.stopCapture()
        if(activeHandle!=0L)VoiceBridge.decide(activeHandle,false);incoming=false
    }
    internal fun toggleMute() { audio?.let { muted=!muted;it.mute=muted } }
    internal fun toggleSpeaker() { audio?.let { if(it.speaker(!loud)) loud=!loud } }
    override fun onDestroy() {
        authorized.forEach { runCatching { Lp2lnRealtimeBridge.nativeRevoke(it,PROTOCOL) } }; notices.cancel(73)
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
