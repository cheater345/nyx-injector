package dev.nyx.injector

import dev.nyx.injector.cli.Commands
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] in setOf("-h", "--help", "help")) {
        usage()
        return
    }
    val cmd = args[0]
    val a = Args(args.drop(1))

    if (a.flag("debug")) {
        // handled inside catch below
    }

    try {
        when (cmd) {
            "info" -> Commands.info(a.positional.requirePos(0, "bundle"))
            "list" -> Commands.listObjects(a.positional.requirePos(0, "bundle"), a.opt("node") ?: a.opt("path"))
            "unpack" -> Commands.unpack(a.positional.requirePos(0, "bundle"), File(a.require("out")))
            "repack" -> Commands.repack(
                inDir = File(a.require("in")),
                out = File(a.require("out")),
                compressName = a.opt("compress"),
                unityVersionOverride = a.opt("unity-version"),
                unityRevisionOverride = a.opt("unity-revision"),
                versionOverride = a.opt("version")?.toIntOrNull(),
            )
            "replace-node" -> Commands.replaceNode(
                bundle = a.positional.requirePos(0, "bundle"),
                selector = a.require("node"),
                payload = File(a.require("payload")),
                out = a.opt("out")?.let(::File),
                inPlace = a.flag("in-place"),
            )
            "replace-object" -> Commands.replaceObject(
                bundle = a.positional.requirePos(0, "bundle"),
                selector = a.require("node"),
                pathId = parseId(a.require("path-id")),
                payload = File(a.require("payload")),
                out = a.opt("out")?.let(::File),
                inPlace = a.flag("in-place"),
            )
            "verify" -> Commands.verify(a.positional.requirePos(0, "bundle"))
            "restore" -> Commands.restore(a.positional.requirePos(0, "bundle"))
            else -> {
                System.err.println("nyx-injector: unknown command '$cmd'")
                usage()
                exitProcess(2)
            }
        }
    } catch (t: Throwable) {
        System.err.println("nyx-injector: ${t.message ?: t::class.java.simpleName}")
        if (a.flag("debug")) t.printStackTrace()
        exitProcess(2)
    }
}

private fun parseId(raw: String): Long =
    if (raw.startsWith("0x", ignoreCase = true)) raw.substring(2).toLong(16) else raw.toLong()

private class Args(raw: List<String>) {
    private val named = LinkedHashMap<String, String>()
    val positional = ArrayList<String>()

    init {
        var i = 0
        while (i < raw.size) {
            val s = raw[i]
            if (s.startsWith("--")) {
                val eq = s.indexOf('=')
                if (eq > 0) {
                    named[s.substring(2, eq)] = s.substring(eq + 1)
                    i += 1
                } else {
                    val next = raw.getOrNull(i + 1)
                    if (next != null && !next.startsWith("--")) {
                        named[s.substring(2)] = next
                        i += 2
                    } else {
                        named[s.substring(2)] = "true"
                        i += 1
                    }
                }
            } else {
                positional.add(s)
                i += 1
            }
        }
    }

    fun require(key: String): String = named[key] ?: throw IllegalArgumentException("missing --$key")
    fun opt(key: String): String? = named[key]?.takeIf { it != "true" }
    fun flag(key: String): Boolean = named[key] == "true" || named[key] == "1"
}

private fun List<String>.requirePos(index: Int, what: String): File {
    val v = getOrNull(index) ?: throw IllegalArgumentException("missing <$what> argument")
    return File(v)
}

private fun usage() {
    println(
        """
        nyx-injector 0.1.0 — UnityFS unpack/repack + typed payload injection

        usage: nyx-injector <command> [args]

        read-only:
          info <bundle>                     archive header, blocks, nodes
          list <bundle> [--node IDX|PATH]   objects inside serialized nodes
          verify <bundle>                   re-check all offsets and objects

        round trip:
          unpack <bundle> --out DIR         dump nodes + manifest.tsv
          repack --in DIR --out FILE        rebuild a bundle from unpacked nodes
                 [--compress none|lzma|lz4|lz4hc] [--unity-version S]
                 [--unity-revision S] [--version N]

        injection:
          replace-node <bundle> --node IDX|PATH --payload FILE (--out FILE|--in-place)
          replace-object <bundle> --node IDX|PATH --path-id ID --payload FILE (--out FILE|--in-place)
          restore <bundle>                  put the .bak back (undo --in-place)

        global:
          --debug                           stack traces on failure
        """.trimIndent(),
    )
}
