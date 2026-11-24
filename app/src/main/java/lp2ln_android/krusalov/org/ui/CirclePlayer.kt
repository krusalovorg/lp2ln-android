package lp2ln_android.krusalov.org.ui

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lp2ln_android.krusalov.org.network.*
import java.io.File

@Composable
internal fun CirclePlayer(file: StoredFile) {
    val context = LocalContext.current
    var play by remember(file.id) { mutableStateOf(false) }
    var error by remember(file.id) { mutableStateOf(false) }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) play = false }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Box(Modifier.padding(5.dp).size(155.dp).clip(CircleShape).background(Color.DarkGray)
        .clickable { play = !play; error = false }, contentAlignment = Alignment.Center) {
        if (!play) Text(if (error) "Повторить" else "▶ Кружок", color = Color.White)
        else {
            var source by remember { mutableStateOf<File?>(null) }
            LaunchedEffect(file.id) {
                val temporary = File.createTempFile("circle-", ".mp4", context.cacheDir)
                try {
                    withContext(Dispatchers.IO) { Lp2lnRepository().export(file.id, temporary.absolutePath) }
                    source = temporary
                    kotlinx.coroutines.awaitCancellation()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { error = true; play = false }
                finally { temporary.delete() }
            }
            source?.let { video ->
                val player = remember { MediaPlayer() }
                DisposableEffect(player) { onDispose { player.release() } }
                AndroidView(modifier = Modifier.fillMaxSize(), factory = {
                    TextureView(it).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) {
                                val surface = Surface(texture)
                                try {
                                    player.setSurface(surface)
                                    player.setDataSource(video.absolutePath)
                                    player.setOnPreparedListener { mp ->
                                        val ratio = mp.videoWidth.toFloat() / mp.videoHeight.coerceAtLeast(1)
                                        val matrix = android.graphics.Matrix()
                                        matrix.setScale(ratio.coerceAtLeast(1f), (1f / ratio).coerceAtLeast(1f), w / 2f, h / 2f)
                                        setTransform(matrix)
                                        mp.start()
                                    }
                                    player.setOnCompletionListener { play = false }
                                    player.setOnErrorListener { _, _, _ -> error = true; play = false; true }
                                    player.prepareAsync()
                                } catch (_: Exception) { error = true; play = false }
                                finally { surface.release() }
                            }
                            override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) = Unit
                            override fun onSurfaceTextureUpdated(t: SurfaceTexture) = Unit
                            override fun onSurfaceTextureDestroyed(t: SurfaceTexture) = true
                        }
                    }
                })
            }
        }
    }
}
