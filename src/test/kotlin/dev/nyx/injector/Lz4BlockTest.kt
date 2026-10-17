package dev.nyx.injector

import dev.nyx.injector.fs.Lz4Block
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Lz4BlockTest {

    @Test
    fun `decompresses a handcrafted sequence`() {
        // token 0x1B: 1 literal, match length 11+4=15; literal 'a'; offset 1 (little endian)
        val compressed = byteArrayOf(0x1B, 'a'.code.toByte(), 0x01, 0x00)
        val out = Lz4Block.decompress(compressed, 0, compressed.size, 16)
        assertContentEquals(ByteArray(16) { 'a'.code.toByte() }, out)
    }

    @Test
    fun `decompresses empty block`() {
        val compressed = byteArrayOf(0x00)
        val out = Lz4Block.decompress(compressed, 0, compressed.size, 0)
        assertEquals(0, out.size)
    }

    @Test
    fun `round trips repetitive data`() {
        val data = "abcabcabcabcabcabcabcabcabcabc".toByteArray()
        val compressed = Lz4Block.compress(data)
        assertContentEquals(data, Lz4Block.decompress(compressed, 0, compressed.size, data.size))
    }

    @Test
    fun `round trips a long run`() {
        val data = ByteArray(100_000) { 0x42 }
        val compressed = Lz4Block.compress(data)
        assertContentEquals(data, Lz4Block.decompress(compressed, 0, compressed.size, data.size))
    }

    @Test
    fun `round trips incompressible data`() {
        val data = Random(42).nextBytes(65_536)
        val compressed = Lz4Block.compress(data)
        assertContentEquals(data, Lz4Block.decompress(compressed, 0, compressed.size, data.size))
    }

    @Test
    fun `round trips mixed content`() {
        val rng = Random(7)
        val data = ByteArray(200_000) { i ->
            when {
                i % 97 == 0 -> 0x00
                i % 5 == 0 -> rng.nextInt(256).toByte()
                else -> (i / 13).toByte()
            }
        }
        val compressed = Lz4Block.compress(data)
        assertContentEquals(data, Lz4Block.decompress(compressed, 0, compressed.size, data.size))
    }

    @Test
    fun `rejects corrupt stream`() {
        // literal run longer than the block
        val bad = byteArrayOf(0xF0.toByte(), 0x05)
        assertFailsWith<IllegalArgumentException> {
            Lz4Block.decompress(bad, 0, bad.size, 4)
        }
    }
}
