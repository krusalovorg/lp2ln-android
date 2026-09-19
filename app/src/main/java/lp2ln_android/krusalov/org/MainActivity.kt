package lp2ln_android.krusalov.org

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import lp2ln_android.krusalov.org.network.ConnectionPhase
import lp2ln_android.krusalov.org.network.NetworkSession
import lp2ln_android.krusalov.org.network.NetworkState
import lp2ln_android.krusalov.org.network.NetworkViewModel
import lp2ln_android.krusalov.org.ui.theme.Lp2lnandroidTheme
import lp2ln_android.krusalov.org.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Lp2lnandroidTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    NetworkRoute()
                }
            }
        }
    }
}

@Composable
private fun NetworkRoute(viewModel: NetworkViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.connect(context.filesDir) }
    NetworkScreen(state, viewModel::retry, viewModel::reconnect)
}

@Composable
private fun NetworkScreen(state: NetworkState, onRetry: () -> Unit, onReconnect: (String) -> Unit) {
    var address by remember(state.bootstrapAddress) { mutableStateOf(state.bootstrapAddress) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("LP2LN", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(statusText(state.phase), fontSize = 16.sp)
        state.error?.let { Text(it, color = TextSecondary, fontSize = 13.sp) }
        if (state.phase == ConnectionPhase.ERROR) {
            Button(onClick = onRetry) { Text("Повторить") }
        }

        Divider()
        InfoRow("Пиры", state.activePeers.toString())
        InfoRow("Сессии", state.activeConnections.toString())
        InfoRow("Аптайм", "${state.uptimeSeconds} с")
        InfoRow("Получено", formatBytes(state.bytesReceived))
        InfoRow("Отправлено", formatBytes(state.bytesSent))

        Divider()
        Text("Мой ID", color = TextSecondary, fontSize = 12.sp)
        Text(state.peerId.ifBlank { "..." }, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)

        Divider()
        Text("Соединения (${state.sessions.size})", color = TextSecondary, fontSize = 12.sp)
        if (state.sessions.isEmpty()) {
            Text("нет", fontSize = 13.sp)
        } else {
            state.sessions.forEach { SessionRow(it) }
        }

        Divider()
        Text("Bootstrap-узел", color = TextSecondary, fontSize = 12.sp)
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = { onReconnect(address) },
            enabled = address.isNotBlank() && address.trim() != state.bootstrapAddress,
        ) {
            Text("Подключиться")
        }
    }
}

private fun statusText(phase: ConnectionPhase) = when (phase) {
    ConnectionPhase.STARTING -> "Запуск узла..."
    ConnectionPhase.SEARCHING -> "Поиск пиров..."
    ConnectionPhase.CONNECTED -> "В сети"
    ConnectionPhase.DEGRADED -> "Сеть нестабильна"
    ConnectionPhase.STOPPED -> "Узел остановлен"
    ConnectionPhase.ERROR -> "Ошибка подключения"
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSecondary, fontSize = 14.sp)
        Text(value, fontSize = 14.sp)
    }
}

@Composable
private fun SessionRow(session: NetworkSession) {
    Text(
        text = "${session.peerId.take(12)}... ${session.protocol} " +
            "↓${formatBytes(session.bytesReceived)} ↑${formatBytes(session.bytesSent)}",
        fontSize = 13.sp,
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "${bytes / 1_048_576} МБ"
    bytes >= 1_024 -> "${bytes / 1_024} КБ"
    else -> "$bytes Б"
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun NetworkScreenPreview() {
    Lp2lnandroidTheme {
        NetworkScreen(
            state = NetworkState(
                phase = ConnectionPhase.CONNECTED,
                peerId = "12D3KooWJk8sT4rQx9vB2mNpLcH7dZfYaR3uE6gW1nXqVtSm",
                activePeers = 4,
                activeConnections = 2,
                bytesSent = 12_483_221,
                bytesReceived = 48_920_004,
                uptimeSeconds = 7_412,
                sessions = listOf(
                    NetworkSession("12D3KooWPeerA9vB2mNpLcH7dZfYaR3uE6", "tcp", true, 102_400, 884_000, 3),
                ),
            ),
            onRetry = {},
            onReconnect = {},
        )
    }
}
