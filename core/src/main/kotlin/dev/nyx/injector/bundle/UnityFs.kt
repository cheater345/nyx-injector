package dev.nyx.injector.bundle

import dev.nyx.injector.fs.BinaryReader
import dev.nyx.injector.fs.BinaryWriter
import dev.nyx.injector.fs.Compression
import dev.nyx.injector.fs.Compressor
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * UnityFS archive (the container Unity/MLBB ships asset bundles in).
 *
 * Layout (versions 7 and 8):
 *   "UnityFS\0" | version u32 | unityVersion cstr | unityRevision cstr
 *   totalSize i64 | compressedBlocksInfoSize u32 | uncompressedBlocksInfoSize u32 | flags u32
 *   [blocks info — compressed with the archive compression]
 *   [block data ...]            <- blocks info may instead live at the end (flag 0x80)
 *
 * Blocks info: hash[16] | blockCount u32 | blocks{ uncSize u32, cmpSize u32, flags u16 }
 *              nodeCount u32 | nodes{ offset i64, size i64, flags u32, path cstr }
 *
 * Everything is re-derived on write; node payloads are the single source of truth.
 */
class UnityFsArchive(
    val version: Int,
    val unityVersion: String,
    val unityRevision: String,
    val compression: Compression,
    val blocksInfoAtEnd: Boolean,
    val blocks: List<Block>,
    val nodes: List<Node>,
    val notes: List<String> = emptyList(),
) {
    data class Block(val uncompressedSize: Int, val compressedSize: Int, val compression: Compression)

    data class Node(val path: String, val flags: Int, val payload: ByteArray)

    fun withNodePayload(index: Int, payload: ByteArray): UnityFsArchive {
        require(index in nodes.indices) { "node index $index out of range" }
        val copy = nodes.mapIndexed { i, n ->
            if (i == index) Node(n.path, n.flags, payload) else Node(n.path, n.flags, n.payload)
        }
        return UnityFsArchive(version, unityVersion, unityRevision, compression, blocksInfoAtEnd, blocks, copy, notes)
    }

    fun toBytes(compression: Compression = this.compression, blockSize: Int = 128 * 1024): ByteArray {
        // 1. concatenate node payloads, recompute node offsets
        val content = ByteArrayOutputStream()
        val nodeEntries = nodes.map { n ->
            val offset = content.size().toLong()
            content.write(n.payload)
            NodeEntry(offset, n.payload.size.toLong(), if (n.flags != 0) n.flags else 4, n.path)
        }
        val contentBytes = content.toByteArray()

        // 2. chunk + compress blocks
        data class OutBlock(val uncompressedSize: Int, val compressed: ByteArray)

        val outBlocks = ArrayList<OutBlock>()
        if (contentBytes.isEmpty()) {
            outBlocks.add(OutBlock(0, ByteArray(0)))
        } else {
            var pos = 0
            while (pos < contentBytes.size) {
                val n = minOf(blockSize, contentBytes.size - pos)
                val chunk = contentBytes.copyOfRange(pos, pos + n)
                outBlocks.add(OutBlock(n, Compressor.compress(compression, chunk)))
                pos += n
            }
        }

        // 3. blocks info
        val bi = BinaryWriter()
        bi.bytes(ByteArray(16)) // hash placeholder — zeros, same as the other community repackers
        bi.i32(outBlocks.size)
        for (b in outBlocks) {
            bi.u32(b.uncompressedSize.toLong())
            bi.u32(b.compressed.size.toLong())
            bi.u16(compression.id)
        }
        bi.i32(nodeEntries.size)
        for (n in nodeEntries) {
            bi.u64(n.offset)
            bi.u64(n.size)
            bi.u32(n.flags.toLong())
            bi.stringNull(n.path)
        }
        val blocksInfoRaw = bi.toByteArray()
        val blocksInfoCompressed = Compressor.compress(compression, blocksInfoRaw)

        // 4. header + body (two-pass: totalSize must be known before writing)
        val headerSize = HEADER_FIXED +
            unityVersion.toByteArray(StandardCharsets.UTF_8).size + 1 +
            unityRevision.toByteArray(StandardCharsets.UTF_8).size + 1
        val dataSize = outBlocks.sumOf { it.compressed.size }.toLong()
        val totalSize = headerSize + blocksInfoCompressed.size + dataSize

        val w = BinaryWriter()
        w.stringNull(SIGNATURE)
        w.u32(version.toLong())
        w.stringNull(unityVersion)
        w.stringNull(unityRevision)
        w.i64(totalSize)
        w.u32(blocksInfoCompressed.size.toLong())
        w.u32(blocksInfoRaw.size.toLong())
        w.u32((compression.id or FLAG_COMBINED).toLong()) // written "not at end"
        w.bytes(blocksInfoCompressed)
        for (b in outBlocks) w.bytes(b.compressed)

        val out = w.toByteArray()
        check(out.size.toLong() == totalSize) { "internal error: produced ${out.size} bytes, expected $totalSize" }
        return out
    }

    private data class NodeEntry(val offset: Long, val size: Long, val flags: Int, val path: String)

    companion object {
        const val SIGNATURE = "UnityFS"
        private const val FLAG_COMBINED = 0x40
        private const val FLAG_BLOCKS_INFO_AT_END = 0x80

        /** sig(8) + version(4) + i64(8) + u32(4) + u32(4) + u32(4) — excluding the two cstrings. */
        private const val HEADER_FIXED = 32

        fun parse(bytes: ByteArray): UnityFsArchive {
            val r = BinaryReader(bytes)
            val sig = r.stringNull()
            require(sig == SIGNATURE) {
                val head = bytes.take(16).joinToString(" ") { "%02X".format(it) }
                "not a UnityFS archive (signature '$sig', head bytes: $head)"
            }
            val version = r.u32().toInt()
            require(version in 7..8) { "unsupported UnityFS version $version (supported: 7..8)" }
            val unityVersion = r.stringNull()
            val unityRevision = r.stringNull()
            val totalSize = r.i64()
            val compressedBlocksInfoSize = r.u32().toInt()
            val uncompressedBlocksInfoSize = r.u32().toInt()
            val flags = r.u32().toInt()
            val headerSize = r.position

            val archiveCompression = Compression.fromId(flags)
            val atEnd = (flags and FLAG_BLOCKS_INFO_AT_END) != 0
            require(flags and FLAG_COMBINED != 0) {
                "legacy archives with separate directory info are not supported"
            }

            val notes = ArrayList<String>()
            if (totalSize != bytes.size.toLong()) {
                notes.add("header totalSize ($totalSize) != actual file size (${bytes.size})")
            }

            val blocksInfoRaw = if (atEnd) {
                val start = bytes.size - compressedBlocksInfoSize
                require(start >= headerSize) { "blocks info size overruns the file" }
                bytes.copyOfRange(start, bytes.size)
            } else {
                require(headerSize + compressedBlocksInfoSize <= bytes.size) { "blocks info overruns the file" }
                bytes.copyOfRange(headerSize, headerSize + compressedBlocksInfoSize)
            }
            val blocksInfo = Compressor.decompress(archiveCompression, blocksInfoRaw, uncompressedBlocksInfoSize)

            val br = BinaryReader(blocksInfo)
            br.skip(16) // hash
            val blockCount = br.i32()
            require(blockCount in 0..1_000_000) { "implausible block count $blockCount" }
            val blocks = (0 until blockCount).map {
                val unc = br.u32().toInt()
                val cmp = br.u32().toInt()
                val bflags = br.u16()
                Block(unc, cmp, Compression.fromId(bflags))
            }
            val nodeCount = br.i32()
            require(nodeCount in 0..1_000_000) { "implausible node count $nodeCount" }
            data class RawNode(val offset: Long, val size: Long, val flags: Int, val path: String)

            val rawNodes = (0 until nodeCount).map {
                RawNode(br.i64(), br.i64(), br.u32().toInt(), br.stringNull())
            }

            // block data
            var cursor = if (atEnd) headerSize else headerSize + compressedBlocksInfoSize
            val content = ByteArrayOutputStream()
            for (b in blocks) {
                require(cursor + b.compressedSize <= bytes.size) { "block overruns the file" }
                val raw = bytes.copyOfRange(cursor, cursor + b.compressedSize)
                cursor += b.compressedSize
                content.write(Compressor.decompress(b.compression, raw, b.uncompressedSize))
            }
            if (!atEnd && cursor != bytes.size) {
                notes.add("trailing bytes after blocks: ${bytes.size - cursor}")
            }

            val contentBytes = content.toByteArray()
            val nodes = rawNodes.map { rn ->
                require(rn.offset >= 0 && rn.size >= 0 && rn.offset + rn.size <= contentBytes.size) {
                    "node out of bounds (offset=${rn.offset}, size=${rn.size}, content=${contentBytes.size})"
                }
                Node(rn.path, rn.flags, contentBytes.copyOfRange(rn.offset.toInt(), (rn.offset + rn.size).toInt()))
            }

            return UnityFsArchive(
                version = version,
                unityVersion = unityVersion,
                unityRevision = unityRevision,
                compression = archiveCompression,
                blocksInfoAtEnd = atEnd,
                blocks = blocks,
                nodes = nodes,
                notes = notes,
            )
        }
    }
}
