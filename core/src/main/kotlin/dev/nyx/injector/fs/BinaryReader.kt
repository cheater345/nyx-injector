package dev.nyx.injector.fs

import java.nio.charset.StandardCharsets

/** Big-endian by default — the layout everything Unity writes starts with. */
class BinaryReader(val data: ByteArray, var position: Int = 0) {
    var bigEndian: Boolean = true

    fun seek(p: Int) {
        require(p in 0..data.size) { "seek out of range: $p (size=${data.size})" }
        position = p
    }

    fun skip(n: Int) {
        require(n >= 0) { "negative skip: $n" }
        seek(position + n)
    }

    fun align(alignment: Int = 4) {
        val rem = position % alignment
        if (rem != 0) skip(alignment - rem)
    }

    fun remains(): Int = data.size - position

    fun u8(): Int {
        require(position < data.size) { "read past end" }
        return data[position++].toInt() and 0xFF
    }

    fun u16(): Int {
        val a = u8()
        val b = u8()
        return if (bigEndian) (a shl 8) or b else a or (b shl 8)
    }

    fun i16(): Int {
        val v = u16()
        return if (v >= 0x8000) v - 0x10000 else v
    }

    fun u32(): Long {
        val a = u8().toLong()
        val b = u8().toLong()
        val c = u8().toLong()
        val d = u8().toLong()
        return if (bigEndian) (a shl 24) or (b shl 16) or (c shl 8) or d
        else (d shl 24) or (c shl 16) or (b shl 8) or a
    }

    fun i32(): Int = u32().toInt()

    fun u64(): Long =
        if (bigEndian) {
            var v = 0L
            repeat(8) { v = (v shl 8) or u8().toLong() }
            v
        } else {
            var v = 0L
            for (i in 0..7) v = v or (u8().toLong() shl (8 * i))
            v
        }

    fun i64(): Long = u64()

    fun bytes(n: Int): ByteArray {
        require(n >= 0 && position + n <= data.size) {
            "read past end: want $n at $position (size=${data.size})"
        }
        val out = data.copyOfRange(position, position + n)
        position += n
        return out
    }

    /** C-string (null terminated). */
    fun stringNull(): String {
        val start = position
        while (position < data.size && data[position].toInt() != 0) position++
        val s = String(data, start, position - start, StandardCharsets.UTF_8)
        if (position < data.size) position++ // consume the terminator
        return s
    }

    /** Length-prefixed string, padded to a 4-byte boundary (Unity metadata style). */
    fun stringAligned(): String {
        val len = u32().toInt()
        require(len >= 0 && position + len <= data.size) { "bad aligned string length $len at $position" }
        val s = String(bytes(len), StandardCharsets.UTF_8)
        align(4)
        return s
    }
}
