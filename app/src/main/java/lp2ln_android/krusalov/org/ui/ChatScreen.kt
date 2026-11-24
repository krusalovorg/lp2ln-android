package lp2ln_android.krusalov.org.ui

import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import lp2ln_android.krusalov.org.network.*

private val ChatBlue = Color(0xFF007AFF)
private val ChatGray = Color(0xFF8E8E93)
private val ChatLine = Color(0xFFE5E5EA)

@Composable
fun ChatScreen(
    state: NetworkState,
    onSend: (String, String, String, (Boolean) -> Unit) -> Unit,
    onRead: (String) -> Unit,
    onAttach: (String, android.net.Uri, String) -> Unit,
    onFile: (StoredFile) -> Unit,
    busy: Boolean,
    onProbe: (String) -> Unit,
    onConversationChanged: (Boolean) -> Unit,
    onNetwork: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var peer by rememberSaveable { mutableStateOf<String?>(null) }
    var newChat by rememberSaveable { mutableStateOf(false) }
    var myAddress by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    val profile = java.io.File(androidx.compose.ui.platform.LocalContext.current.filesDir, "lp2ln/profile-name")
    var profileOpen by rememberSaveable { mutableStateOf(false) }
    val names = remember(state.messages) {
        state.messages.filter { !it.outgoing }.sortedBy { it.createdAt }.associate { it.peerId to it.senderName }
    }
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("contacts", 0)
    val aliases = remember { mutableStateMapOf<String, String>().apply {
        prefs.all.forEach { (peer, alias) -> if (alias is String) put(peer, alias) }
    } }
    BackHandler(peer != null) { peer = null }
    val connected = state.activePeers > 0 && state.phase in listOf(ConnectionPhase.CONNECTED, ConnectionPhase.DEGRADED)
    val currentPeer = peer
    LaunchedEffect(currentPeer) { onConversationChanged(currentPeer != null) }
    DisposableEffect(Unit) { onDispose { onConversationChanged(false) } }
    val recentPeers = remember(state.messages) { chatConversations(state.messages).take(8).map { it.peerId } }
    LaunchedEffect(currentPeer, connected, recentPeers) {
        if (connected) while (true) {
            if (currentPeer != null) onProbe(currentPeer) else recentPeers.forEach(onProbe)
            delay(if (currentPeer != null) 15_000L else 30_000L)
        }
    }
    Column(modifier.fillMaxSize().background(Color.White)) {
        if (currentPeer == null) {
            Column(Modifier.padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Мой профиль", modifier = Modifier.clickable { profileOpen = true }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ChatGray)
                    Text(if (connected) "В сети" else "Нет соединения", fontSize = 12.sp, color = ChatGray)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Сообщения", fontSize = 33.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp,
                        modifier = Modifier.weight(1f))
                    lp2ln_android.krusalov.org.BackgroundButton()
                    IconButton(onClick = { myAddress = true }, enabled = state.peerId.isNotBlank(),
                        modifier = Modifier.semantics { contentDescription = "Мой адрес для чата" }) {
                        ChatIcon("qr", ChatBlue, Modifier.size(23.dp))
                    }
                    IconButton(onClick = { newChat = true }, modifier = Modifier.semantics { contentDescription = "Новый диалог" }) {
                        ChatIcon("compose", ChatBlue, Modifier.size(24.dp))
                    }
                }
                OutlinedTextField(search, { search = it }, singleLine = true,
                    placeholder = { Text("Поиск", color = ChatGray, fontSize = 16.sp) },
                    leadingIcon = { ChatIcon("search", ChatGray, Modifier.size(18.dp)) },
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent, unfocusedContainerColor = Color(0xFFF0F0F3),
                        focusedContainerColor = Color(0xFFF0F0F3)))
            }
            if (!connected) ConnectionHint(onNetwork)
            state.chatError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 13.sp) }
            val conversations = run {
                chatConversations(state.messages).filter {
                    (aliases[it.peerId] ?: names[it.peerId]).orEmpty().contains(search, true) || it.peerId.contains(search, true) || it.lastMessage.text.contains(search, true)
                }
            }
            if (conversations.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(84.dp).background(ChatBlue.copy(alpha = .07f), RoundedCornerShape(26.dp)),
                        contentAlignment = Alignment.Center) { ChatIcon("bubble", ChatBlue, Modifier.size(42.dp)) }
                    Spacer(Modifier.height(22.dp))
                    Text(if (search.isBlank()) "Ваши сообщения" else "Ничего не найдено", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Text(if (search.isBlank()) "Начните разговор с другим узлом.\nДостаточно его адреса или QR."
                        else "Попробуйте другой адрес или текст.", color = ChatGray, fontSize = 15.sp,
                        lineHeight = 22.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    if (search.isBlank()) TextButton(onClick = { newChat = true }, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Новый диалог", color = ChatBlue, fontSize = 16.sp)
                    }
                }
            } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 90.dp)) {
                items(conversations, key = { it.peerId }) { conversation ->
                    ConversationRow(conversation, aliases[conversation.peerId] ?: names[conversation.peerId] ?: peerLabel(conversation.peerId), connected && state.chatPresence[conversation.peerId]?.status == "online") { peer = conversation.peerId }
                }
            }
        } else {
            val messages = remember(state.messages, currentPeer) { state.messages.filter { it.peerId == currentPeer } }
            val unread = messages.count { !it.outgoing && !it.read }
            val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(currentPeer, unread, lifecycle) {
                lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                    if (unread > 0) onRead(currentPeer)
                }
            }
            ChatThread(currentPeer, names[currentPeer], aliases[currentPeer].orEmpty(), { alias ->
                if (alias.isBlank()) aliases.remove(currentPeer) else aliases[currentPeer] = alias
                prefs.edit().putString(currentPeer, alias.takeIf { it.isNotBlank() }).apply()
            }, state.files, onFile, onAttach, busy, messages, connected, state.chatPresence[currentPeer], onBack = { peer = null }, onSend = onSend, onNetwork = onNetwork)
        }
    }
    if (newChat) NewChatDialog(state.peerId, dismiss = { newChat = false }) { recipient ->
        peer = recipient
        newChat = false
    }
    if (profileOpen) {
        var name by rememberSaveable { mutableStateOf(runCatching { profile.readText() }.getOrDefault("")) }
        var failed by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { profileOpen = false }, title = { Text("Мой профиль") },
            text = { OutlinedTextField(name, { name = it.take(60) }, singleLine = true, isError = failed,
                label = { Text("Имя для собеседников") }) },
            confirmButton = { TextButton(onClick = {
                runCatching { profile.parentFile?.mkdirs(); profile.writeText(name.trim()) }
                    .onSuccess { profileOpen = false }.onFailure { failed = true }
            }) { Text("Сохранить") } })
    }
    if (myAddress) ReceiveFileDialog(state.peerId, title = "Мой адрес",
        description = "Покажите QR собеседнику. Чтобы получать сообщения при закрытом чате, включите фоновую связь.") { myAddress = false }
}

@Composable
private fun ConnectionHint(onNetwork: () -> Unit) {
    TextButton(onClick = onNetwork, modifier = Modifier.fillMaxWidth().background(Color(0xFFF5F5F9))) {
        Text("Нет соединения · Открыть сеть", color = ChatGray, fontSize = 13.sp)
    }
}

@Composable
private fun PeerAvatar(peer: String, modifier: Modifier = Modifier) {
    val colors = listOf(Color(0xFFE2EDFF), Color(0xFFEDE7F8), Color(0xFFE2F1EC), Color(0xFFFCE8E5))
    val tint = colors[(peer.drop(2).take(2).toIntOrNull(16) ?: 0) % colors.size]
    Box(modifier.background(tint, CircleShape), contentAlignment = Alignment.Center) {
        Text(peer.drop(2).take(2).uppercase(), color = Color(0xFF5D6E89), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ConversationRow(conversation: ChatConversation, label: String, online: Boolean, open: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = open)) {
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 15.dp, bottom = 15.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                PeerAvatar(conversation.peerId, Modifier.size(50.dp))
                if (online) Box(Modifier.align(Alignment.BottomEnd).size(12.dp).background(Color.White, CircleShape).padding(2.dp)
                    .background(Color(0xFF34C759), CircleShape))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(timeLabel(conversation.lastMessage.createdAt), fontSize = 12.sp, color = ChatGray)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val message = conversation.lastMessage
                    Text((if (message.outgoing) "Вы: " else "") + (attachmentLabel(message.text) ?: message.text).replace('\n', ' '),
                        fontSize = 14.sp, color = ChatGray, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    if (conversation.unreadCount > 0) Box(Modifier.padding(start = 8.dp).size(21.dp)
                        .background(ChatBlue, CircleShape), contentAlignment = Alignment.Center) {
                        Text(conversation.unreadCount.coerceAtMost(99).toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    else ChatIcon("chevron", Color(0xFFC7C7CC), Modifier.padding(start = 8.dp).size(14.dp))
                }
            }
        }
        Divider(Modifier.padding(start = 83.dp), color = ChatLine, thickness = .5.dp)
    }
}

@Composable
private fun ColumnScope.ChatThread(peer: String, senderName: String?, alias: String, rename: (String) -> Unit, files: List<StoredFile>, onFile: (StoredFile) -> Unit, onAttach: (String, android.net.Uri, String) -> Unit, busy: Boolean, messages: List<ChatMessage>, connected: Boolean, presence: ChatPresence?,
    onBack: () -> Unit, onSend: (String, String, String, (Boolean) -> Unit) -> Unit, onNetwork: () -> Unit) {
    var draft by rememberSaveable(peer) { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var info by rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(peer, messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) {
            val nearBottom = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= messages.lastIndex - 2 } ?: true
            if (nearBottom || list.layoutInfo.totalItemsCount == 0) list.animateScrollToItem(messages.lastIndex)
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Назад к диалогам" }) {
            ChatIcon("back", ChatBlue, Modifier.size(24.dp))
        }
        Row(Modifier.weight(1f).clickable { info = true }, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PeerAvatar(peer, Modifier.size(34.dp))
            Column {
                Text(alias.ifBlank { senderName ?: peerLabel(peer) }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                val online = connected && presence?.status == "online"
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(5.dp).background(if (online) Color(0xFF34C759) else ChatGray, CircleShape))
                    Text(when {
                        !connected -> "Нет соединения"
                        online -> "В сети"
                        presence?.status == "offline" -> "Не отвечает"
                        else -> "Проверяем доступность…"
                    }, fontSize = 11.sp, color = if (online) Color(0xFF248A3D) else ChatGray)
                }
            }
        }
        IconButton(onClick = { info = true }, modifier = Modifier.semantics { contentDescription = "Адрес собеседника" }) {
            ChatIcon("info", ChatBlue, Modifier.size(22.dp))
        }
    }
    Divider(color = ChatLine, thickness = .5.dp)
    if (!connected) ConnectionHint(onNetwork)
    if (messages.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth().padding(30.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        PeerAvatar(peer, Modifier.size(64.dp))
        Text("Начните разговор", fontWeight = FontWeight.SemiBold, fontSize = 19.sp, modifier = Modifier.padding(top = 16.dp))
        Text("Для доставки оба узла должны быть в сети.", color = ChatGray, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp)) {
        itemsIndexed(messages, key = { _, message -> message.id + message.outgoing }) { index, message ->
            if (index == 0 || dayLabel(messages[index - 1].createdAt) != dayLabel(message.createdAt)) {
                Text(dayLabel(message.createdAt), color = ChatGray, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            val grouped = index > 0 && messages[index - 1].outgoing == message.outgoing
                && message.createdAt - messages[index - 1].createdAt < 120_000
            Column(Modifier.fillMaxWidth().padding(top = if (grouped) 3.dp else 10.dp),
                horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
                val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp,
                    bottomEnd = if (message.outgoing) 6.dp else 20.dp, bottomStart = if (message.outgoing) 20.dp else 6.dp)
                Surface(color = if (message.outgoing) ChatBlue else Color(0xFFE9E9EB), shape = shape,
                    modifier = Modifier.widthIn(max = 295.dp)) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                    val label = attachmentLabel(message.text)
                    if (label != null) {
                        val file = files.find { it.id == message.text.substringAfterLast('/') &&
                            (message.outgoing || it.senderPeerId == peer) }
                        if (file != null && message.text.startsWith("lp2ln-media/1/circle/") && file.availableLocally) {
                            CirclePlayer(file)
                        } else TextButton(enabled = file != null && !busy, onClick = { file?.let(onFile) }) {
                            Text(if (file == null) "$label · ожидаем файл" else "$label · ${file.name}",
                                color = if (message.outgoing) Color.White else ChatBlue)
                        }
                    } else SelectionContainer {
                        Text(message.text, color = if (message.outgoing) Color.White else Color(0xFF1C1C1E),
                            fontSize = 16.sp, lineHeight = 20.sp)
                    }
                    val stamp = timeLabel(message.createdAt) + if (!message.outgoing) "" else when (message.status) {
                        "delivered" -> "  ✓✓"
                        "failed" -> "  !"
                        else -> "  ◷"
                    }
                    Text(stamp, Modifier.align(Alignment.End), fontSize = 10.sp, lineHeight = 12.sp,
                        color = if (message.outgoing) Color.White.copy(alpha = .85f) else ChatGray)
                    }
                }
                if (message.outgoing && message.status == "failed") {
                    Text(when (message.status) {
                        "delivered" -> "Доставлено"
                        "failed" -> "Не доставлено · Повторить"
                        else -> "Отправляется…"
                    }, color = if (message.status == "failed") MaterialTheme.colorScheme.error else ChatGray,
                        fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp, start = 8.dp, end = 8.dp, bottom = 6.dp)
                            .clickable(enabled = message.status == "failed" && connected) {
                                onSend(peer, message.text, message.id) {}
                            })
                }
            }
        }
    }
    Divider(color = ChatLine, thickness = .5.dp)
    val canSend = connected && draft.isNotBlank() && !submitting
    val send: () -> Unit = {
        if (canSend) {
            submitting = true
            val sentDraft = draft
            onSend(peer, sentDraft, "") { queued ->
                submitting = false
                if (queued && draft == sentDraft) draft = ""
            }
        }
    }
    AttachmentComposer(peer, connected && !busy, onAttach, draft.isNotBlank(), canSend, send) {
        androidx.compose.foundation.text.BasicTextField(draft, { if (it.length <= 1000) draft = it },
            modifier = Modifier.weight(1f).padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            textStyle = LocalTextStyle.current.copy(fontSize = 16.sp), maxLines = 4,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            decorationBox = { input -> Box {
                if (draft.isEmpty()) Text("Сообщение", color = ChatGray, fontSize = 16.sp)
                input()
            } })
    }
    if (info) {
        var edited by rememberSaveable(peer) { mutableStateOf(alias) }
        val clipboard = LocalClipboardManager.current
        AlertDialog(onDismissRequest = { info = false }, title = { Text("Собеседник") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(edited, { edited = it.take(60) }, singleLine = true, label = { Text("Имя на этом устройстве") })
                SelectionContainer { Text(peer, fontSize = 13.sp) }
                Text("Сообщения зашифрованы между узлами. История хранится на этом устройстве.", color = ChatGray, fontSize = 14.sp)
                TextButton(onClick = { clipboard.setText(AnnotatedString(PeerInvitation.encode(peer))) }) { Text("Копировать адрес") }
            } }, confirmButton = { TextButton(onClick = { rename(edited.trim()); info = false }) { Text("Готово") } }, containerColor = Color.White)
    }
}

@Composable
private fun NewChatDialog(ownPeer: String, dismiss: () -> Unit, open: (String) -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let { address = it } }
    val peer = PeerInvitation.parse(address)
    val valid = peer != null && peer != ownPeer
    AlertDialog(onDismissRequest = dismiss, title = { Text("Новый диалог", fontWeight = FontWeight.SemiBold) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Вставьте адрес собеседника или отсканируйте его QR.", fontSize = 15.sp, color = ChatGray)
            OutlinedTextField(address, { address = it }, label = { Text("Адрес узла") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), isError = address.isNotBlank() && !valid)
            if (peer == ownPeer) Text("Это ваш собственный адрес", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            OutlinedButton(onClick = { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("QR собеседника из LP2LN").setBeepEnabled(false).setOrientationLocked(false)) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("Сканировать QR") }
        } }, confirmButton = { TextButton(onClick = { peer?.let(open) }, enabled = valid) { Text("Продолжить") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } }, containerColor = Color.White, shape = RoundedCornerShape(26.dp))
}

private fun timeLabel(timestamp: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
private fun dayLabel(timestamp: Long): String = SimpleDateFormat("d MMMM", Locale("ru")).format(Date(timestamp))

@Composable
private fun ChatIcon(kind: String, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(w * .07f, cap = StrokeCap.Round)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(w*x, h*y), Offset(w*x2, h*y2), stroke.width, StrokeCap.Round)
        when (kind) {
            "back" -> { line(.64f,.15f,.28f,.5f); line(.28f,.5f,.64f,.85f) }
            "chevron" -> { line(.35f,.2f,.65f,.5f); line(.65f,.5f,.35f,.8f) }
            "send" -> { line(.5f,.82f,.5f,.18f); line(.2f,.48f,.5f,.18f); line(.5f,.18f,.8f,.48f) }
            "search" -> { drawCircle(color,w*.29f,Offset(w*.42f,h*.42f),style=stroke); line(.64f,.64f,.9f,.9f) }
            "info" -> { drawCircle(color,w*.4f,center,style=stroke); drawCircle(color,w*.04f,Offset(w*.5f,h*.3f)); line(.5f,.46f,.5f,.72f) }
            "qr" -> { for ((x,y) in listOf(.1f to .1f,.61f to .1f,.1f to .61f)) {
                drawRect(color, Offset(w*x,h*y), Size(w*.29f,h*.29f), style=stroke)
            }; line(.64f,.65f,.9f,.65f); line(.64f,.65f,.64f,.9f); line(.82f,.82f,.9f,.9f) }
            "compose" -> { line(.15f,.2f,.55f,.2f); line(.15f,.2f,.15f,.87f); line(.15f,.87f,.8f,.87f); line(.8f,.87f,.8f,.48f)
                val path=Path().apply { moveTo(w*.4f,h*.6f); lineTo(w*.46f,h*.4f); lineTo(w*.82f,h*.06f); lineTo(w*.96f,h*.2f); lineTo(w*.6f,h*.55f); close() }
                drawPath(path,color,style=stroke) }
            else -> { drawRoundRect(color,Offset(w*.1f,h*.13f),Size(w*.8f,h*.62f),CornerRadius(w*.18f),style=stroke)
                line(.28f,.75f,.2f,.91f); line(.2f,.91f,.46f,.75f) }
        }
    }
}
