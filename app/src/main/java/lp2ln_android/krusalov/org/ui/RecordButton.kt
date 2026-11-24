@file:Suppress("DEPRECATION")

package lp2ln_android.krusalov.org.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.camera.view.PreviewView
import android.view.HapticFeedbackConstants
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.*
import androidx.core.content.FileProvider
import androidx.lifecycle.*
import kotlinx.coroutines.delay
import lp2ln_android.krusalov.org.R

private object ScreenCenter : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize) = IntOffset((windowSize.width-popupContentSize.width)/2,
            (windowSize.height-popupContentSize.height)/2)
}

@Composable
internal fun RecordComposer(peer: String, enabled: Boolean, send: (String, Uri, String) -> Unit, hasText: Boolean,
    canSend: Boolean, submitText: () -> Unit, field: @Composable RowScope.() -> Unit) {
    val context = LocalContext.current
    var video by rememberSaveable { mutableStateOf(false) }
    var active by remember { mutableStateOf<ChatRecording?>(null) }
    var locked by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    val lifecycle = owner.lifecycle
    val view = LocalView.current
    var drag by remember { mutableStateOf(Offset.Zero) }
    var discardOnRelease by remember { mutableStateOf(false) }
    var elapsed by remember { mutableIntStateOf(0) }
    val levels = remember { mutableStateListOf<Float>() }
    fun feedback() { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    fun notice(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        notice(if (it.values.all { granted -> granted }) "Удерживайте кнопку для записи" else "Для записи нужен доступ")
    }
    fun finish(submit: Boolean) {
        val session = active ?: return
        active = null
        locked = false
        drag = Offset.Zero
        discardOnRelease = false
        feedback()
        session.finish(submit)
    }
    fun start() {
        val needed = if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            else arrayOf(Manifest.permission.RECORD_AUDIO)
        if (needed.any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            request.launch(needed)
            return
        }
        runCatching {
            val isVideo = video
            lateinit var session: ChatRecording
            session = ChatRecording(context, isVideo, owner) { file, failed ->
                if (active === session) { active = null; locked = false; drag = Offset.Zero; discardOnRelease = false }
                if (file != null) send(peer, FileProvider.getUriForFile(context, "${context.packageName}.files", file),
                    if (isVideo) "circle" else "voice")
                if (failed) notice("Не удалось сохранить запись")
            }
            active = session
            elapsed = 0
            levels.clear()
            if (!video) session.startVoice()
            feedback()
        }.onFailure { finish(false); notice("Не удалось начать запись") }
    }
    DisposableEffect(lifecycle, peer) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) finish(false) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); finish(false) }
    }
    val currentStart by rememberUpdatedState(::start)
    val currentFinish by rememberUpdatedState(::finish)
    Row(Modifier.fillMaxWidth().imePadding().padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.weight(1f).heightIn(min = 46.dp).background(Color(0xFFF1F2F5), CircleShape),
            verticalAlignment = Alignment.CenterVertically) {
            val session = active
            if (session == null) field() else {
                if (session.video) IconButton(onClick = {
                    if (session.flip()) feedback() else notice("Камера недоступна")
                }) { Icon(painterResource(R.drawable.chat_flip), "Перевернуть камеру") }
                Text("%d:%02d".format(elapsed/60, elapsed%60), Modifier.padding(horizontal = 12.dp))
                if (!session.video) Canvas(Modifier.weight(1f).height(28.dp)) {
                    val step = 4.dp.toPx()
                    val values = levels.takeLast((size.width/step).toInt())
                    values.forEachIndexed { i, sample ->
                        val half = (size.height * sample / 2).coerceAtLeast(1.dp.toPx())
                        drawLine(Color(0xFF438CEF), Offset(i*step, center.y-half), Offset(i*step, center.y+half),
                            strokeWidth = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                }
                if (locked) TextButton(onClick = { finish(false) }) { Text("Отмена") }
            }
        }
        if (hasText && active == null) FilledIconButton(onClick = submitText, enabled = canSend, modifier = Modifier.size(46.dp)) {
            Icon(painterResource(R.drawable.chat_send), "Отправить")
        } else Box(Modifier.size(46.dp)
        .pointerInput(peer, enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                if (locked) { currentFinish(true); return@awaitEachGesture }
                val lockDistance = 72.dp.toPx()
                val discardDistance = 96.dp.toPx()
                var axis: RecordDragAxis? = null
                var switched = false
                var released = false
                try {
                    val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val change = awaitPointerEvent().changes.first { it.id == down.id }
                            if (change.position.y - down.position.y > 17.dp.toPx()) {
                                video = !video; feedback(); switched = true; change.consume(); break
                            }
                            if (!change.pressed) { released = true; break }
                        }
                        true
                    }
                    if (early == null) {
                        currentStart()
                        while (true) {
                            val change = awaitPointerEvent().changes.first { it.id == down.id }
                            val delta = change.position - down.position
                            if (active != null && !locked) {
                                axis = recordDragAxis(axis, delta.x, delta.y, viewConfiguration.touchSlop)
                                drag = when (axis) {
                                    RecordDragAxis.LEFT -> Offset(delta.x.coerceIn(-discardDistance, 0f), 0f)
                                    RecordDragAxis.UP -> Offset(0f, delta.y.coerceIn(-lockDistance, 0f))
                                    null -> Offset.Zero
                                }
                                val armed = drag.x <= -discardDistance
                                if (armed && !discardOnRelease) feedback()
                                discardOnRelease = armed
                                if (drag.y <= -lockDistance) {
                                    locked = true
                                    drag = Offset.Zero
                                    feedback()
                                }
                            }
                            change.consume()
                            if (!change.pressed) {
                                if (!locked) currentFinish(!discardOnRelease)
                                break
                            }
                        }
                    } else if (released && !switched) { video = !video; feedback() }
                } finally { if (!locked) currentFinish(false) }
            }
        }.semantics { contentDescription = if (locked) "Отправить запись" else
            (if (video) "Видеокружок" else "Голосовое") + ". Удерживайте для записи, смахните вниз для смены режима" },
        contentAlignment = Alignment.Center) {
        if (active != null && !locked) {
            Box(Modifier.offset(x = (-96).dp).size(36.dp)
                .background(if (discardOnRelease) Color(0xFFC02E3A) else Color(0xFFF8E0E2), CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.chat_discard), "Корзина", Modifier.size(18.dp), tint = Color(0xFF912431))
            }
        }
        Box(Modifier.size(46.dp).graphicsLayer {
            translationX = drag.x; translationY = drag.y
            scaleX = if (active != null && !locked) 1.18f else 1f; scaleY = scaleX
        }.background(if (active != null) Color(0xFFD64A52) else if (enabled) Color(0xFF007AFF) else Color.Gray, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(if (discardOnRelease) R.drawable.chat_discard else if (locked) R.drawable.chat_send else if (video) R.drawable.chat_camera else R.drawable.chat_mic),
                null, tint = Color.White)
        }
    }
    }
    active?.let { session ->
        LaunchedEffect(session) {
            while (elapsed < 60) {
                delay(90)
                elapsed = session.durationSeconds
                if (!session.video) {
                    levels.add(kotlin.math.sqrt(session.amplitude.coerceIn(0,32767)/32767f))
                    if (levels.size > 100) levels.removeAt(0)
                }
            }
            if (!discardOnRelease) finish(true)
        }
        if (session.video) {
            val config = LocalConfiguration.current
            val diameter = (minOf(config.screenWidthDp, config.screenHeightDp) * .84f).dp
            Popup(popupPositionProvider = ScreenCenter, properties = PopupProperties(focusable = false)) {
                Box(Modifier.size(diameter).clip(CircleShape).background(Color.Black)) {
                    AndroidView(factory = { PreviewView(it).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        session.startVideo(this)
                    } }, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
