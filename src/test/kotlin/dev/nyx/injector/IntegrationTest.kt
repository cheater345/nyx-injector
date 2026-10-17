package dev.nyx.injector

import dev.nyx.injector.bundle.UnityFsArchive
import dev.nyx.injector.cli.Commands
import dev.nyx.injector.fs.BinaryWriter
import dev.nyx.injector.fs.Compression
import dev.nyx.injector.serialized.SerializedFile
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class IntegrationTest {

    /** Minimal single-object v21 serialized file (TextAsset-ish). */
    private fun buildSerialized(payload: ByteArray): ByteArray {
        val headerSize = 20
        val meta = BinaryWriter()
        meta.stringAligned("2019.4.31f1")
        meta.i32(19)
        meta.u8(0) // no type trees
        meta.i32(1)
        meta.i32(49)
        meta.u8(0)
        meta.i16(-1 and 0xFFFF)
        meta.i32(1) // one object
        meta.align(4)
        meta.i64(1L)
        val startPos = meta.position
        meta.u32(0) // byteStart placeholder
        meta.u32(payload.size.toLong())
        meta.i32(0)
        val metaBytes = meta.toByteArray()

        var dataOffset = headerSize + metaBytes.size
        while (dataOffset % 4 != 0) dataOffset++

        val patched = metaBytes.copyOf()
        for (b in 0..3) {
            patched[startPos + b] = ((dataOffset ushr (8 * (3 - b))) and 0xFF).toByte()
        }

        val total = dataOffset + payload.size
        val out = ByteArray(total)
        fun putU32(pos: Int, v: Int) {
            for (b in 0..3) out[pos + b] = ((v ushr (8 * (3 - b))) and 0xFF).toByte()
        }
        putU32(0, dataOffset - headerSize)
        putU32(4, total)
        putU32(8, 21)
        putU32(12, dataOffset)
        System.arraycopy(patched, 0, out, headerSize, patched.size)
        System.arraycopy(payload, 0, out, dataOffset, payload.size)
        return out
    }

    @Test
    fun `replace-object end to end then restore`() {
        val dir = createTempDirectory("nyx-it").toFile()
        val bundle = File(dir, "demo.bundle")

        val archive = UnityFsArchive(
            version = 7,
            unityVersion = "2019.4.31f1",
            unityRevision = "test",
            compression = Compression.Lz4,
            blocksInfoAtEnd = false,
            blocks = emptyList(),
            nodes = listOf(
                UnityFsArchive.Node("CAB-test", 4, buildSerialized("AAAA".toByteArray())),
                UnityFsArchive.Node("blob.resS", 4, "BLOB".toByteArray()),
            ),
        )
        bundle.writeBytes(archive.toBytes())

        Commands.verify(bundle)

        val payload = File(dir, "payload.bin")
        payload.writeBytes("NEWPAYLOAD-16B!!".toByteArray())

        Commands.replaceObject(bundle, "0", 1L, payload, null, inPlace = true)

        val patched = UnityFsArchive.parse(bundle.readBytes())
        assertContentEquals("BLOB".toByteArray(), patched.nodes[1].payload, "neighbor node must be untouched")
        val sf = SerializedFile.parse(patched.nodes[0].payload)
        assertContentEquals(payload.readBytes(), sf.payload(sf.objects[0]), "object payload must be the new bytes")

        Commands.verify(bundle)
        assertTrue(File(bundle.absolutePath + ".bak").exists(), "in-place write must leave a backup")

        Commands.restore(bundle)

        val restored = UnityFsArchive.parse(bundle.readBytes())
        val sfRestored = SerializedFile.parse(restored.nodes[0].payload)
        assertContentEquals("AAAA".toByteArray(), sfRestored.payload(sfRestored.objects[0]))
        assertContentEquals("BLOB".toByteArray(), restored.nodes[1].payload)
    }

    @Test
    fun `unpack then repack reproduces the same nodes`() {
        val dir = createTempDirectory("nyx-rt").toFile()
        val bundle = File(dir, "demo.bundle")
        val archive = UnityFsArchive(
            version = 7,
            unityVersion = "2019.4.31f1",
            unityRevision = "test",
            compression = Compression.Lz4,
            blocksInfoAtEnd = false,
            blocks = emptyList(),
            nodes = listOf(
                UnityFsArchive.Node("CAB-test", 4, buildSerialized("AAAA".toByteArray())),
                UnityFsArchive.Node("blob.resS", 4, "BLOB".toByteArray()),
            ),
        )
        bundle.writeBytes(archive.toBytes())

        val work = File(dir, "work")
        Commands.unpack(bundle, work)
        val rebuilt = File(dir, "rebuilt.bundle")
        Commands.repack(work, rebuilt, null, null, null, null)

        val original = UnityFsArchive.parse(bundle.readBytes())
        val roundTripped = UnityFsArchive.parse(rebuilt.readBytes())
        original.nodes.forEachIndexed { i, n ->
            assertContentEquals(n.payload, roundTripped.nodes[i].payload, "node $i payload preserved")
            assertTrue(n.path == roundTripped.nodes[i].path, "node $i path preserved")
        }
    }
}
