package lp2ln_android.krusalov.org

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import lp2ln_android.krusalov.org.network.*

/** Both owners run on the main thread and share the single native node. */
internal object NodeSession : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    private var owners = 0
    fun acquire(): NetworkViewModel {
        owners++
        return ViewModelProvider(this)[NetworkViewModel::class.java]
    }
    fun release() { if (--owners == 0) viewModelStore.clear() }
}

class ChatService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    companion object { var running by mutableStateOf(false); private set }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("node", "Фоновая связь", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("messages", "Сообщения", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        fun notification(channel: String) = Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_lp2ln_status).setContentIntent(open)
        startForeground(1, notification("node").setContentTitle("LP2LN").setContentText("Получение сообщений")
            .setCategory(Notification.CATEGORY_SERVICE).setOnlyAlertOnce(true).setShowWhen(false).setOngoing(true)
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }.build())
        running = true
        val node = NodeSession.acquire()
        node.connect(filesDir)
        scope.launch {
            var previous = emptySet<String>()
            node.state.collect { state ->
                val unread = state.messages.filter { !it.outgoing && !it.read }
                val peers = unread.map { it.peerId }.toSet()
                (previous - peers).forEach { manager.cancel(it, 2) }
                unread.groupBy { it.peerId }.forEach { (peer, messages) ->
                    val last = messages.maxBy { it.createdAt }
                    val prefs = getSharedPreferences("notifications", MODE_PRIVATE)
                    if (prefs.getString(peer, null) != last.id) {
                        val alias = getSharedPreferences("contacts", MODE_PRIVATE).getString(peer, null)
                        manager.notify(peer, 2, notification("messages").setContentTitle(alias ?: peerLabel(peer))
                            .setContentText("Новых сообщений: ${messages.size}").setAutoCancel(true).build())
                        prefs.edit().putString(peer, last.id).apply()
                    }
                }
                previous = peers
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onDestroy() {
        running = false
        scope.cancel()
        NodeSession.release()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

@Composable
internal fun BackgroundButton() {
    val context = LocalContext.current
    val intent = Intent(context, ChatService::class.java)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) ContextCompat.startForegroundService(context, intent)
        else Toast.makeText(context, "Разрешите уведомления для фонового чата", Toast.LENGTH_SHORT).show()
    }
    TextButton(onClick = {
        if (ChatService.running) context.stopService(intent)
        else if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else ContextCompat.startForegroundService(context, intent)
    }) { Text(if (ChatService.running) "Фон вкл." else "Фон выкл.") }
}
