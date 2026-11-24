package lp2ln_android.krusalov.org

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import lp2ln_android.krusalov.org.network.VoiceBridge
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Blocking platform audio runs only on dedicated workers. No audio callback enters JNI. */
internal class VoiceAudio(private val context: Context, private val handle: Long,
    private val failure: (String) -> Unit) : Closeable {
    private val running = AtomicBoolean(false)
    @Volatile var mute = false
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener {
            if (it < 0) failure("Потерян аудиофокус")
        }.build()
    private var focused = false
    private var oldMode = manager.mode
    private var oldSpeaker = false
    private var oldDevice: AudioDeviceInfo? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    val underruns get() = track?.underrunCount ?: 0
    private val workers = mutableListOf<Thread>()
    private val frame = 960
    var processing = "platform VOICE_COMMUNICATION"
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        require(JSONObject(VoiceBridge.startMedia(handle)).optBoolean("ok"))
        check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        focused = true
        oldMode = manager.mode
        @Suppress("DEPRECATION")
        oldSpeaker = manager.isSpeakerphoneOn
        if (Build.VERSION.SDK_INT >= 31) oldDevice = manager.communicationDevice
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        val inputSize = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val outputSize = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(inputSize > 0 && outputSize > 0) { "PCM 48 kHz unavailable" }
        val input = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 48000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(inputSize, frame * 4))
        record = input
        val output = AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(frame * 4, outputSize)).setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
        track = output
        check(input.state == AudioRecord.STATE_INITIALIZED && output.state == AudioTrack.STATE_INITIALIZED)
        // One platform effect chain only. Devices may already enable these effects by audio source.
        aec = runCatching { AcousticEchoCanceler.create(input.audioSessionId)?.also { it.enabled = true } }.getOrNull()
        ns = runCatching { NoiseSuppressor.create(input.audioSessionId)?.also { it.enabled = true } }.getOrNull()
        processing = "platform AEC=${aec?.enabled} NS=${ns?.enabled}"
        input.startRecording()
        check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        output.play()
        running.set(true)
        worker("voice-rx", false) { VoiceBridge.ingress(handle) }
        val captured = ShortArray(frame)
        val stamp = AudioTimestamp()
        var capturePosition = 0L
        worker("voice-capture", true) {
            var offset = 0
            while (running.get() && offset < frame) {
                val n = input.read(captured, offset, frame-offset, AudioRecord.READ_BLOCKING)
                check(n > 0); offset += n
            }
            if (running.get()) {
                val now = System.nanoTime()
                // First sample's age on the recording clock. Without a hardware
                // timestamp, use the capacity of the platform capture buffer.
                val ageNs = if(input.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC)==AudioRecord.SUCCESS) {
                    now-stamp.nanoTime+(stamp.framePosition-capturePosition)*1_000_000_000L/48000
                } else input.bufferSizeInFrames*1_000_000_000L/48000
                capturePosition+=frame
                if (mute) captured.fill(0)
                check(VoiceBridge.capture(handle, captured, (ageNs.coerceIn(0,1_000_000_000L)/1_000_000).toInt()) >= 0)
            }
        }
        val rendered = ShortArray(frame)
        worker("voice-playout", true) {
            check(VoiceBridge.play(handle, rendered) == 0)
            var offset = 0
            while (running.get() && offset < frame) {
                val n = output.write(rendered, offset, frame-offset, AudioTrack.WRITE_BLOCKING)
                check(n > 0); offset += n
            }
        }
    }
    private fun worker(name: String, audio: Boolean, job: ()->Unit) {
        workers += thread(name = name) {
            try {
                if (audio) Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                while (running.get()) job()
            } catch (e: Exception) {
                if (running.compareAndSet(true, false)) failure("$name: ${e.message}")
            }
        }
    }
    @Suppress("DEPRECATION")
    fun speaker(enabled: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            val type = if (enabled) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val device = manager.availableCommunicationDevices.firstOrNull { it.type == type } ?: return false
            return manager.setCommunicationDevice(device)
        }
        manager.isSpeakerphoneOn = enabled
        return true
    }
    fun stopCapture() {
        running.set(false)
        runCatching { record?.stop() }
    }
    override fun close() {
        stopCapture()
        runCatching { track?.pause(); track?.flush() }
        workers.forEach { it.join(600) }
        aec?.release(); ns?.release()
        record?.release(); track?.release()
        if (focused) {
            if (Build.VERSION.SDK_INT >= 31) {
                val old = oldDevice
                if (old == null || !manager.setCommunicationDevice(old)) manager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                manager.isSpeakerphoneOn = oldSpeaker
            }
            manager.mode = oldMode
            manager.abandonAudioFocusRequest(focus)
            focused = false
        }
    }
}
