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
    private var pollingJob: Job? = null

    fun connect(filesDirectory: File) {
        if (pollingJob?.isActive == true) return
        dataDirectory = File(filesDirectory, "lp2ln").also { it.mkdirs() }
        val bootstrap = _state.value.bootstrapAddress
        _state.value = NetworkState(
            phase = ConnectionPhase.STARTING,
            bootstrapAddress = bootstrap,
        )
        pollingJob = viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) {
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

    fun retry() {
        pollingJob?.cancel()
        pollingJob = null
        dataDirectory?.parentFile?.let(::connect)
    }

    override fun onCleared() {
        pollingJob?.cancel()
        repository.stop()
        super.onCleared()
    }

    private companion object {
        const val SNAPSHOT_INTERVAL_MS = 1_000L
    }
}
