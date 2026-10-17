package dev.nyx.injector

import dev.nyx.injector.bundle.UnityFsArchive
import dev.nyx.injector.fs.Compression
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UnityFsTest {

    private fun buildArchive(compression: Compression, payloads: List<ByteArray>): UnityFsArchive =
        UnityFsArchive(
            version = 7,
            unityVersion = "2019.4.31f1",
            unityRevision = "f0d3d5f9d5e1",
            compression = compression,
            blocksInfoAtEnd = false,
            blocks = emptyList(),
            nodes = payloads.mapIndexed { i, p ->
                UnityFsArchive.Node(if (i == 0) "CAB-test$i" else "file$i.resS", 4, p)
            },
        )

    private fun roundTrip(compression: Compression, payloads: List<ByteArray>) {
        val bytes = buildArchive(compression, payloads).toBytes()
        val parsed = UnityFsArchive.parse(bytes)
        assertEquals(7, parsed.version)
        assertEquals("2019.4.31f1", parsed.unityVersion)
        assertEquals("f0d3d5f9d5e1", parsed.unityRevision)
        assertEquals(compression, parsed.compression)
        assertEquals(payloads.size, parsed.nodes.size)
        payloads.forEachIndexed { i, expected ->
            assertContentEquals(expected, parsed.nodes[i].payload, "payload $i mismatch")
        }
        assertTrue(parsed.notes.isEmpty(), "unexpected notes: ${parsed.notes}")
    }

    @Test
    fun `round trips with lz4`() {
        roundTrip(
            Compression.Lz4,
            listOf(
                "hello unity bundle".toByteArray(),
                Random(1).nextBytes(64),
                ByteArray(4096) { (it % 251).toByte() },
            ),
        )
    }

    @Test
    fun `round trips with no compression`() {
        roundTrip(Compression.None, listOf("payload-a".toByteArray(), "payload-b".toByteArray()))
    }

    @Test
    fun `round trips with lzma`() {
        roundTrip(Compression.Lzma, listOf(ByteArray(1024) { it.toByte() }))
    }

    @Test
    fun `round trips a payload larger than one block`() {
        val big = Random(9).nextBytes(300_000)
        val bytes = buildArchive(Compression.Lz4, listOf(big, "tail".toByteArray())).toBytes()
        val parsed = UnityFsArchive.parse(bytes)
        assertTrue(parsed.blocks.size >= 3, "expected multiple blocks, got ${parsed.blocks.size}")
        assertContentEquals(big, parsed.nodes[0].payload)
        assertContentEquals("tail".toByteArray(), parsed.nodes[1].payload)
    }

    @Test
    fun `withNodePayload replaces only the target`() {
        val archive = buildArchive(Compression.Lz4, listOf("one".toByteArray(), "two".toByteArray()))
        val updated = archive.withNodePayload(1, "TWO-REPLACED".toByteArray())
        val parsed = UnityFsArchive.parse(updated.toBytes())
        assertContentEquals("one".toByteArray(), parsed.nodes[0].payload)
        assertContentEquals("TWO-REPLACED".toByteArray(), parsed.nodes[1].payload)
    }

    @Test
    fun `node paths and flags survive`() {
        val bytes = buildArchive(Compression.Lz4, listOf("x".toByteArray())).toBytes()
        val parsed = UnityFsArchive.parse(bytes)
        assertEquals("CAB-test0", parsed.nodes[0].path)
        assertEquals(4, parsed.nodes[0].flags)
    }

    @Test
    fun `rejects garbage`() {
        assertFailsWith<IllegalArgumentException> {
            UnityFsArchive.parse("this is definitely not a unity bundle".toByteArray())
        }
    }
}
