package dev.nyx.injector

import dev.nyx.injector.fs.BinaryReader
import dev.nyx.injector.fs.BinaryWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BinaryIoTest {

    @Test
    fun `round trips scalars big endian`() {
        val w = BinaryWriter()
        w.u8(0xAB)
        w.u16(0xBEEF)
        w.u32(0xDEADBEEFL)
        w.i64(-123456789012345L)
        w.stringNull("hello")
        w.stringAligned("aligned!")
        val bytes = w.toByteArray()

        val r = BinaryReader(bytes)
        assertEquals(0xAB, r.u8())
        assertEquals(0xBEEF, r.u16())
        assertEquals(0xDEADBEEFL, r.u32())
        assertEquals(-123456789012345L, r.i64())
        assertEquals("hello", r.stringNull())
        assertEquals("aligned!", r.stringAligned())
        assertEquals(0, r.remains())
    }

    @Test
    fun `round trips scalars little endian`() {
        val w = BinaryWriter().also { it.bigEndian = false }
        w.u16(0x1234)
        w.u32(0xCAFEBABEL)
        w.i64(-1L)
        val bytes = w.toByteArray()

        val r = BinaryReader(bytes).also { it.bigEndian = false }
        assertEquals(0x1234, r.u16())
        assertEquals(0xCAFEBABEL, r.u32())
        assertEquals(-1L, r.i64())
    }

    @Test
    fun `alignment pads to boundary`() {
        val w = BinaryWriter()
        w.u8(1)
        w.align(4)
        w.u32(7L)
        val bytes = w.toByteArray()
        assertEquals(8, bytes.size)
        assertEquals(0, bytes[1].toInt())
        assertEquals(0, bytes[2].toInt())
        assertEquals(0, bytes[3].toInt())
    }

    @Test
    fun `aligned string pads correctly`() {
        val w = BinaryWriter()
        w.stringAligned("abc") // 4 length + 3 bytes + 1 pad
        assertEquals(8, w.position)
        val r = BinaryReader(w.toByteArray())
        assertEquals("abc", r.stringAligned())
    }

    @Test
    fun `reader rejects out of bounds`() {
        val r = BinaryReader(byteArrayOf(1, 2))
        assertFailsWith<IllegalArgumentException> { r.bytes(3) }
        r.seek(2)
        assertFailsWith<IllegalArgumentException> { r.u8() }
    }

    @Test
    fun `bytes round trip`() {
        val payload = ByteArray(300) { it.toByte() }
        val w = BinaryWriter()
        w.bytes(payload)
        val r = BinaryReader(w.toByteArray())
        assertContentEquals(payload, r.bytes(300))
    }
}
