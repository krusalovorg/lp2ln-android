package lp2ln_android.krusalov.org.ui

import android.Manifest
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import lp2ln_android.krusalov.org.R
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

private val attachment = Regex("lp2ln-media/1/(media|voice|circle)/[0-9a-fA-F]{64}")

internal fun attachmentLabel(text: String): String? = when (attachment.matchEntire(text)?.groupValues?.get(1)) {
    "voice" -> "Голосовое"
    "circle" -> "Видеокружок"
    "media" -> "Вложение"
    else -> null
}

private fun Context.recording(suffix: String): File =
    File.createTempFile("chat-", suffix, File(cacheDir, "recordings").apply { mkdirs() })

private fun Context.uri(file: File): Uri = FileProvider.getUriForFile(this, "${packageName}.files", file)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentComposer(peer: String, enabled: Boolean, send: (String, Uri, String) -> Unit,
    hasText: Boolean, canSend: Boolean, submit: () -> Unit, field: @Composable RowScope.() -> Unit) {
    val context = LocalContext.current
    var sheet by rememberSaveable { mutableStateOf(false) }
    var destination by rememberSaveable { mutableStateOf(peer) }
    var photoPath by rememberSaveable { mutableStateOf<String?>(null) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        photoPath?.let { path ->
            val file = File(path)
            if (ok && file.length() > 0) send(destination, context.uri(file), "media") else file.delete()
        }
        photoPath = null
    }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { send(destination, it, "media") }
    }
    fun takePhoto() {
        runCatching {
            val file = context.recording(".jpg")
            photoPath = file.absolutePath
            photo.launch(context.uri(file))
        }.onFailure { photoPath?.let { File(it).delete() }; photoPath = null
            Toast.makeText(context, "Камера недоступна", Toast.LENGTH_SHORT).show() }
    }
    val cameraAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) takePhoto()
    }
    RecordComposer(peer, enabled, send, hasText, canSend, submit) {
        field()
        IconButton(onClick = { destination = peer; sheet = true }, enabled = enabled) {
            Icon(painterResource(R.drawable.chat_clip), "Прикрепить", modifier = Modifier.size(23.dp))
        }
    }
    if (sheet) GallerySheet(dismiss = { sheet = false },
        attach = { sheet = false; send(destination, it, "media") },
        document = { sheet = false; document.launch(arrayOf("*/*")) },
        camera = {
            sheet = false
            if (context.checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                takePhoto() else cameraAccess.launch(Manifest.permission.CAMERA)
        })
}
