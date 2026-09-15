package lp2ln_android.krusalov.org.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NetworkViewModel(
    private val repository: Lp2lnRepository = Lp2lnRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow(NetworkState())
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private var dataDirectory: File? = null
    private var bootstrapFile: File? = null
    private var pollingJob: Job? = null

    fun connect(filesDirectory: File) {
        if (pollingJob?.isActive == true) return
        dataDirectory = File(filesDirectory, "lp2ln").also { it.mkdirs() }
        val stored = File(filesDirectory, BOOTSTRAP_FILE)
            .also { bootstrapFile = it }
            .takeIf { it.exists() }
            ?.runCatching { readText().trim() }
            ?.getOrNull()
            ?.takeIf { it.isNotEmpty() }
        launchNode(stored ?: _state.value.bootstrapAddress, restart = false)
    }

    /** Switches the node to a user-supplied bootstrap node and remembers it. */
    fun reconnect(address: String) {
        val bootstrap = address.trim()
        if (dataDirectory == null) return
        if (!isValidBootstrap(bootstrap)) {
            _state.value = _state.value.copy(
                phase = ConnectionPhase.ERROR,
                error = "Неверный адрес. Формат: хост:порт, например 83.136.233.187:18080",
            )
            return
        }
        bootstrapFile?.runCatching { writeText(bootstrap) }
        launchNode(bootstrap, restart = true)
    }

    fun retry() = launchNode(_state.value.bootstrapAddress, restart = true)

    private fun launchNode(bootstrap: String, restart: Boolean) {
        pollingJob?.cancel()
        _state.value = NetworkState(phase = ConnectionPhase.STARTING, bootstrapAddress = bootstrap)
        pollingJob = viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) {
                if (restart) repository.stop()
                repository.start(requireNotNull(dataDirectory), bootstrap)
            }
            if (_state.value.phase == ConnectionPhase.ERROR) return@launch

            while (isActive) {
                delay(SNAPSHOT_INTERVAL_MS)
                _state.value = withContext(Dispatchers.IO) {
                    repository.snapshot(bootstrap)
                }
            }
        }
    }

    override fun onCleared() {
        pollingJob?.cancel()
        repository.stop()
        super.onCleared()
    }

    internal companion object {
        private const val SNAPSHOT_INTERVAL_MS = 1_000L
        private const val BOOTSTRAP_FILE = "bootstrap.txt"

        /** host:port — host is an IPv4 literal or a DNS name, port is 1..65535. */
        fun isValidBootstrap(address: String): Boolean {
            val host = address.substringBeforeLast(':', "")
            val port = address.substringAfterLast(':', "").toIntOrNull() ?: return false
            if (port !in 1..65535) return false
            return host.isNotEmpty() && host.matches(HOST_REGEX)
        }

        private val HOST_REGEX = Regex("[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?")
    }
}
