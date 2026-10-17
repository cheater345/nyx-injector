package dev.nyx.injector.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.nyx.injector.bundle.UnityFsArchive
import dev.nyx.injector.serialized.ClassIds
import dev.nyx.injector.serialized.SerializedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ObjectView(val pathId: Long, val className: String, val byteSize: Int)

data class NodeView(
    val index: Int,
    val path: String,
    val size: Int,
    val serialized: Boolean,
    val objects: List<ObjectView>,
)

data class InjectorState(
    val bundleName: String = "",
    val loaded: Boolean = false,
    val unityVersion: String = "",
    val compression: String = "",
    val nodes: List<NodeView> = emptyList(),
    val pendingLabel: String? = null,
    val busy: Boolean = false,
    val status: String = "open a bundle to begin",
    val journal: List<String> = emptyList(),
)

/** nodeIndex + pathId (null = node-level replace) + payload source */
private data class PendingReplace(val nodeIndex: Int, val pathId: Long?, val payloadUri: Uri, val label: String)

class InjectorViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(InjectorState())
    val state: StateFlow<InjectorState> = _state.asStateFlow()

    private var archive: UnityFsArchive? = null
    private var pending: PendingReplace? = null

    private val journalFile: File get() = File(getApplication<Application>().filesDir, "journal.tsv")

    init {
        refreshJournal()
    }

    fun openBundle(uri: Uri) = viewModelScope.launch {
        _state.update { it.copy(busy = true, status = "reading bundle…") }
        try {
            val bytes = withContext(Dispatchers.IO) { readAll(uri) }
            val parsed = withContext(Dispatchers.Default) { UnityFsArchive.parse(bytes) }
            val nodes = withContext(Dispatchers.Default) {
                parsed.nodes.mapIndexed { i, n ->
                    val sf = runCatching { SerializedFile.parse(n.payload) }.getOrNull()
                    NodeView(
                        index = i,
                        path = n.path.ifEmpty { "(serialized)" },
                        size = n.payload.size,
                        serialized = sf != null,
                        objects = sf?.objects?.map { o -> ObjectView(o.pathId, ClassIds.name(o.classId), o.byteSize) }
                            ?: emptyList(),
                    )
                }
            }
            archive = parsed
            pending = null
            _state.update {
                it.copy(
                    bundleName = uri.lastPathSegment ?: "bundle",
                    loaded = true,
                    unityVersion = parsed.unityVersion,
                    compression = parsed.compression.name.lowercase(),
                    nodes = nodes,
                    pendingLabel = null,
                    busy = false,
                    status = "loaded — ${nodes.size} nodes, unity ${parsed.unityVersion}",
                )
            }
            journal("open", uri.lastPathSegment ?: "bundle", "ok: ${parsed.nodes.size} nodes")
        } catch (t: Throwable) {
            _state.update { it.copy(busy = false, status = "load failed — ${t.message ?: t::class.java.simpleName}") }
            journal("open", uri.lastPathSegment ?: "bundle", "error: ${t.message}")
        }
    }

    fun pickNodePayload(nodeIndex: Int, payloadUri: Uri) {
        pending = PendingReplace(nodeIndex, null, payloadUri, "node $nodeIndex")
        _state.update {
            it.copy(pendingLabel = "node $nodeIndex", status = "node $nodeIndex payload picked — save the patched bundle when ready")
        }
    }

    fun pickObjectPayload(nodeIndex: Int, pathId: Long, payloadUri: Uri) {
        val label = "object 0x${pathId.toString(16)} in node $nodeIndex"
        pending = PendingReplace(nodeIndex, pathId, payloadUri, label)
        _state.update {
            it.copy(pendingLabel = label, status = "$label payload picked — save the patched bundle when ready")
        }
    }

    fun savePatched(out: Uri) = viewModelScope.launch {
        val p = pending
        if (p == null) {
            _state.update { it.copy(status = "nothing pending — pick a payload first") }
            return@launch
        }
        val base = archive
        if (base == null) {
            _state.update { it.copy(status = "no bundle loaded") }
            return@launch
        }
        _state.update { it.copy(busy = true, status = "injecting…") }
        try {
            val payloadBytes = withContext(Dispatchers.IO) { readAll(p.payloadUri) }
            val outBytes = withContext(Dispatchers.Default) {
                val updated = if (p.pathId == null) {
                    base.withNodePayload(p.nodeIndex, payloadBytes)
                } else {
                    val sf = SerializedFile.parse(base.nodes[p.nodeIndex].payload)
                    val rebuilt = sf.replaceObject(p.pathId, payloadBytes).toBytes()
                    // prove it re-reads before it leaves the device
                    val check = SerializedFile.parse(rebuilt)
                    val obj = check.objects.firstOrNull { it.pathId == p.pathId }
                    require(obj != null && check.payload(obj).contentEquals(payloadBytes)) {
                        "internal verification failed"
                    }
                    base.withNodePayload(p.nodeIndex, rebuilt)
                }
                updated.toBytes()
            }
            withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                resolver.openOutputStream(out)?.use { it.write(outBytes) }
                    ?: throw IllegalStateException("cannot open output destination")
            }
            pending = null
            _state.update {
                it.copy(
                    busy = false,
                    pendingLabel = null,
                    status = "patched bundle saved — ${outBytes.size} bytes",
                )
            }
            journal("inject", p.label, "ok: ${payloadBytes.size} B payload -> ${outBytes.size} B bundle")
        } catch (t: Throwable) {
            _state.update { it.copy(busy = false, status = "inject failed — ${t.message ?: t::class.java.simpleName}") }
            journal("inject", p.label, "error: ${t.message}")
        }
    }

    private fun readAll(uri: Uri): ByteArray =
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("cannot open $uri")

    private fun journal(op: String, target: String, detail: String) {
        runCatching { journalFile.appendText("${System.currentTimeMillis()}\t$op\t$target\t$detail\n") }
        refreshJournal()
    }

    private fun refreshJournal() {
        val lines = runCatching { journalFile.readLines().takeLast(50).reversed() }.getOrDefault(emptyList())
        _state.update { it.copy(journal = lines) }
    }
}
