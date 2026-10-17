package dev.nyx.injector.serialized

import dev.nyx.injector.fs.BinaryReader
import java.io.ByteArrayOutputStream

/**
 * Minimal serialized-file reader/writer.
 *
 * Only the header + metadata prefix needed to reach the object table is
 * parsed; everything after is kept as opaque bytes. Object payloads can be
 * replaced in place — the object table fields are patched by absolute
 * position (their widths never change), the data region is rebuilt and the
 * header sizes are fixed up. No re-serialization of metadata needed.
 *
 * Supports serialized versions ~14..22 (Unity 2017..2020-ish), both endians.
 */
class SerializedFile private constructor(
    private val fileBytes: ByteArray,
    val bigEndian: Boolean,
    val version: Int,
    val metadataSize: Int,
    val dataOffset: Int,
    val unityVersion: String,
    val objects: List<ObjectEntry>,
    private val layout: Layout,
) {
    data class ObjectEntry(
        val pathId: Long,
        val typeId: Int,
        val classId: Int,
        val byteStart: Int,
        val byteSize: Int,
    )

    private data class Layout(
        val fileSizeFieldPos: Int,
        val fileSizeIs64: Boolean,
        val startFieldPos: IntArray,
        val sizeFieldPos: IntArray,
        val startFieldIs64: Boolean,
    )

    fun payload(entry: ObjectEntry): ByteArray =
        fileBytes.copyOfRange(entry.byteStart, entry.byteStart + entry.byteSize)

    /** Returns a new SerializedFile with [pathId]'s payload replaced. */
    fun replaceObject(pathId: Long, newPayload: ByteArray): SerializedFile {
        val index = objects.indexOfFirst { it.pathId == pathId }
        require(index >= 0) { "object 0x${pathId.toString(16)} not found" }

        val newFile = fileBytes.copyOf()
        val data = ByteArrayOutputStream()
        var cursor = dataOffset
        val newObjects = ArrayList<ObjectEntry>(objects.size)

        for ((i, obj) in objects.withIndex()) {
            while (cursor % 4 != 0) {
                data.write(0)
                cursor++
            }
            val payload = if (i == index) newPayload else payload(obj)
            patch(newFile, layout.startFieldPos[i], cursor.toLong(), layout.startFieldIs64)
            patch(newFile, layout.sizeFieldPos[i], payload.size.toLong(), false)
            newObjects.add(obj.copy(byteStart = cursor, byteSize = payload.size))
            data.write(payload)
            cursor += payload.size
        }
        patch(newFile, layout.fileSizeFieldPos, cursor.toLong(), layout.fileSizeIs64)

        val dataBytes = data.toByteArray()
        val out = ByteArray(dataOffset + dataBytes.size)
        System.arraycopy(newFile, 0, out, 0, dataOffset)
        System.arraycopy(dataBytes, 0, out, dataOffset, dataBytes.size)

        return SerializedFile(out, bigEndian, version, metadataSize, dataOffset, unityVersion, newObjects, layout)
    }

    fun toBytes(): ByteArray = fileBytes

    private fun patch(buf: ByteArray, pos: Int, value: Long, is64: Boolean) {
        if (is64) {
            if (bigEndian) {
                for (i in 0..7) buf[pos + i] = (value ushr (8 * (7 - i))).toByte()
            } else {
                for (i in 0..7) buf[pos + i] = (value ushr (8 * i)).toByte()
            }
        } else {
            val v = value.toInt()
            if (bigEndian) {
                buf[pos] = (v ushr 24).toByte()
                buf[pos + 1] = (v ushr 16).toByte()
                buf[pos + 2] = (v ushr 8).toByte()
                buf[pos + 3] = v.toByte()
            } else {
                buf[pos] = v.toByte()
                buf[pos + 1] = (v ushr 8).toByte()
                buf[pos + 2] = (v ushr 16).toByte()
                buf[pos + 3] = (v ushr 24).toByte()
            }
        }
    }

    companion object {
        fun parse(bytes: ByteArray): SerializedFile {
            val be = runCatching { parseWithEndian(bytes, true) }
            if (be.isSuccess) return be.getOrThrow()
            val le = runCatching { parseWithEndian(bytes, false) }
            if (le.isSuccess) return le.getOrThrow()
            throw IllegalArgumentException(
                "not a serialized file (BE: ${be.exceptionOrNull()?.message}; LE: ${le.exceptionOrNull()?.message})",
            )
        }

        private fun parseWithEndian(bytes: ByteArray, bigEndian: Boolean): SerializedFile {
            val r = BinaryReader(bytes, 0).also { it.bigEndian = bigEndian }

            var metadataSize = r.u32().toInt()
            var fileSizeFieldPos = 4
            r.u32() // legacy fileSize
            val version = r.u32().toInt()
            var dataOffset = r.u32()
            require(version in 5..30) { "implausible serialized version $version" }
            if (version >= 14) r.u8() // endianness marker; the heuristic above already decided
            r.align(4)

            var fileSizeIs64 = false
            if (version >= 22) {
                metadataSize = r.u32().toInt()
                fileSizeFieldPos = r.position
                fileSizeIs64 = true
                r.i64() // file size (i64)
                dataOffset = r.i64()
                r.i64() // unknown
            }

            require(metadataSize in 1 until bytes.size) { "implausible metadata size $metadataSize" }
            require(dataOffset in 1..bytes.size.toLong()) { "implausible data offset $dataOffset" }

            val unityVersion = r.stringAligned()
            r.i32() // target platform
            val enableTypeTree = if (version >= 13) r.u8() != 0 else false

            val typeCount = r.i32()
            require(typeCount in 0..100_000) { "implausible type count $typeCount" }
            val classIds = IntArray(typeCount)
            repeat(typeCount) { i ->
                val classId = r.i32()
                if (version >= 16) r.u8() // is stripped
                if (version >= 17) r.i16() // script type index
                if (classId == 114 && version >= 13) r.skip(16) // MonoBehaviour script hash
                if (enableTypeTree) skipTypeTree(r, version)
                classIds[i] = classId
            }

            val objectCount = r.i32()
            require(objectCount in 0..1_000_000) { "implausible object count $objectCount" }
            val objects = ArrayList<ObjectEntry>(objectCount)
            val startPos = IntArray(objectCount)
            val sizePos = IntArray(objectCount)
            repeat(objectCount) { i ->
                r.align(4)
                val pathId = r.i64()
                startPos[i] = r.position
                val byteStart = if (version >= 22) r.i64() else r.u32()
                sizePos[i] = r.position
                val byteSize = r.u32().toInt()
                val typeId = r.i32()
                require(byteStart >= 0 && byteSize >= 0 && byteStart + byteSize <= bytes.size.toLong()) {
                    "object $i out of bounds (start=$byteStart size=$byteSize file=${bytes.size})"
                }
                val classId = if (typeId in 0 until typeCount) classIds[typeId] else -1
                objects.add(ObjectEntry(pathId, typeId, classId, byteStart.toInt(), byteSize))
            }

            val layout = Layout(
                fileSizeFieldPos = fileSizeFieldPos,
                fileSizeIs64 = fileSizeIs64,
                startFieldPos = startPos,
                sizeFieldPos = sizePos,
                startFieldIs64 = version >= 22,
            )
            return SerializedFile(bytes, bigEndian, version, metadataSize, dataOffset.toInt(), unityVersion, objects, layout)
        }

        private fun skipTypeTree(r: BinaryReader, version: Int) {
            require(version >= 12) { "legacy type trees (serialized v$version) are not supported" }
            val nodeCount = r.i32()
            require(nodeCount in 0..1_000_000) { "implausible type tree node count" }
            repeat(nodeCount) {
                r.u16() // node version
                r.u8() // level
                r.u8() // type flags
                r.stringAligned() // type name
                r.stringAligned() // field name
                r.i32() // byte size
                r.i32() // index
                r.i32() // meta flag
                if (version >= 19) r.i64() // ref type hash
            }
            if (version >= 13) {
                val stringBufferSize = r.i32()
                require(stringBufferSize in 0..1_000_000) { "implausible type tree string buffer" }
                r.skip(stringBufferSize)
            }
            val dependencyCount = r.i32()
            require(dependencyCount in 0..100_000) { "implausible type dependency count" }
            r.skip(dependencyCount * 4)
        }
    }
}
