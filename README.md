# SuperChunk for Forge 1.20.1

This branch holds the Forge 1.20.1 port of SuperChunk. The NeoForge 1.21.1 build on `main` is
the original and the source of truth; this tree follows it and is at parity with 0.5.0.
`PORT-NOTES.md` records each catch-up and every place where 1.20.1 deliberately differs.

## Install

Forge 47.x for Minecraft 1.20.1, Java 17. Put `superchunk-<version>-shell.jar` in `mods/`. It
carries the mod and LWJGL's OpenCL binding; nothing else is needed. GPU offload is optional and
off unless `gpu.enabled=true` is set in `config/superchunk.properties`.

## Build

```
./gradlew build shellJar
```

The installable jar is `build/libs/superchunk-<version>-shell.jar`. Check that its nested
`META-INF/jarjar/superchunk-core.jar` contains `superchunk.refmap.json`: production servers
run SRG names, and string-target mixins fail without it.

## License

LGPL-3.0-only, like the bundled C2ME, ScalableLux, Noisium and Lithium sources. See `LICENSE`
and `NOTICE`.
