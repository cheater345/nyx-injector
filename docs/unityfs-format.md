# UnityFS format notes

What `nyx-injector` parses and writes, per the community's collective
reverse-engineering (AssetStudio, UnityPy, UABEA and friends). Values are
big-endian unless noted.

## header (v7+)

```
"UnityFS\0"
u32      version            # 6, 7, 8
cstring  unityVersion       # e.g. "2019.4.31f1"
cstring  unityRevision
i64      totalSize          # v7+ ; ignored on read, recomputed on write
u32      compressedBlocksInfoSize
u32      uncompressedBlocksInfoSize
u32      flags
```

`flags & 0x3F` — archive compression: `0` none, `1` LZMA, `2` LZ4, `3` LZ4HC.
`flags & 0x40` — blocks + directory info combined (always set in v7+).
`flags & 0x80` — blocks info lives at the **end** of the file (Unity writes this;
seek to `end - compressedBlocksInfoSize`). Otherwise it sits right after the
header.

v6 archives (2015 era) have a different layout and are rejected.

## blocks info (after decompression)

```
u8[16]   hash               # not verified by anything we know; repack writes zeros
u32      blockCount
repeat   uncSize u32, cmpSize u32, flags u16   # flags low 6 bits = per-block compression
u32      nodeCount
repeat   offset i64, size i64, flags u32, path cstring
```

Blocks decompress to one contiguous stream; each node's `offset`/`size` carve
a slice out of it. The node with an empty path is the serialized file (CAB).

When writing, block data is chunked at 128 KiB and each block is compressed
independently — same as Unity does.

## serialized file

```
u32 metadataSize       u32 fileSize      u32 version     u32 dataOffset
u8  endianness         (pad to 4)
# v22+ ("large files"): the three sizes are repeated as u32/u64/u64, plus an unknown u64
cstring-align unityVersion
i32 targetPlatform
u8  enableTypeTree     (v13+)
i32 typeCount
repeat: classId i32, isStripped u8 (v16+), scriptTypeIndex i16 (v17+),
        scriptHash[16] if MonoBehaviour, type tree if enabled
i32 objectCount
repeat: pad4, pathId i64, byteStart u32 (i64 on v22+), byteSize u32, typeId i32
```

`byteStart` is absolute within the serialized file. Object payloads are laid
out in that data region; readers always go through the table, so offsets are
free to change when a payload is replaced — which is exactly what the patcher
does:

1. copy metadata verbatim, patch each object's `byteStart`/`byteSize`
2. rebuild the data region (original order, 4-byte alignment)
3. fix `fileSize` in the header

No re-serialization, no type tree surgery, minimal blast radius.

## endianness

Serialized files carry an endianness marker but honesty varies; the reader
sniffs both interpretations and keeps the one whose header fields (version
range, metadata size, object bounds) are self-consistent.

## LZ4

Unity's "LZ4" blocks are raw LZ4 block format — no frame header, no checksums.
`nyx-injector` implements the block codec directly (see `Lz4Block.kt`): greedy
hash-table matcher on the way in, exact overlap semantics on the way out.

## LZMA

Unity's LZMA blocks are the classic `.lzma` alone format: 5 bytes of properties,
8 bytes of uncompressed size (or `0xFFFF...` + end marker), then the stream.
Handled by XZ for Java.
