package dev.nyx.injector.fs

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class BinaryWriter {
    private val out = ByteArrayOutputStream(1 shl 16)
    var bigEndian: Boolean = true

    val position: Int get() = out.size()

    fun u8(v: Int) {
        out.write(v and 0xFF)
    }

    fun bytes(b: ByteArray) {
        out.write(b)
    }

    fun u16(v: Int) {
        if (bigEndian) { u8(v ushr 8); u8(v) } else { u8(v); u8(v ushr 8) }
    }

    fun i16(v: Int) {
        u16(v and 0xFFFF)
    }

    fun u32(v: Long) {
        if (bigEndian) {
            u8((v ushr 24).toInt()); u8((v ushr 16).toInt()); u8((v ushr 8).toInt()); u8(v.toInt())
        } else {
            u8(v.toInt()); u8((v ushr 8).toInt()); u8((v ushr 16).toInt()); u8((v ushr 24).toInt())
        }
    }

    fun i32(v: Int) {
        u32(v.toLong() and 0xFFFFFFFFL)
    }

    fun u64(v: Long) {
        if (bigEndian) {
            for (i in 7 downTo 0) u8((v ushr (8 * i)).toInt())
        } else {
            for (i in 0..7) u8((v ushr (8 * i)).toInt())
        }
    }

    fun i64(v: Long) = u64(v)

    fun align(alignment: Int = 4) {
        while (position % alignment != 0) u8(0)
    }

    fun stringNull(s: String) {
        bytes(s.toByteArray(StandardCharsets.UTF_8))
        u8(0)
    }

    fun stringAligned(s: String) {
        val b = s.toByteArray(StandardCharsets.UTF_8)
        u32(b.size.toLong())
        bytes(b)
        align(4)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}
