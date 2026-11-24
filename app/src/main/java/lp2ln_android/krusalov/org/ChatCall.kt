package lp2ln_android.krusalov.org

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import lp2ln_android.krusalov.org.network.*

@Composable
internal fun ChatCallButton(peer:String) {
    val ctx=LocalContext.current
    fun dial() {
        ContextCompat.startForegroundService(ctx,Intent(ctx,VoiceService::class.java).setAction("call").putExtra("peer",peer))
    }
    val mic=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) dial() else VoiceService.error="Чтобы позвонить, разрешите доступ к микрофону."
    }
    IconButton(enabled=!VoiceService.busy,onClick={
        if(ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) dial()
        else mic.launch(Manifest.permission.RECORD_AUDIO)
    }) { Icon(painterResource(R.drawable.ic_voice_phone), "Позвонить") }
}

@Composable
internal fun CallOverlay(state:NetworkState) {
    val ctx=LocalContext.current
    val prefs=ctx.getSharedPreferences("call-ui",Context.MODE_PRIVATE)
    val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if(Build.VERSION.SDK_INT>=33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("askedNotification",false)){
            prefs.edit().putBoolean("askedNotification",true).apply()
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    var requested by rememberSaveable {mutableStateOf(0L)}
    val mic=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if(ok) VoiceService.instance?.accept(requested)
        else {if(VoiceService.instance?.ringingHandle()==requested)VoiceService.instance?.finishCall()
            VoiceService.error="Чтобы ответить, разрешите доступ к микрофону."}
    }
    var minimized by remember(VoiceService.busy,VoiceService.incoming){mutableStateOf(false)}
    if(VoiceService.busy && minimized) Box(Modifier.fillMaxWidth().statusBarsPadding(),contentAlignment=Alignment.TopCenter) {
        Button(onClick={minimized=false}){Text("Вернуться к звонку")}
    }
    if(VoiceService.busy && !minimized) {
        val peer=VoiceService.callPeer
        val label=ctx.getSharedPreferences("contacts",Context.MODE_PRIVATE).getString(peer,null)
            ?: state.messages.lastOrNull {it.peerId==peer && !it.outgoing}?.senderName ?: peerLabel(peer)
        Dialog(onDismissRequest = { minimized = true },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Surface(Modifier.fillMaxSize(), color = Color(0xFF19283B), contentColor = Color.White) {
                Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    TextButton(onClick = { minimized = true }, modifier = Modifier.align(Alignment.Start)) {
                        Text("Свернуть", color = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(92.dp).background(Color(0xFF396086), CircleShape),
                        contentAlignment = Alignment.Center) {
                        Text(label.take(1).uppercase(), style = MaterialTheme.typography.displayMedium)
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(label, style = MaterialTheme.typography.headlineMedium)
                    Text(if (VoiceService.incoming) "Входящий звонок" else VoiceService.summary)
                    Spacer(Modifier.weight(1f))
                    if (VoiceService.summary == "Разговор") Row {
                        TextButton(onClick = { VoiceService.instance?.toggleMute() }) {
                            Text(if (VoiceService.muted) "Вкл. микрофон" else "Выкл. микрофон", color = Color.White)
                        }
                        TextButton(onClick = { VoiceService.instance?.toggleSpeaker() }) {
                            Text(if (VoiceService.loud) "К уху" else "Динамик", color = Color.White)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                        CallAction(if (VoiceService.incoming) "Отклонить" else "Завершить", false) {
                            VoiceService.instance?.finishCall()
                        }
                        if (VoiceService.incoming) CallAction("Принять", true) {
                            requested = VoiceService.instance?.ringingHandle() ?: 0L
                            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                                VoiceService.instance?.accept(requested)
                            else mic.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
    VoiceService.error?.let { reason->AlertDialog(onDismissRequest={VoiceService.error=null},
        title={Text("Голосовой звонок")},text={Text(reason)},confirmButton={TextButton(onClick={VoiceService.error=null}){Text("Закрыть")}}) }
}

@Composable
private fun CallAction(label: String, accept: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick = onClick, modifier = Modifier.size(60.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (accept) Color(0xFF18875B) else Color(0xFFBA3743))) {
            Icon(painterResource(R.drawable.ic_voice_phone), label,
                modifier = Modifier.rotate(if (accept) 0f else 135f), tint = Color.White)
        }
        Spacer(Modifier.height(6.dp))
        Text(label)
    }
}
