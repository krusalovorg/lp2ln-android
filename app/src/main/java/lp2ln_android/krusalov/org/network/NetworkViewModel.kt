package lp2ln_android.krusalov.org.network

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val nodeMutex = Mutex()
    private val _fileBusy = MutableStateFlow(false)
    val fileBusy = _fileBusy.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    fun clearNotice() { _notice.value = null }

    fun importFile(context: Context, uri: Uri) {
        if (_fileBusy.value || _state.value.transfer.busy) return
        _fileBusy.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val directory = File(requireNotNull(dataDirectory), "imports").also { it.mkdirs() }
                    val staging = File.createTempFile("upload-", ".bin", directory)
                    var handedOff = false
                    try {
                        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "File"
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            staging.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var total = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    check(total <= 100L * 1024 * 1024) { "Максимальный размер файла — 100 МБ" }
                                    output.write(buffer, 0, count)
                                }
                            }
                        } ?: error("Не удалось прочитать файл")
                        nodeMutex.withLock {
                            repository.upload(staging.absolutePath, name)
                            handedOff = true
                            _state.value = repository.snapshot(_state.value.bootstrapAddress)
                        }
                    } finally { if (!handedOff) staging.delete() }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _notice.value = error.message ?: "Не удалось добавить файл"
            } finally { _fileBusy.value = false }
        }
    }

    fun retryFile(file: StoredFile) {
        if (_fileBusy.value || _state.value.transfer.busy) return
        _fileBusy.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    nodeMutex.withLock {
                        repository.upload("", file.name, file.id)
                        _state.value = repository.snapshot(_state.value.bootstrapAddress)
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _notice.value = error.message
            } finally { _fileBusy.value = false }
        }
    }

    fun exportFile(context: Context, file: StoredFile, uri: Uri) {
        if (_fileBusy.value) return
        _fileBusy.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val temp = File.createTempFile("restore-", ".bin", context.cacheDir)
                    try {
                        repository.export(file.id, temp.absolutePath)
                        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                            temp.inputStream().use { it.copyTo(output) }
                        } ?: error("Не удалось сохранить файл")
                    } finally { temp.delete() }
                }
                _notice.value = "Файл сохранён"
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _notice.value = error.message
            } finally { _fileBusy.value = false }
        }
    }

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
        val bootstrap = stored ?: _state.value.bootstrapAddress
        if (bootstrap.isBlank()) {
            _state.value = NetworkState(phase = ConnectionPhase.STOPPED)
            return
        }
        launchNode(bootstrap, restart = false)
    }

    /** Switches the node to a user-supplied bootstrap node and remembers it. */
    fun reconnect(address: String) {
        if (_fileBusy.value || _state.value.transfer.busy) return
        val bootstrap = address.trim()
        if (dataDirectory == null) return
        if (!isValidBootstrap(bootstrap)) {
            _state.value = _state.value.copy(
                error = "Неверный адрес. Используйте формат хост:порт.",
            )
            return
        }
        bootstrapFile?.runCatching { writeText(bootstrap) }
        launchNode(bootstrap, restart = true)
    }

    fun retry() { if (!_fileBusy.value && !_state.value.transfer.busy) launchNode(_state.value.bootstrapAddress, restart = true) }

    private fun launchNode(bootstrap: String, restart: Boolean) {
        if (dataDirectory == null) return
        pollingJob?.cancel()
        _state.value = NetworkState(phase = ConnectionPhase.STARTING, bootstrapAddress = bootstrap)
        pollingJob = viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) {
                nodeMutex.withLock {
                    if (restart) repository.stop()
                    repository.start(requireNotNull(dataDirectory), bootstrap)
                }
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
