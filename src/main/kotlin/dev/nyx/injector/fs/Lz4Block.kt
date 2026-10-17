package dev.nyx.injector.fs

/**
 * Raw LZ4 block format (no framing), which is what sits inside UnityFS
 * blocks flagged as LZ4 / LZ4HC. Compression is a straightforward greedy
 * matcher — not the fastest encoder, but correct and dependency-free.
 */
object Lz4Block {

    private const val MIN_MATCH = 4
    private const val LAST_LITERALS = 5
    private const val MF_LIMIT = 12
    private const val HASH_LOG = 16
    private const val HASH_SIZE = 1 shl HASH_LOG
    private const val MAX_OFFSET = 0xFFFF

    fun decompress(src: ByteArray, srcOffset: Int, srcLength: Int, uncompressedSize: Int): ByteArray {
        val dst = ByteArray(uncompressedSize)
        var s = srcOffset
        val sEnd = srcOffset + srcLength
        var produced = 0

        while (s < sEnd) {
            val token = src[s++].toInt() and 0xFF

            var literalLength = token ushr 4
            if (literalLength == 15) {
                while (true) {
                    require(s < sEnd) { "lz4: truncated literal length extension" }
                    val b = src[s++].toInt() and 0xFF
                    literalLength += b
                    if (b != 255) break
                }
            }
            require(s + literalLength <= sEnd) { "lz4: literal run past end of input" }
            require(produced + literalLength <= uncompressedSize) { "lz4: literal run exceeds block size" }
            System.arraycopy(src, s, dst, produced, literalLength)
            s += literalLength
            produced += literalLength

            if (s >= sEnd) break // last sequence: literals only

            require(s + 2 <= sEnd) { "lz4: match without offset" }
            val offset = (src[s].toInt() and 0xFF) or ((src[s + 1].toInt() and 0xFF) shl 8)
            s += 2
            require(offset != 0 && offset <= produced) { "lz4: bad match offset $offset" }

            var matchLength = (token and 0xF) + MIN_MATCH
            if ((token and 0xF) == 15) {
                while (true) {
                    require(s < sEnd) { "lz4: truncated match length extension" }
                    val b = src[s++].toInt() and 0xFF
                    matchLength += b
                    if (b != 255) break
                }
            }
            require(produced + matchLength <= uncompressedSize) { "lz4: match exceeds block size" }

            // Overlapping copies are legal — copy byte-by-byte so the pattern repeats.
            var m = produced - offset
            for (i in 0 until matchLength) {
                dst[produced + i] = dst[m + i]
            }
            produced += matchLength
        }

        require(produced == uncompressedSize) { "lz4: produced $produced bytes, expected $uncompressedSize" }
        return dst
    }

    fun compress(src: ByteArray, srcOffset: Int = 0, srcLength: Int = src.size): ByteArray {
        val out = ByteArray(srcLength + srcLength / 255 + 16)
        if (srcLength < MF_LIMIT + 1) {
            return out.copyOf(writeLastLiterals(src, srcOffset, srcLength, out, 0))
        }

        val table = IntArray(HASH_SIZE) { -1 }
        var op = 0
        var anchor = srcOffset
        val iend = srcOffset + srcLength
        var ip = srcOffset

        while (ip <= iend - MF_LIMIT) {
            val h = hash(read32(src, ip))
            val candidate = table[h]
            table[h] = ip

            if (candidate >= srcOffset && ip - candidate <= MAX_OFFSET && read32(src, candidate) == read32(src, ip)) {
                var mlen = MIN_MATCH
                while (ip + mlen < iend - LAST_LITERALS && src[ip + mlen] == src[candidate + mlen]) mlen++

                val litLen = ip - anchor
                val matchCode = mlen - MIN_MATCH

                out[op++] = (((litLen.coerceAtMost(15)) shl 4) or matchCode.coerceAtMost(15)).toByte()
                if (litLen >= 15) {
                    var rem = litLen - 15
                    while (rem >= 255) { out[op++] = 255.toByte(); rem -= 255 }
                    out[op++] = rem.toByte()
                }
                System.arraycopy(src, anchor, out, op, litLen)
                op += litLen

                val offset = ip - candidate
                out[op++] = (offset and 0xFF).toByte()
                out[op++] = ((offset ushr 8) and 0xFF).toByte()

                if (matchCode >= 15) {
                    var rem = matchCode - 15
                    while (rem >= 255) { out[op++] = 255.toByte(); rem -= 255 }
                    out[op++] = rem.toByte()
                }

                ip += mlen
                anchor = ip
            } else {
                ip++
            }
        }

        op = writeLastLiterals(src, anchor, iend - anchor, out, op)
        return out.copyOf(op)
    }

    private fun writeLastLiterals(src: ByteArray, offset: Int, length: Int, out: ByteArray, opIn: Int): Int {
        var op = opIn
        out[op++] = (length.coerceAtMost(15) shl 4).toByte()
        if (length >= 15) {
            var rem = length - 15
            while (rem >= 255) { out[op++] = 255.toByte(); rem -= 255 }
            out[op++] = rem.toByte()
        }
        System.arraycopy(src, offset, out, op, length)
        return op + length
    }

    private fun hash(v: Int): Int = ((v * -1640531535) ushr (32 - HASH_LOG)) and (HASH_SIZE - 1)

    private fun read32(src: ByteArray, p: Int): Int =
        (src[p].toInt() and 0xFF) or
            ((src[p + 1].toInt() and 0xFF) shl 8) or
            ((src[p + 2].toInt() and 0xFF) shl 16) or
            ((src[p + 3].toInt() and 0xFF) shl 24)
}
