package lp2ln_android.krusalov.org

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import lp2ln_android.krusalov.org.network.*
import lp2ln_android.krusalov.org.ui.theme.Lp2lnandroidTheme

private val Blue = Color(0xFF3478F6)
private val Secondary = Color(0xFF83858C)
private val DividerColor = Color(0xFFE9E9EE)
private val Green = Color(0xFF26865D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Lp2lnandroidTheme { NetworkRoute() } }
    }
}

@Composable
private fun NetworkRoute(viewModel: NetworkViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val fileBusy by viewModel.fileBusy.collectAsState()
    val notice by viewModel.notice.collectAsState()
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importFile(context.applicationContext, it) }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = state.files.find { it.id == exportId }
        if (uri != null && file != null) viewModel.exportFile(context.applicationContext, file, uri)
        exportId = null
    }
    LaunchedEffect(Unit) { viewModel.connect(context.filesDir) }
    NetworkScreen(state, fileBusy, notice, viewModel::clearNotice,
        { picker.launch(arrayOf("*/*")) }, viewModel::retryFile,
        { exportId = it.id; saver.launch(it.name) }, viewModel::retry, viewModel::reconnect)
}

@Composable
private fun NetworkScreen(
    state: NetworkState, fileBusy: Boolean, notice: String?, dismissNotice: () -> Unit,
    onAdd: () -> Unit, onRetryFile: (StoredFile) -> Unit, onSave: (StoredFile) -> Unit,
    onRetry: () -> Unit, onReconnect: (String) -> Unit, initialTab: Int = 0,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var search by rememberSaveable { mutableStateOf("") }
    var selectedFile by remember { mutableStateOf<StoredFile?>(null) }
    val busy = fileBusy || state.transfer.busy
    val available = state.phase in listOf(ConnectionPhase.CONNECTED, ConnectionPhase.SEARCHING, ConnectionPhase.DEGRADED)
    val visibleFiles = remember(state.files, search) { state.files.filter { it.name.contains(search, ignoreCase = true) } }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) { if (notice != null) { snackbar.showSnackbar(notice); dismissNotice() } }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(color = Color.White) {
                Column {
                    Divider(color = DividerColor)
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 6.dp, bottom = 8.dp)) {
                        listOf("Файлы", "Сеть").forEachIndexed { index, title ->
                            val color = if (tab == index) Blue else Secondary
                            Column(Modifier.weight(1f).clickable { tab = index }.padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                LineIcon(if (index == 0) "folder" else "network", color, Modifier.size(24.dp))
                                Text(title, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = color)
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("LP2LN", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Secondary)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(6.dp).background(if (state.phase == ConnectionPhase.CONNECTED) Green else Secondary, CircleShape))
                            Text(statusText(state.phase), fontSize = 12.sp, color = Secondary)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (tab == 0) "Файлы" else "Сеть", fontSize = 34.sp, lineHeight = 40.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
                        if (tab == 0) IconButton(onClick = onAdd, enabled = available && !busy,
                            modifier = Modifier.size(44.dp).background(if (available && !busy) Blue else DividerColor, CircleShape)
                                .semantics { contentDescription = "Добавить файл" }) {
                            LineIcon("plus", Color.White, Modifier.size(23.dp))
                        }
                    }
                    if (tab == 0) {
                        OutlinedTextField(
                            value = search, onValueChange = { search = it }, singleLine = true,
                            placeholder = { Text("Поиск файлов", color = Secondary, fontSize = 16.sp) },
                            leadingIcon = { LineIcon("search", Secondary, Modifier.size(20.dp)) },
                            trailingIcon = if (search.isNotEmpty()) { { IconButton(onClick = { search = "" },
                                modifier = Modifier.semantics { contentDescription = "Очистить поиск" }) { Text("×", fontSize = 24.sp, color = Secondary) } } } else null,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = Color(0xFFECECF0), focusedContainerColor = Color(0xFFECECF0),
                                unfocusedBorderColor = Color.Transparent, focusedBorderColor = Color.Transparent),
                        )
                    }
                }
            }
            if (tab == 0) {
                if (busy || state.transfer.error != null) item {
                    Group {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (busy) state.transfer.stage.ifBlank { "Подготовка…" } else "Не отправлено",
                                fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            if (busy && state.transfer.total > 0) Text(
                                "${(state.transfer.done * 100 / state.transfer.total).coerceIn(0, 100)}%",
                                color = Secondary, fontSize = 13.sp)
                        }
                        if (state.transfer.name.isNotBlank()) Text(state.transfer.name, color = Secondary, fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (busy) {
                            if (state.transfer.total > 0) LinearProgressIndicator(
                                (state.transfer.done.toFloat() / state.transfer.total).coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth(), color = Blue, trackColor = DividerColor)
                            else LinearProgressIndicator(Modifier.fillMaxWidth(), color = Blue, trackColor = DividerColor)
                        } else Text(state.transfer.error.orEmpty(), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
                if (!available) item {
                    TextButton(onClick = { tab = 1 }, contentPadding = PaddingValues(0.dp)) { Text("Настроить подключение") }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (search.isBlank()) "Недавние" else "Результаты", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(visibleFiles.size.toString(), fontSize = 14.sp, color = Secondary)
                    }
                }
                if (visibleFiles.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        LineIcon("folder", Color(0xFFB6C5DF), Modifier.size(56.dp))
                        Text(if (search.isBlank()) "Пока нет файлов" else "Ничего не найдено", color = Secondary, fontSize = 16.sp)
                    }
                }
                items(visibleFiles, key = { it.id }) { file ->
                    FileRow(file, busy, available, { onRetryFile(file) }, { onSave(file) }, { selectedFile = file })
                }
            } else item { NetworkDetails(state, busy, onRetry, onReconnect) }
        }
    }
    selectedFile?.let { file ->
        val clipboard = LocalClipboardManager.current
        AlertDialog(onDismissRequest = { selectedFile = null }, title = { Text(file.name, maxLines = 3) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(formatBytes(file.size) + " · " + if (file.uploaded) "В сети" else "На устройстве")
                    Text("ID файла", color = Secondary, fontSize = 12.sp)
                    SelectionContainer { Text(file.id, fontSize = 12.sp) }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(file.id)) }) { Text("Копировать ID") }
                    Text(if (file.uploaded) "Сохранение подтверждено другим узлом." else "Файл ещё не сохранён на другом узле.",
                        color = Secondary, fontSize = 13.sp)
                    file.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                }
            }, confirmButton = { TextButton(onClick = { selectedFile = null }) { Text("Готово") } },
            containerColor = Color.White, tonalElevation = 0.dp)
    }
}

@Composable
private fun FileRow(file: StoredFile, busy: Boolean, available: Boolean, retry: () -> Unit, save: () -> Unit, details: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val extension = file.name.substringAfterLast('.', "").lowercase()
    val tint = when (extension) {
        "pdf" -> Color(0xFFD65F59)
        "jpg", "jpeg", "png", "webp" -> Color(0xFF8E6BC9)
        "zip", "rar", "7z" -> Color(0xFFBA8A3A)
        else -> Blue
    }
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = details).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(48.dp).background(tint.copy(alpha = 0.09f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                LineIcon("file", tint, Modifier.size(25.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(formatBytes(file.size), fontSize = 12.sp, color = Secondary)
                    Text("·", color = Secondary)
                    Text(if (file.uploaded) "В сети" else "На устройстве", color = if (file.uploaded) Green else Secondary, fontSize = 12.sp)
                }
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Действия с ${file.name}" }) {
                    Text("⋯", fontSize = 24.sp, color = Secondary)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!file.uploaded) DropdownMenuItem(text = { Text("Отправить в сеть") }, enabled = !busy && available,
                        onClick = { menu = false; retry() })
                    DropdownMenuItem(text = { Text("Сохранить на устройство") }, enabled = !busy, onClick = { menu = false; save() })
                    DropdownMenuItem(text = { Text("Сведения") }, onClick = { menu = false; details() })
                }
            }
        }
        Divider(Modifier.padding(start = 62.dp, top = 12.dp), color = DividerColor)
    }
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(16.dp), tonalElevation = 0.dp, shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun NetworkDetails(state: NetworkState, busy: Boolean, onRetry: () -> Unit, onReconnect: (String) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable(state.bootstrapAddress) { mutableStateOf(state.bootstrapAddress) }
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Group {
            InfoRow("Статус", statusText(state.phase))
            Divider(color = DividerColor)
            InfoRow("Пиры", state.activePeers.toString())
            InfoRow("Получено", formatBytes(state.bytesReceived))
            InfoRow("Отправлено", formatBytes(state.bytesSent))
            InfoRow("Время работы", formatUptime(state.uptimeSeconds))
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            if (state.phase == ConnectionPhase.ERROR || state.phase == ConnectionPhase.STOPPED)
                TextButton(onClick = {
                    if (state.bootstrapAddress.isBlank()) editing = true else onRetry()
                }, enabled = !busy) { Text("Подключиться") }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Подключение", color = Secondary, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp))
            Group {
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { editing = true },
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Bootstrap", fontSize = 15.sp)
                        Text(state.bootstrapAddress, fontSize = 13.sp, color = Secondary)
                    }
                    Text("›", fontSize = 26.sp, color = Secondary)
                }
                Divider(color = DividerColor)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("ID узла", fontSize = 15.sp)
                        Text(state.peerId.ifBlank { "—" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = Secondary)
                    }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(state.peerId)) }, enabled = state.peerId.isNotBlank()) { Text("Копировать", fontSize = 12.sp) }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Соединения", color = Secondary, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp))
            Group {
                if (state.sessions.isEmpty()) Text("Нет активных соединений", color = Secondary, fontSize = 14.sp)
                state.sessions.forEachIndexed { index, session ->
                    if (index > 0) Divider(color = DividerColor)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        LineIcon("network", Blue, Modifier.size(22.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(session.peerId.ifBlank { "Подключение…" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                            Text("↓ ${formatBytes(session.bytesReceived)}  ↑ ${formatBytes(session.bytesSent)}", color = Secondary, fontSize = 12.sp)
                        }
                        Text(session.protocol.uppercase(), color = Secondary, fontSize = 11.sp)
                    }
                }
            }
        }
    }
    if (editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text("Адрес узла") },
        text = {
            OutlinedTextField(value = address, onValueChange = { address = it }, singleLine = true,
                label = { Text("Хост:порт") }, shape = RoundedCornerShape(12.dp),
                isError = address.isNotEmpty() && !NetworkViewModel.isValidBootstrap(address.trim()))
        },
        confirmButton = {
            TextButton(onClick = { onReconnect(address); editing = false },
                enabled = !busy && NetworkViewModel.isValidBootstrap(address.trim())) { Text("Подключиться") }
        }, dismissButton = { TextButton(onClick = { editing = false }) { Text("Отмена") } },
        containerColor = Color.White, tonalElevation = 0.dp)
}

@Composable
private fun LineIcon(kind: String, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.065f)
        when (kind) {
            "plus" -> {
                drawLine(color, Offset(w * .5f, h * .12f), Offset(w * .5f, h * .88f), strokeWidth = stroke.width)
                drawLine(color, Offset(w * .12f, h * .5f), Offset(w * .88f, h * .5f), strokeWidth = stroke.width)
            }
            "search" -> {
                drawCircle(color, w * .3f, Offset(w * .4f, h * .4f), style = stroke)
                drawLine(color, Offset(w * .63f, h * .63f), Offset(w * .92f, h * .92f), strokeWidth = stroke.width)
            }
            "network" -> {
                val points = listOf(Offset(w * .5f, h * .16f), Offset(w * .16f, h * .79f), Offset(w * .84f, h * .79f))
                drawLine(color, points[0], points[1], stroke.width)
                drawLine(color, points[0], points[2], stroke.width)
                drawLine(color, points[1], points[2], stroke.width)
                points.forEach { drawCircle(Color.White, w * .13f, it); drawCircle(color, w * .13f, it, style = stroke) }
            }
            "folder" -> {
                val path = Path().apply {
                    moveTo(w * .08f, h * .25f); lineTo(w * .4f, h * .25f); lineTo(w * .49f, h * .36f)
                    lineTo(w * .92f, h * .36f); lineTo(w * .92f, h * .85f); lineTo(w * .08f, h * .85f); close()
                }
                drawPath(path, color, style = stroke)
            }
            else -> {
                val path = Path().apply {
                    moveTo(w * .2f, h * .06f); lineTo(w * .62f, h * .06f); lineTo(w * .83f, h * .28f)
                    lineTo(w * .83f, h * .94f); lineTo(w * .2f, h * .94f); close()
                    moveTo(w * .62f, h * .06f); lineTo(w * .62f, h * .28f); lineTo(w * .83f, h * .28f)
                    moveTo(w * .34f, h * .52f); lineTo(w * .68f, h * .52f)
                    moveTo(w * .34f, h * .68f); lineTo(w * .62f, h * .68f)
                }
                drawPath(path, color, style = stroke)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 15.sp)
        Text(value, fontSize = 15.sp, color = Secondary)
    }
}
private fun statusText(phase: ConnectionPhase) = when (phase) {
    ConnectionPhase.STARTING -> "Запуск"
    ConnectionPhase.SEARCHING -> "Поиск пиров"
    ConnectionPhase.CONNECTED -> "В сети"
    ConnectionPhase.DEGRADED -> "Нестабильно"
    ConnectionPhase.STOPPED -> "Остановлен"
    ConnectionPhase.ERROR -> "Нет подключения"
}
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(java.util.Locale.forLanguageTag("ru"), "%.1f МБ", bytes / 1_048_576.0)
    bytes >= 1_024 -> String.format(java.util.Locale.forLanguageTag("ru"), "%.1f КБ", bytes / 1_024.0)
    else -> "$bytes Б"
}
private fun formatUptime(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600} ч"
    seconds >= 60 -> "${seconds / 60} мин"
    else -> "$seconds с"
}

@androidx.compose.ui.tooling.preview.Preview(name = "Files", showBackground = true, widthDp = 393, heightDp = 851)
@Composable
private fun FilesPreview() { DemoPreview(0) }

@androidx.compose.ui.tooling.preview.Preview(name = "Network", showBackground = true, widthDp = 393, heightDp = 851)
@Composable
private fun NetworkPreview() { DemoPreview(1) }

@Composable
private fun DemoPreview(tab: Int) {
    Lp2lnandroidTheme {
        NetworkScreen(
            state = NetworkState(phase = ConnectionPhase.CONNECTED, activePeers = 3,
                activeConnections = 3, uptimeSeconds = 420, bytesSent = 12483221, bytesReceived = 48920004,
                peerId = "03a2b4c6d8e0f123456789abcdef0123456789",
                files = listOf(
                    StoredFile("a".repeat(64), "Архитектура LP2LN.pdf", 2457600, true, 1),
                    StoredFile("b".repeat(64), "Заметки к защите.txt", 3280, false, 0),
                    StoredFile("c".repeat(64), "Схема сети.png", 184320, true, 1))),
            fileBusy = false, notice = null, dismissNotice = {}, onAdd = {}, onRetryFile = {},
            onSave = {}, onRetry = {}, onReconnect = {}, initialTab = tab)
    }
}
