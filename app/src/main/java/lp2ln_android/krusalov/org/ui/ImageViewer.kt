package lp2ln_android.krusalov.org.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import lp2ln_android.krusalov.org.network.*

@Composable
fun ImageViewer(file: StoredFile, state: ImageViewState, transfer: FileTransfer,
                dismiss: () -> Unit, retry: () -> Unit, save: () -> Unit) {
    var scale by remember(file.id, state.image) { mutableStateOf(1f) }
    var pan by remember(file.id, state.image) { mutableStateOf(Offset.Zero) }
    val bitmap = state.image ?: state.thumbnail
    val progress = transfer.takeIf { it.fileId == file.id && it.total > 0 && state.loading }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF8F8FA)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(file.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when {
                            state.image != null -> "Изображение готово"
                            state.error != null -> "Загрузка приостановлена"
                            state.thumbnail != null -> "Миниатюра · загружаем оригинал"
                            else -> "Открываем изображение…"
                        }, fontSize = 12.sp, color = Color(0xFF83858C))
                    }
                    TextButton(onClick = dismiss) { Text("Готово") }
                }
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFFEDEDF1)), contentAlignment = Alignment.Center) {
                    if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = file.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().pointerInput(state.image) {
                            if (state.image != null) detectTransformGestures { _, movement, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                pan = if (scale == 1f) Offset.Zero else Offset(
                                    (pan.x + movement.x).coerceIn(-size.width * (scale - 1) / 2, size.width * (scale - 1) / 2),
                                    (pan.y + movement.y).coerceIn(-size.height * (scale - 1) / 2, size.height * (scale - 1) / 2))
                            }
                        }.graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y })
                    else if (state.loading) CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
                    if (state.loading && bitmap != null) Surface(Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth(), color = Color.White.copy(alpha = 0.92f)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (progress != null) "Получено ${progress.done} из ${progress.total} частей"
                                else "Получаем изображение из сети…", fontSize = 12.sp)
                            if (progress != null) LinearProgressIndicator(
                                (progress.done.toFloat() / progress.total).coerceIn(0f, 1f), Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
                state.error?.let {
                    Text("Не удалось открыть изображение. Проверьте подключение и повторите.",
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = retry) { Text("Повторить") }
                }
                if (state.image != null) FilledTonalButton(onClick = save, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)) { Text("Сохранить оригинал") }
                else Text("Оригинал сохраняется в приложении. В «Загрузки» — только по вашему нажатию.",
                    fontSize = 12.sp, color = Color(0xFF83858C))
            }
        }
    }
}
