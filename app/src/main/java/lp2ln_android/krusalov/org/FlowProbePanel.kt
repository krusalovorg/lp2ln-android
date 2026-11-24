package lp2ln_android.krusalov.org

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import lp2ln_android.krusalov.org.network.*
import org.json.JSONObject
import java.io.File

@Composable
internal fun FlowProbePanel(peer: String, endpoint: String, running: Boolean, onRun: (Boolean)->Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf("") }
    var longRun by remember { mutableStateOf(false) }
    var fast by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null && result.isNotEmpty()) scope.launch(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(result) }
        }
    }
    fun launch(listener:Boolean) {
        onRun(true);status="Соединение…";result=""
        job=scope.launch {
            var handle=0L
            val protocol=DuplexProbe.protocol
            try {
                val raw=withContext(NonCancellable+Dispatchers.IO) {
                    if(listener) { checked(Lp2lnRealtimeBridge.nativeAuthorize(peer,protocol)); null }
                    else checked(Lp2lnRealtimeBridge.nativeOpen(peer,endpoint,protocol))
                }
                val opened=raw ?: withTimeout(120_000) {
                    var accepted: JSONObject?=null
                    while(accepted==null) {
                        ensureActive()
                        val candidate=withContext(NonCancellable+Dispatchers.IO) { JSONObject(Lp2lnRealtimeBridge.nativeAccept(500)) }
                        if(candidate.optBoolean("ok")) {
                            if(candidate.optString("peerId")==peer && candidate.optInt("protocol")==protocol) accepted=candidate
                            else withContext(NonCancellable+Dispatchers.IO){Lp2lnRealtimeBridge.nativeClose(candidate.getLong("handle"))}
                        }
                    }
                    accepted
                }
                handle=opened.getLong("handle")
                ensureActive()
                val report=DuplexProbe.run(handle,listener,if(longRun)1200 else 10,
                    if(fast)500 else 50,minOf(120,opened.getInt("maxPayload"))) { message ->
                    Handler(Looper.getMainLooper()).post { status=message }
                }
                result=report.toString(2)
                withContext(Dispatchers.IO){File(context.filesDir,"flow-result.json").writeText(result)}
                status="RX ${report.getInt("remoteDelivered")} / ${report.getInt("offeredSlots")} · ${report.getLong("goodputBps")} bit/s"
            } catch(e: CancellationException){status="Тест остановлен";throw e}
            catch(e:Exception){status=e.message ?: "Ошибка теста"}
            finally {
                withContext(NonCancellable+Dispatchers.IO){
                    if(handle!=0L)Lp2lnRealtimeBridge.nativeClose(handle)
                    if(listener)Lp2lnRealtimeBridge.nativeRevoke(peer,protocol)
                }
                onRun(false)
            }
        }
    }
    TextButton(onClick={shown=!shown},enabled=!running){Text("Проверка Flow без микрофона")}
    if(shown){
        Row {
            TextButton(onClick={longRun=!longRun},enabled=!running){Text(if(longRun)"20 минут" else "10 секунд")}
            TextButton(onClick={fast=!fast},enabled=!running){Text(if(fast)"500 PPS" else "50 PPS")}
        }
        Row {
            TextButton(onClick={launch(true)},enabled=!running && peer.isNotBlank()){Text("Ждать тест")}
            TextButton(onClick={launch(false)},enabled=!running && peer.isNotBlank() && endpoint.isNotBlank()){Text("Запустить тест")}
        }
        Text(status)
        if(running) TextButton(onClick={job?.cancel()}){Text("Остановить тест")}
        if(result.isNotEmpty())TextButton(onClick={save.launch("flow-result.json")}){Text("Сохранить JSON")}
    }
}
