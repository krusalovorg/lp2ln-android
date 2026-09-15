package lp2ln_android.krusalov.org

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import lp2ln_android.krusalov.org.network.ConnectionPhase
import lp2ln_android.krusalov.org.network.NetworkSession
import lp2ln_android.krusalov.org.network.NetworkState
import lp2ln_android.krusalov.org.network.NetworkViewModel
import lp2ln_android.krusalov.org.ui.theme.Cyan
import lp2ln_android.krusalov.org.ui.theme.ElectricBlue
import lp2ln_android.krusalov.org.ui.theme.Error
import lp2ln_android.krusalov.org.ui.theme.Lp2lnandroidTheme
import lp2ln_android.krusalov.org.ui.theme.Success
import lp2ln_android.krusalov.org.ui.theme.TextSecondary
import lp2ln_android.krusalov.org.ui.theme.Violet
import lp2ln_android.krusalov.org.ui.theme.Warning

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Lp2lnandroidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
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
    LaunchedEffect(Unit) {
        viewModel.connect(context.filesDir)
    }
    NetworkScreen(state = state, onRetry = viewModel::retry)
}

@Composable
private fun NetworkScreen(state: NetworkState, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        BrandHeader()
        Spacer(Modifier.height(30.dp))
        ConnectionCard(state, onRetry)
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MetricCard("Пиры", state.activePeers.toString(), Modifier.weight(1f))
            MetricCard("Сессии", state.activeConnections.toString(), Modifier.weight(1f))
            MetricCard("Аптайм", formatUptime(state.uptimeSeconds), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        TrafficCard(state)
        Spacer(Modifier.height(12.dp))
        IdentityCard(state.peerId)
        Spacer(Modifier.height(26.dp))
        SectionTitle("Активные соединения", state.sessions.size)
        Spacer(Modifier.height(10.dp))
        if (state.sessions.isEmpty()) {
            EmptySessions(state.phase)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.sessions.forEach(::SessionRow)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Bootstrap  ·  ${state.bootstrapAddress}",
            color = TextSecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Lp2lnMark(Modifier.size(38.dp))
        Spacer(Modifier.size(12.dp))
        Column {
            Text(
                text = "LP2LN",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Text(
                text = "DECENTRALIZED STORAGE",
                color = TextSecondary,
                fontSize = 9.sp,
                letterSpacing = 1.4.sp,
            )
        }
    }
}

@Composable
private fun Lp2lnMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * .105f
        val gradient = Brush.linearGradient(
            colors = listOf(ElectricBlue, Cyan, Violet),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        val top = listOf(
            Offset(size.width * .12f, size.height * .34f),
            Offset(size.width * .50f, size.height * .10f),
            Offset(size.width * .88f, size.height * .34f),
            Offset(size.width * .50f, size.height * .56f),
            Offset(size.width * .12f, size.height * .34f),
        )
        top.zipWithNext().forEach { (start, end) ->
            drawLine(gradient, start, end, stroke, StrokeCap.Round)
        }
        drawLine(
            gradient,
            Offset(size.width * .18f, size.height * .65f),
            Offset(size.width * .50f, size.height * .86f),
            stroke,
            StrokeCap.Round,
        )
        drawLine(
            gradient,
            Offset(size.width * .50f, size.height * .86f),
            Offset(size.width * .82f, size.height * .65f),
            stroke,
            StrokeCap.Round,
        )
        drawCircle(ElectricBlue, radius = stroke * .9f, center = Offset(size.width * .50f, size.height * .56f))
        drawCircle(Violet, radius = stroke * .78f, center = Offset(size.width * .82f, size.height * .65f))
    }
}

@Composable
private fun ConnectionCard(state: NetworkState, onRetry: () -> Unit) {
    val (title, subtitle, color) = when (state.phase) {
        ConnectionPhase.STARTING -> Triple("Запуск узла", "Инициализируем LP2LN", ElectricBlue)
        ConnectionPhase.SEARCHING -> Triple("Узел работает", "Подключаемся к пирам", Warning)
        ConnectionPhase.CONNECTED -> Triple("В сети", "Защищённое P2P-соединение активно", Success)
        ConnectionPhase.DEGRADED -> Triple("Сеть нестабильна", state.error ?: "Проверяем соединения", Warning)
        ConnectionPhase.STOPPED -> Triple("Узел остановлен", "Соединение с сетью закрыто", TextSecondary)
        ConnectionPhase.ERROR -> Triple("Ошибка подключения", state.error ?: "Не удалось запустить узел", Error)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.linearGradient(listOf(color.copy(alpha = .17f), Color.Transparent)),
                shape = RoundedCornerShape(24.dp),
            )
            .border(1.dp, color.copy(alpha = .28f), RoundedCornerShape(24.dp))
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.size(10.dp))
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(7.dp))
        Text(subtitle, color = TextSecondary, fontSize = 14.sp)
        if (state.phase == ConnectionPhase.ERROR) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
            ) {
                Text("Повторить")
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 16.dp),
    ) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(label, color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun TrafficCard(state: NetworkState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
            .padding(18.dp),
    ) {
        Text("Трафик", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            TrafficValue("Получено", state.bytesReceived, Cyan, Modifier.weight(1f))
            TrafficValue("Отправлено", state.bytesSent, Violet, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TrafficValue(label: String, bytes: Long, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(formatBytes(bytes), color = color, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun IdentityCard(peerId: String) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Идентификатор узла", color = TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = peerId.ifBlank { "Создаётся…" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp,
                )
            }
            if (peerId.isNotBlank()) {
                Button(
                    onClick = { clipboard.setText(AnnotatedString(peerId)) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Text("Копировать", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, count: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(count.toString(), color = TextSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun EmptySessions(phase: ConnectionPhase) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
            .padding(20.dp),
    ) {
        Text(
            text = if (phase == ConnectionPhase.SEARCHING) "Ищем доступные узлы…" else "Активных соединений пока нет",
            color = TextSecondary,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun SessionRow(session: NetworkSession) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (session.isActive) Success else TextSecondary, CircleShape),
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = session.peerId,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 14.sp,
            )
            Text(session.protocol.uppercase(Locale.ROOT), color = ElectricBlue, fontSize = 11.sp)
        }
        Spacer(Modifier.height(12.dp))
        Divider(color = MaterialTheme.colorScheme.surfaceVariant)
        Spacer(Modifier.height(10.dp))
        Text(
            text = "↓ ${formatBytes(session.bytesReceived)}   ↑ ${formatBytes(session.bytesSent)}   ·   ${session.lastActivitySeconds} с назад",
            color = TextSecondary,
            fontSize = 11.sp,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> String.format(Locale.ROOT, "%.1f ГБ", bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> String.format(Locale.ROOT, "%.1f МБ", bytes / 1_048_576.0)
    bytes >= 1_024 -> String.format(Locale.ROOT, "%.1f КБ", bytes / 1_024.0)
    else -> "$bytes Б"
}

private fun formatUptime(seconds: Long): String = when {
    seconds >= 3_600 -> "${seconds / 3_600} ч"
    seconds >= 60 -> "${seconds / 60} мин"
    else -> "$seconds с"
}
