package dev.nyx.injector

import dev.nyx.injector.fs.BinaryWriter
import dev.nyx.injector.serialized.SerializedFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SerializedFileTest {

    private data class Built(val bytes: ByteArray, val dataOffset: Int)

    /**
     * Minimal v21 file: 2 TextAsset-ish objects, no type trees.
     * metadata: unityVersion | targetPlatform | enableTypeTree=0 | 1 type | 2 objects
     */
    private fun buildSample(bigEndian: Boolean): Built {
        val headerSize = 20 // 4x u32 + endian byte + 3 pad

        val meta = BinaryWriter().also { it.bigEndian = bigEndian }
        meta.stringAligned("2019.4.31f1")
        meta.i32(19) // target platform (Android)
        meta.u8(0) // enableTypeTree = false
        meta.i32(1) // typeCount
        meta.i32(49) // classId TextAsset
        meta.u8(0) // isStripped
        meta.i16(-1 and 0xFFFF) // scriptTypeIndex
        meta.i32(2) // objectCount

        // object table — byteStart values patched after we know dataOffset; use placeholder then rewrite
        // record positions relative to metadata start
        val objPaths = longArrayOf(1L, 2L)
        val sizes = intArrayOf(4, 6)

        // we need the absolute byteStart of each object's data: header(20) + meta size (+padding)
        // two-pass: write table with zeros, note positions, then patch
        val tablePos = ArrayList<Int>()
        for (i in 0..1) {
            meta.align(4)
            meta.i64(objPaths[i])
            tablePos.add(meta.position)
            meta.u32(0L) // byteStart placeholder
            meta.u32(sizes[i].toLong())
            meta.i32(0) // typeId
        }
        val metaBytes = meta.toByteArray()

        var dataOffset = headerSize + metaBytes.size
        while (dataOffset % 4 != 0) dataOffset++

        // patch byteStarts: absolute offsets (table positions are metadata-relative)
        var cursor = dataOffset
        val patched = metaBytes.copyOf()
        for (i in 0..1) {
            val pos = tablePos[i]
            val abs = cursor
            for (b in 0..3) {
                patched[pos + b] = if (bigEndian) (abs ushr (8 * (3 - b))).toByte() else (abs ushr (8 * b)).toByte()
            }
            cursor = (cursor + sizes[i] + 3) / 4 * 4
        }

        val data = ByteArray(cursor - dataOffset)
        var rel = 0
        for (i in 0..1) {
            rel = (rel + 3) / 4 * 4
            for (j in 0 until sizes[i]) data[rel + j] = (if (i == 0) 0x41 else 0x42).toByte()
            rel += sizes[i]
        }

        val total = dataOffset + data.size
        val out = ByteArray(total)
        // header
        fun putU32(pos: Int, v: Int) {
            if (bigEndian) {
                for (b in 0..3) out[pos + b] = (v ushr (8 * (3 - b))).toByte()
            } else {
                for (b in 0..3) out[pos + b] = (v ushr (8 * b)).toByte()
            }
        }
        putU32(0, dataOffset - headerSize) // metadataSize
        putU32(4, total)
        putU32(8, 21) // version
        putU32(12, dataOffset)
        out[16] = if (bigEndian) 0 else 1
        System.arraycopy(patched, 0, out, headerSize, patched.size)
        System.arraycopy(data, 0, out, dataOffset, data.size)
        return Built(out, dataOffset)
    }

    @Test
    fun `parses a big endian serialized file`() {
        val sf = SerializedFile.parse(buildSample(true).bytes)
        assertEquals(21, sf.version)
        assertEquals("2019.4.31f1", sf.unityVersion)
        assertTrue(sf.bigEndian)
        assertEquals(2, sf.objects.size)
        assertEquals(49, sf.objects[0].classId)
        assertContentEquals(byteArrayOf(0x41, 0x41, 0x41, 0x41), sf.payload(sf.objects[0]))
        assertContentEquals(ByteArray(6) { 0x42 }, sf.payload(sf.objects[1]))
    }

    @Test
    fun `parses a little endian serialized file`() {
        val sf = SerializedFile.parse(buildSample(false).bytes)
        assertEquals(21, sf.version)
        assertTrue(!sf.bigEndian)
        assertEquals(2, sf.objects.size)
    }

    @Test
    fun `replaces an object payload`() {
        val sf = SerializedFile.parse(buildSample(true).bytes)
        val replacement = ByteArray(10) { 0x55 }
        val updated = sf.replaceObject(2L, replacement)
        val reparsed = SerializedFile.parse(updated.toBytes())

        assertEquals(2, reparsed.objects.size)
        val obj2 = reparsed.objects.first { it.pathId == 2L }
        assertEquals(10, obj2.byteSize)
        assertContentEquals(replacement, reparsed.payload(obj2))
        // object 1 untouched
        val obj1 = reparsed.objects.first { it.pathId == 1L }
        assertContentEquals(byteArrayOf(0x41, 0x41, 0x41, 0x41), reparsed.payload(obj1))
    }

    @Test
    fun `replace shrinks fine`() {
        val sf = SerializedFile.parse(buildSample(true).bytes)
        val updated = sf.replaceObject(1L, byteArrayOf(0x7F))
        val reparsed = SerializedFile.parse(updated.toBytes())
        val obj1 = reparsed.objects.first { it.pathId == 1L }
        assertEquals(1, obj1.byteSize)
        assertContentEquals(byteArrayOf(0x7F), reparsed.payload(obj1))
    }

    @Test
    fun `unknown path id throws`() {
        val sf = SerializedFile.parse(buildSample(true).bytes)
        assertFailsWith<IllegalArgumentException> { sf.replaceObject(99L, byteArrayOf(1)) }
    }

    @Test
    fun `rejects garbage`() {
        assertFailsWith<IllegalArgumentException> {
            SerializedFile.parse(ByteArray(64) { 0xFF.toByte() })
        }
    }
}
