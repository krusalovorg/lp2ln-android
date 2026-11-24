package lp2ln_android.krusalov.org.ui

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

@androidx.annotation.OptIn(ExperimentalPersistentRecording::class)
internal class ChatRecording(val context: Context, val video: Boolean, val owner: LifecycleOwner,
    private val result: (File?, Boolean) -> Unit) {
    private val file = File.createTempFile("chat-", if (video) ".mp4" else ".m4a",
        File(context.cacheDir, "recordings").apply { mkdirs() })
    private var mic: MediaRecorder? = null
    private var stream: Recording? = null
    private var provider: ProcessCameraProvider? = null
    private var capture: VideoCapture<Recorder>? = null
    private var preview: Preview? = null
    private var facing = CameraSelector.DEFAULT_FRONT_CAMERA
    private var closed = false
    private var submit = false

    private var voiceStart = 0L
    private var videoDurationNanos = 0L
    val durationSeconds: Int get() = if (video) (videoDurationNanos / 1_000_000_000).toInt()
        else ((android.os.SystemClock.elapsedRealtime() - voiceStart) / 1000).toInt()
    val amplitude get() = runCatching { mic?.maxAmplitude ?: 0 }.getOrDefault(0)
    fun startVoice() {
        @Suppress("DEPRECATION")
        val audio = MediaRecorder()
        mic = audio
        audio.setAudioSource(MediaRecorder.AudioSource.MIC)
        audio.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        audio.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        audio.setOutputFile(file.absolutePath)
        audio.prepare()
        audio.start()
        voiceStart = android.os.SystemClock.elapsedRealtime()
    }

    @SuppressLint("MissingPermission")
    fun startVideo(view: PreviewView) {
        val executor = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (closed) return@addListener
            runCatching {
                val cameras = future.get()
                provider = cameras
                if (!cameras.hasCamera(facing)) facing = CameraSelector.DEFAULT_BACK_CAMERA
                val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.SD,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.SD))).build()
                capture = VideoCapture.withOutput(recorder)
                preview = Preview.Builder().build().apply { setSurfaceProvider(view.surfaceProvider) }
                bind(facing)
                stream = recorder.prepareRecording(context, FileOutputOptions.Builder(file).build())
                    .withAudioEnabled().asPersistentRecording().start(executor) { event ->
                        videoDurationNanos = event.recordingStats.recordedDurationNanos
                        if (event is VideoRecordEvent.Finalize) {
                            val unexpected = !closed
                            closed = true
                            unbind()
                            stream = null
                            complete(submit && !event.hasError(), unexpected || (submit && event.hasError()))
                        }
                    }
            }.onFailure {
                closed = true
                unbind()
                complete(false, true)
            }
        }, executor)
    }

    private fun bind(camera: CameraSelector) {
        requireNotNull(provider).bindToLifecycle(owner, camera, requireNotNull(preview), requireNotNull(capture))
    }
    private fun unbind() {
        val cases = listOfNotNull(preview, capture)
        if (cases.isNotEmpty()) provider?.unbind(*cases.toTypedArray())
    }
    fun flip(): Boolean {
        if (closed || stream == null) return false
        val next = if (facing == CameraSelector.DEFAULT_FRONT_CAMERA) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        return runCatching {
            if (provider?.hasCamera(next) != true) return false
            unbind()
            bind(next)
            facing = next
            true
        }.getOrElse { runCatching { bind(facing) }.onFailure { finish(false) }; false }
    }

    fun finish(send: Boolean) {
        if (closed) return
        closed = true
        submit = send
        val recording = stream
        if (recording != null) {
            recording.stop()
            return
        }
        val valid = mic?.let { runCatching { it.stop() }.isSuccess } == true
        mic?.release()
        mic = null
        unbind()
        complete(send && valid, send && !valid)
    }
    private fun complete(keep: Boolean, failed: Boolean) {
        if (!keep) file.delete()
        result(file.takeIf { keep }, failed)
    }
}
