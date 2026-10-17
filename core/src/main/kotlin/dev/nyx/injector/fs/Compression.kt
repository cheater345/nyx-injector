package dev.nyx.injector.fs

import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAInputStream
import org.tukaani.xz.LZMAOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Compression ids as stored in UnityFS flags (low 6 bits). */
enum class Compression(val id: Int) {
    None(0),
    Lzma(1),
    Lz4(2),
    Lz4Hc(3);

    companion object {
        fun fromId(id: Int): Compression = when (id and 0x3F) {
            0 -> None
            1 -> Lzma
            2 -> Lz4
            3 -> Lz4Hc
            else -> throw IllegalArgumentException("unknown compression id $id")
        }

        fun fromName(name: String): Compression = when (name.lowercase()) {
            "none" -> None
            "lzma" -> Lzma
            "lz4" -> Lz4
            "lz4hc" -> Lz4Hc
            else -> throw IllegalArgumentException("unknown compression '$name' (none|lzma|lz4|lz4hc)")
        }
    }
}

object Compressor {

    fun decompress(type: Compression, data: ByteArray, uncompressedSize: Int): ByteArray = when (type) {
        Compression.None -> {
            require(data.size == uncompressedSize) {
                "uncompressed block size mismatch: ${data.size} != $uncompressedSize"
            }
            data
        }
        Compression.Lzma -> LZMAInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
        Compression.Lz4, Compression.Lz4Hc -> Lz4Block.decompress(data, 0, data.size, uncompressedSize)
    }

    fun compress(type: Compression, data: ByteArray): ByteArray = when (type) {
        Compression.None -> data
        Compression.Lzma -> ByteArrayOutputStream().also { out ->
            LZMAOutputStream(out, LZMA2Options(), data.size.toLong()).use { it.write(data) }
        }.toByteArray()
        Compression.Lz4, Compression.Lz4Hc -> Lz4Block.compress(data)
    }
}
