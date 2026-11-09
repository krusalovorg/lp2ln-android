package lp2ln_android.krusalov.org.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import lp2ln_android.krusalov.org.network.PeerInvitation
import lp2ln_android.krusalov.org.network.StoredFile

@Composable
fun ReceiveFileDialog(peerId: String, dismiss: () -> Unit) {
    val invitation = remember(peerId) { runCatching { PeerInvitation.encode(peerId) }.getOrNull() }
    val bitmap = remember(invitation) {
        invitation?.let { runCatching { BarcodeEncoder().encodeBitmap(it, BarcodeFormat.QR_CODE, 600, 600).asImageBitmap() }.getOrNull() }
    }
    val clipboard = LocalClipboardManager.current
    Dialog(onDismissRequest = dismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = Color.White) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Получить файл", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text("Покажите QR другу", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                if (bitmap != null) Image(bitmap, contentDescription = "QR вашего узла",
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White))
                else CircularProgressIndicator(Modifier.padding(48.dp))
                Text(peerId.take(10) + "…" + peerId.takeLast(8), fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Оставьте приложение открытым. Полученный файл появится в вашей библиотеке.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { invitation?.let { clipboard.setText(AnnotatedString(it)) } },
                    enabled = invitation != null) { Text("Копировать адрес") }
                FilledTonalButton(onClick = dismiss, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text("Готово", Modifier.padding(4.dp))
                }
            }
        }
    }
}

@Composable
fun SendFileDialog(file: StoredFile, ownPeer: String, busy: Boolean, send: (String) -> Unit, dismiss: () -> Unit) {
    var address by rememberSaveable(file.id) { mutableStateOf("") }
    var scanCancelled by remember { mutableStateOf(false) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { address = it; scanCancelled = false }
            ?: run { scanCancelled = true }
    }
    val peer = remember(address) { PeerInvitation.parse(address) }
    val valid = peer != null && peer != ownPeer
    AlertDialog(onDismissRequest = dismiss,
        title = { Text("Отправить другу", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(file.name, fontWeight = FontWeight.Medium)
                Text("Сканируйте QR друга или вставьте его адрес.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("QR друга из LP2LN").setBeepEnabled(false).setOrientationLocked(false))
                }, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text("Сканировать QR", Modifier.padding(4.dp))
                }
                OutlinedTextField(value = address, onValueChange = { address = it }, singleLine = true,
                    label = { Text("Адрес друга") }, shape = RoundedCornerShape(14.dp),
                    isError = address.isNotBlank() && !valid, modifier = Modifier.fillMaxWidth())
                if (peer == ownPeer) Text("Это ваш собственный адрес", color = MaterialTheme.colorScheme.error)
                if (scanCancelled) Text("Сканирование отменено. Адрес можно вставить вручную.", fontSize = 12.sp)
                Text(if (file.uploaded) "Друг получит ключ и сможет скачать зашифрованную копию из сети."
                    else "Пока файл хранится на вашем телефоне. Оставьте приложение открытым, пока друг скачивает файл.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { peer?.let(send) }, enabled = valid && !busy) { Text("Отправить") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } },
        shape = RoundedCornerShape(26.dp), containerColor = Color.White, tonalElevation = 0.dp)
}
