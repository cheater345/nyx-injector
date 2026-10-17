package dev.nyx.injector.cli

import dev.nyx.injector.bundle.UnityFsArchive
import dev.nyx.injector.fs.Compression
import dev.nyx.injector.serialized.ClassIds
import dev.nyx.injector.serialized.SerializedFile
import java.io.File

object Commands {

    // ---------- read-only ----------

    fun info(bundle: File) {
        val archive = UnityFsArchive.parse(bundle.readBytes())
        println("file        : ${bundle.absolutePath}")
        println("format      : UnityFS v${archive.version} (unity ${archive.unityVersion}, revision ${archive.unityRevision})")
        val placement = if (archive.blocksInfoAtEnd) "blocks info at end" else "blocks info inline"
        println("compression : ${archive.compression.name.lowercase()} ($placement)")
        println("blocks      : ${archive.blocks.size}")
        println("nodes       : ${archive.nodes.size}")
        archive.nodes.forEachIndexed { i, n ->
            val label = n.path.ifEmpty { "(serialized)" }
            println("  [%3d]  %-48s %10d bytes  flags=0x%X".format(i, label, n.payload.size, n.flags))
        }
        archive.notes.forEach { println("note: $it") }
    }

    fun listObjects(bundle: File, selector: String?) {
        val archive = UnityFsArchive.parse(bundle.readBytes())
        var found = false
        archive.nodes.forEachIndexed { i, n ->
            if (selector != null && !matches(n, i, selector)) return@forEachIndexed
            val sf = runCatching { SerializedFile.parse(n.payload) }.getOrNull() ?: return@forEachIndexed
            found = true
            val label = n.path.ifEmpty { "(serialized)" }
            println("node [$i] $label — serialized v${sf.version}, ${sf.objects.size} objects")
            sf.objects.forEach { o ->
                println("    id=%-12d %-18s %9d bytes  (typeId ${o.typeId})".format(o.pathId, ClassIds.name(o.classId), o.byteSize))
            }
        }
        if (!found) println("no serialized nodes matched${if (selector != null) " '$selector'" else ""}")
    }

    fun verify(bundle: File) {
        val archive = UnityFsArchive.parse(bundle.readBytes())
        var objectCount = 0
        var serializedCount = 0
        archive.nodes.forEachIndexed { i, n ->
            val sf = runCatching { SerializedFile.parse(n.payload) }.getOrNull() ?: return@forEachIndexed
            sf.objects.forEach { o ->
                require(o.byteStart + o.byteSize <= n.payload.size) { "node [$i]: object 0x${o.pathId.toString(16)} out of node bounds" }
            }
            objectCount += sf.objects.size
            serializedCount++
            println("node [$i] ${n.path.ifEmpty { "(serialized)" }}: serialized v${sf.version}, ${sf.objects.size} objects ok")
        }
        println("ok — ${archive.nodes.size} nodes, $serializedCount serialized files, $objectCount objects, no out-of-bounds references")
    }

    // ---------- round trip ----------

    fun unpack(bundle: File, outDir: File) {
        val archive = UnityFsArchive.parse(bundle.readBytes())
        outDir.mkdirs()
        val manifest = StringBuilder()
        manifest.append("# nyx-injector manifest v1\n")
        manifest.append("# unityVersion=${archive.unityVersion}\n")
        manifest.append("# unityRevision=${archive.unityRevision}\n")
        manifest.append("# formatVersion=${archive.version}\n")
        manifest.append("# compression=${archive.compression.name.lowercase()}\n")
        manifest.append("index\tpath\tflags\tsize\tfile\n")
        archive.nodes.forEachIndexed { i, n ->
            val fileName = "%03d_%s".format(i, sanitize(n.path.ifEmpty { "serialized" }))
            File(outDir, fileName).writeBytes(n.payload)
            manifest.append("$i\t${n.path}\t${n.flags}\t${n.payload.size}\t$fileName\n")
        }
        File(outDir, "manifest.tsv").writeText(manifest.toString())
        println("unpacked ${archive.nodes.size} nodes to ${outDir.absolutePath}")
        println("manifest: ${File(outDir, "manifest.tsv").absolutePath}")
    }

    fun repack(
        inDir: File,
        out: File,
        compressName: String?,
        unityVersionOverride: String?,
        unityRevisionOverride: String?,
        versionOverride: Int?,
    ) {
        val manifestFile = File(inDir, "manifest.tsv")
        require(manifestFile.exists()) { "manifest.tsv not found in ${inDir.absolutePath}" }

        val header = HashMap<String, String>()
        data class Row(val path: String, val flags: Int, val file: File)

        val rows = ArrayList<Row>()
        manifestFile.readLines().forEach { line ->
            when {
                line.startsWith("#") -> {
                    val body = line.removePrefix("#").trim()
                    val eq = body.indexOf('=')
                    if (eq > 0) header[body.substring(0, eq)] = body.substring(eq + 1)
                }
                line.isBlank() || line.startsWith("index\t") -> Unit
                else -> {
                    val parts = line.split("\t")
                    require(parts.size >= 5) { "bad manifest row: $line" }
                    rows.add(Row(parts[1], parts[2].toInt(), File(inDir, parts[4])))
                }
            }
        }
        require(rows.isNotEmpty()) { "manifest has no node rows" }

        val unityVersion = unityVersionOverride ?: header["unityVersion"]
        ?: throw IllegalArgumentException("missing unityVersion — pass --unity-version")
        val unityRevision = unityRevisionOverride ?: header["unityRevision"] ?: ""
        val version = versionOverride ?: header["formatVersion"]?.toIntOrNull() ?: 7
        val compression = compressName?.let { Compression.fromName(it) }
            ?: Compression.fromName(header["compression"] ?: "lz4")

        rows.forEach { require(it.file.exists()) { "missing node file: ${it.file.absolutePath}" } }

        val built = UnityFsArchive(
            version = version,
            unityVersion = unityVersion,
            unityRevision = unityRevision,
            compression = compression,
            blocksInfoAtEnd = false,
            blocks = emptyList(),
            nodes = rows.map { UnityFsArchive.Node(it.path, it.flags, it.file.readBytes()) },
        )
        val bytes = built.toBytes(compression)
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        println("repacked ${rows.size} nodes, ${compression.name.lowercase()} — ${bytes.size} bytes -> ${out.absolutePath}")
    }

    // ---------- injection ----------

    fun replaceNode(bundle: File, selector: String, payload: File, out: File?, inPlace: Boolean) {
        val archive = UnityFsArchive.parse(bundle.readBytes())
        val idx = findNode(archive, selector)
        val old = archive.nodes[idx].payload.size
        val updated = archive.withNodePayload(idx, payload.readBytes())
        writeOutput(bundle, updated.toBytes(), out, inPlace)
        val label = archive.nodes[idx].path.ifEmpty { "(serialized)" }
        println("node [$idx] $label: $old -> ${updated.nodes[idx].payload.size} bytes")
        println("wrote ${(out ?: bundle).absolutePath}${if (inPlace) " (backup at ${bundle.absolutePath}.bak)" else ""}")
    }

    fun replaceObject(bundle: File, selector: String, pathId: Long, payload: File, out: File?, inPlace: Boolean) {
        val archive = UnityFsArchive.parse(bundle.readBytes())
        val idx = findNode(archive, selector)
        val sf = SerializedFile.parse(archive.nodes[idx].payload)
        val obj = sf.objects.firstOrNull { it.pathId == pathId }
            ?: throw IllegalArgumentException("object 0x${pathId.toString(16)} not found in node [$idx]")

        val newPayload = payload.readBytes()
        val newArchiveBytes = sf.replaceObject(pathId, newPayload).toBytes()

        // prove the result re-reads before touching anything on disk
        val reparsed = SerializedFile.parse(newArchiveBytes)
        val reparsedObj = reparsed.objects.firstOrNull { it.pathId == pathId }
            ?: throw IllegalStateException("internal verification failed: object disappeared")
        check(reparsedObj.byteSize == newPayload.size && reparsed.payload(reparsedObj).contentEquals(newPayload)) {
            "internal verification failed: payload mismatch after patch"
        }

        writeOutput(bundle, archive.withNodePayload(idx, newArchiveBytes).toBytes(), out, inPlace)
        println("object 0x${pathId.toString(16)} (${ClassIds.name(obj.classId)}) in node [$idx]: ${obj.byteSize} -> ${newPayload.size} bytes")
        println("note: the payload must match the original asset's raw layout (e.g. Texture2D image data bytes)")
        println("wrote ${(out ?: bundle).absolutePath}${if (inPlace) " (backup at ${bundle.absolutePath}.bak)" else ""}")
    }

    fun restore(bundle: File) {
        val bak = File(bundle.absolutePath + ".bak")
        require(bak.exists()) { "no backup at ${bak.absolutePath}" }
        bak.copyTo(bundle, overwrite = true)
        println("restored ${bundle.absolutePath} from backup")
    }

    // ---------- helpers ----------

    private fun writeOutput(original: File, bytes: ByteArray, out: File?, inPlace: Boolean) {
        if (inPlace) {
            val bak = File(original.absolutePath + ".bak")
            if (!bak.exists()) original.copyTo(bak, overwrite = false)
            original.writeBytes(bytes)
        } else {
            val target = out ?: throw IllegalArgumentException("pass --out FILE or --in-place")
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
    }

    private fun findNode(archive: UnityFsArchive, selector: String): Int {
        selector.toIntOrNull()?.let { i ->
            require(i in archive.nodes.indices) { "node index $i out of range (0..${archive.nodes.size - 1})" }
            return i
        }
        val byPath = archive.nodes.indexOfFirst { it.path == selector }
        if (byPath >= 0) return byPath
        val byContains = archive.nodes.indexOfFirst { it.path.contains(selector) }
        require(byContains >= 0) { "node '$selector' not found" }
        return byContains
    }

    private fun matches(n: UnityFsArchive.Node, index: Int, selector: String): Boolean {
        selector.toIntOrNull()?.let { return it == index }
        return n.path == selector || n.path.contains(selector)
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "node" }
}
