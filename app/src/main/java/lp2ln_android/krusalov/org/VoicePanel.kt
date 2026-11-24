package lp2ln_android.krusalov.org

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import lp2ln_android.krusalov.org.network.*
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

@Composable
internal fun VoicePanel(state:NetworkState) {
    val context=LocalContext.current
    var expanded by rememberSaveable {mutableStateOf(false)}
    var peer by rememberSaveable {mutableStateOf("")}
    var endpoint by rememberSaveable {mutableStateOf("")}
    var local by remember {mutableStateOf("")}
    var probing by remember {mutableStateOf(false)}
    var pending by rememberSaveable {mutableStateOf("")}
    var message by remember {mutableStateOf("")}
    LaunchedEffect(expanded,state.peerId) {
        if(expanded) local=withContext(Dispatchers.IO) {
            runCatching {
                val status=checked(Lp2lnRealtimeBridge.nativeStatus())
                val port=status.getInt("port")
                val addresses=NetworkInterface.getNetworkInterfaces().toList().filter {it.isUp && !it.isLoopback}
                    .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                addresses.joinToString { "${it.hostAddress}:$port" }
            }.getOrDefault("")
        }
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val report=VoiceService.latestReport
        if(uri!=null && report.isNotEmpty()) {
            runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {it.write(report)} }
                .onFailure { message=it.message.orEmpty() }
        }
    }
    fun command(action:String) {
        val intent=Intent(context,VoiceService::class.java).putExtra("peer",peer.trim())
        if(action=="dial")intent.putExtra("endpoint",endpoint.trim())
        ContextCompat.startForegroundService(context,intent)
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) command(pending)
        else message="Для звонка нужен доступ к микрофону"
    }
    fun start(action:String) {
        pending=action
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)command(action)
        else permission.launch(if(Build.VERSION.SDK_INT>=33)arrayOf(Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.RECORD_AUDIO))
    }
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        TextButton(onClick={expanded=!expanded}){Text(if(VoiceService.incoming)"Входящий звонок" else "Голос LP2LN")}
        if(expanded || VoiceService.incoming) {
            SelectionContainer { Text("Мой Peer ID\n${state.peerId}\nQUIC $local") }
            if(!VoiceService.busy) {
                val known=state.knownNodes.distinctBy {it.peerId}.take(16)
                var menu by remember {mutableStateOf(false)}
                if(known.isNotEmpty()) Box {
                    TextButton(onClick={menu=true}){Text("Выбрать известного пира")}
                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                        known.forEach { p -> DropdownMenuItem(text={Text(peerLabel(p.peerId))},onClick={
                            peer=p.peerId
                            endpoint=state.knownNodes.firstOrNull {it.peerId==peer && it.protocol=="quic"}?.address.orEmpty()
                            menu=false
                        }) }
                    }
                }
                OutlinedTextField(peer,{peer=it},label={Text("Peer ID собеседника")},singleLine=true)
                OutlinedTextField(endpoint,{endpoint=it},label={Text("QUIC IP:порт собеседника")},singleLine=true)
                Row {
                    TextButton(onClick={start("listen")},enabled=!probing && peer.isNotBlank()){Text("Разрешить входящие")}
                    TextButton(onClick={start("dial")},enabled=!probing && peer.isNotBlank() && endpoint.isNotBlank()){Text("Позвонить")}
                }
            }
            if(VoiceService.instance==null) FlowProbePanel(peer.trim(),endpoint.trim(),probing){ probing=it }
            Row {
                Checkbox(checked=VoiceService.diagnostics,onCheckedChange={VoiceService.diagnostics=it})
                Text("Диагностика звонка")
            }
            if(!VoiceService.busy && VoiceService.latestReport.isNotEmpty())
                TextButton(onClick={export.launch("lp2ln-call.json")}){Text("Сохранить отчёт звонка")}
            Text(VoiceService.summary)
            if(message.isNotBlank())Text(message)
            if(VoiceService.incoming)Button(onClick={VoiceService.instance?.accept()}){Text("Принять")}
            if(VoiceService.busy) {
                Row {
                    TextButton(onClick={VoiceService.instance?.finishCall()}){Text(if(VoiceService.incoming)"Отклонить" else "Завершить")}
                    TextButton(onClick={VoiceService.instance?.toggleMute()}){Text(if(VoiceService.muted)"Вкл. микрофон" else "Mute")}
                    TextButton(onClick={VoiceService.instance?.toggleSpeaker()}){Text(if(VoiceService.loud)"К уху" else "Динамик")}
                }
                if(VoiceService.diagnostics)SelectionContainer {Text(VoiceService.detail)}
            } else if(VoiceService.instance!=null) {
                TextButton(onClick={context.stopService(Intent(context, VoiceService::class.java))}){Text("Остановить приём")}
            }
        }
    }
}
