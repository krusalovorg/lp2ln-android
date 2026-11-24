package lp2ln_android.krusalov.org.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lp2ln_android.krusalov.org.R

private data class GalleryItem(val uri: Uri, val duration: Long)
private fun recent(context: Context): List<GalleryItem> {
    val table = MediaStore.Files.getContentUri("external")
    return buildList {
        context.contentResolver.query(table, arrayOf("_id", "media_type", "duration"),
            "media_type IN (1,3)", null, "date_added DESC, _id DESC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val collection = if (cursor.getInt(1) == 3) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                add(GalleryItem(ContentUris.withAppendedId(collection, cursor.getLong(0)), cursor.getLong(2)))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GallerySheet(dismiss: () -> Unit, attach: (Uri) -> Unit,
    document: () -> Unit, camera: () -> Unit) {
    val context = LocalContext.current
    val permissions = remember { when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    } }
    var allowed by remember { mutableStateOf(permissions.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = it.values.any { granted -> granted }
    }
    var selected by remember { mutableStateOf<Uri?>(null) }
    val media by produceState(emptyList<GalleryItem>(), allowed) {
        if (allowed) value = withContext(Dispatchers.IO) { runCatching { recent(context) }.getOrDefault(emptyList()) }
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var files by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet) {
        BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight()) {
        val offset = with(LocalDensity.current) { runCatching { sheet.requireOffset().toDp() }.getOrDefault(0.dp) }
        Column(Modifier.fillMaxWidth().height((maxHeight - offset).coerceIn(0.dp, maxHeight)).navigationBarsPadding()) {
            if (files) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                FilledTonalButton(onClick = document) { Text("Выбрать файл на устройстве") }
            } else {

            if (!allowed) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { request.launch(permissions) }) { Text("Показать фото и видео") }
            }
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item { Box(Modifier.aspectRatio(1f).background(Color.DarkGray).clickable(onClick = camera),
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.chat_camera), "Снять фото", tint = Color.White)
                } }
                items(media, key = { it.uri.toString() }) { item ->
                    val bitmap by produceState<android.graphics.Bitmap?>(null, item.uri) {
                        value = withContext(Dispatchers.IO) { runCatching {
                            context.contentResolver.loadThumbnail(item.uri, Size(192,192), null)
                        }.getOrNull() }
                    }
                    Box(Modifier.aspectRatio(1f).background(Color.Gray).clickable {
                        selected = if (selected == item.uri) null else item.uri
                    }) {
                        bitmap?.let { Image(it.asImageBitmap(), if (item.duration > 0) "Видео" else "Фото",
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp)
                            .background(if (selected == item.uri) Color(0xFF007AFF) else Color.Black.copy(alpha=.25f), CircleShape)
                            .border(1.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                            if (selected == item.uri) Text("✓", color = Color.White, fontSize = 12.sp)
                        }
                        if (item.duration > 0) Text("%d:%02d".format(item.duration/60000, item.duration/1000%60),
                            Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha=.55f)).padding(3.dp),
                            color = Color.White, fontSize = 11.sp)
                    }
                }
            }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                listOf("Галерея", "Файлы").forEachIndexed { index, label ->
                    val selectedTab = files == (index == 1)
                    Column(Modifier.weight(1f).background(if (selectedTab) Color(0xFFE3EFFF) else Color.Transparent, CircleShape)
                        .clickable { files = index == 1 }.padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(painterResource(if (index == 0) R.drawable.chat_gallery else R.drawable.chat_file), null,
                            Modifier.size(22.dp), tint = if (selectedTab) Color(0xFF007AFF) else Color.DarkGray)
                        Text(label, fontSize = 11.sp)
                    }
                }
                if (!files) selected?.let { uri -> Button(onClick = { attach(uri) }) { Text("Отправить") } }
            }
        }
        }
    }
}
