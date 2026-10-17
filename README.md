# nyx-injector

UnityFS bundle unpack / repack / payload injection — the "override" half of the
lab workflow. Pairs with [asset-cartographer](https://github.com/cheater345/asset-cartographer)
(recon: what's inside the bundles) and a throwaway Unity project (render/behavior
tests).

It reads and writes the real thing:

- **UnityFS v7 / v8** archives — header, blocks info (inline or at end), node table
- **LZ4** block codec written from scratch (compress + decompress), **LZMA** (xz),
  **none** — reads all three, writes any of them
- **Serialized files** v14–v22 (Unity 2017–2020 era), both endians — enough of the
  header/metadata to reach the object table, then the object table itself
- **Object-level patching** — replace one object's payload by path id; the object
  table and header sizes get fixed up, everything else is byte-identical

Everything is read-only until you explicitly ask it to write. `--in-place` always
drops a `.bak` next to the original; `restore` puts it back.

## modules

| module | role |
|---|---|
| `core` | the engine — UnityFS, serialized files, LZ4/LZMA codecs (pure JVM, no Android deps) |
| `cli`  | `nyx-injector` command line |
| `app`  | the APK — pick a bundle via SAF, browse nodes/objects, inject a payload, save the patched copy |

## build

```bash
gradle :core:build :cli:build   # compile + tests
gradle :cli:installDist         # -> cli/build/install/nyx-injector/bin/nyx-injector
gradle :app:assembleDebug       # -> app/build/outputs/apk/debug/app-debug.apk
```

No wrapper jar is checked in; `gradle wrapper --gradle-version 8.10.2` if you want
`./gradlew`. CI builds, tests, and uploads both the CLI dist and the APK
(`nyx-injector-apk` artifact).

## the apk

On device: **Open bundle** → tap a node → **Replace node**, or expand a
serialized node and hit **Replace** on a specific object → pick the payload
file → **Save patched bundle**. The patched build is written wherever you
choose; the source bundle is never modified. Every open and inject lands in
the **Journal** tab, stored under the app's private files.


## usage

```bash
# look before you touch
nyx-injector info   client_bundle
nyx-injector list   client_bundle
nyx-injector verify client_bundle

# round trip — sanity check, should reproduce a working bundle
nyx-injector unpack client_bundle --out work/
nyx-injector repack --in work/ --out rebuilt_bundle

# swap a whole node (e.g. a .resS / .resource blob)
nyx-injector replace-node client_bundle --node 1 --payload my_blob.bin --out patched

# swap one object's payload inside a serialized node (the interesting one)
nyx-injector list client_bundle --node 0
nyx-injector replace-object client_bundle --node 0 --path-id 0x2af --payload tex.bin --out patched

# in-place + undo — the experiment loop
nyx-injector replace-object client_bundle --node 0 --path-id 0x2af --payload tex.bin --in-place
nyx-injector restore client_bundle
```

`--compress none|lzma|lz4|lz4hc` on `repack` when you want to force an encoding
(None is handy for diffing, LZ4 for everyday).

## limits (v0.1)

- object table tail (externals, user info, assetbundle name) is kept verbatim —
  parsed only up to the object table; that's all patching needs
- no type-tree walking yet — that's what a future `names`/`replace-texture`
  upgrade uses to locate `Texture2D.image data` and friends; for now you supply
  the raw payload bytes yourself
- plugins/encrypted bundles from live games are not handled; if `info` says
  "not a UnityFS archive" and prints head bytes, that's usually why
- prepended/garbled headers, per-node compression mixes beyond what Unity ships:
  repack normalizes a bundle to one compression type

## the workflow this fits into

1. **cartographer** (phone) — find and identify bundles, journal everything
2. **nyx-injector** (desk) — unpack, swap a payload, repack
3. **lab project** (Unity, throwaway) — the patched bundle is loaded there, never
   against a live account or production client
4. **revert** — `restore`, or reinstall from the journaled originals

Test patched content in the lab first; the phone only ever sees builds you've
already validated.
