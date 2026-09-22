# Publication review — 2026-09-22

The review covers all pending source changes after `3199d19`, including the earlier
compatibility/storage work and both optimization passes. Independent reviewers
examined CPU world generation and serialization, scheduler/cache/storage behavior,
compatibility hooks, build/test wiring, documentation, and publication privacy. The
GPU consumer changes also received a separate review and source-extracted parity
check.

## Findings addressed

- A default 200 ms deadline for non-creating chunk requests could make collision
  checks treat a healthy but slow-loading chunk as empty. The default now preserves
  vanilla's unlimited wait. The broken-holder future completion fix is retained,
  with regression coverage for failure delivery, callbacks outside the holder
  monitor, unload/reload, and actual scheduler upgrade failure. The optional
  diagnostic timeout uses saturating duration conversion.
- Palette packing now rejects strategies whose sizes do not fit the bounded
  reusable buffers, falling back to the original implementation.
- Skein compatibility now requires exclusive ownership of the exact dimension,
  an active dimension barrier, and a non-deferring worker. The same ownership
  reaches the chunk executor and checked world RNG; the RNG stream is retained.
  A packaged command-block test exposed C2ME instrumentation skipping
  `managedBlock` on a dimension worker and deadlocking at the following future
  join. The wrapper now preserves executor pumping when declining instrumentation
  and recognizes the legitimate dimension owner. Bundled Lithium's chunk-status
  callbacks and loaded block-entity lookup also recognize that owner; the latter
  previously returned null on workers, bypassing hopper/comparator notifications.
- Skein's parallel entity, random-tick, block-entity, scheduled-tick, and saving
  phases are disabled in its live configuration because the bundled optimizations
  require one thread per level. Pins apply at startup, after every refresh, and
  before level ticking. Reloads and registry rebuilds requested during a dimension
  barrier are deferred to the next server tick. The configuration file and the
  dimension-threading setting are unchanged. Both known package namespaces are
  supported, and unknown or ambiguous APIs fail closed.
- Whole-server saves require the server thread outside a dimension barrier.
  Opportunistic autosaves also wait until dimension workers have finished.
- A global entity selector in a command block exposed a cross-dimension transfer
  while another worker still owned the source entity. Entire command-block ticks
  and command-minecart activations now replay on the server thread after the
  parallel dimension barrier, within the same server tick. Selection, conditional
  chains, success counts, repeating-block scheduling, and cart cooldowns stay
  together. This changes their timing within the tick, without allowing a partial
  foreign-world mutation through the async guards.
- Six PowerShell launchers now use `JAVA_HOME` or Java on `PATH`, with explicit
  overrides supported. The machine-specific JDK path was removed from the merge
  notes, the compact-consumer coverage description was corrected, and obsolete
  issue-tracker placeholder comments were removed.

## Privacy and repository hygiene

The audit examined publishable tracked/untracked files, historical blobs, commit
messages, and author/committer metadata. No private home paths, personal contact
details, token-shaped credentials, private keys, or credential-bearing URLs were
found. Historical commit addresses belong to GitHub's noreply/public commit
service identities. Required upstream copyright and NOTICE attributions, public
project identities, and functional compatibility package names are retained.

Build outputs, server worlds, logs, local configurations, benchmark invocation
files, and machine-specific profiling artifacts remain excluded by Git ignore
rules. The publication commit uses the account's GitHub noreply identity.

## Verification

```sh
./gradlew build --offline --console=plain
python3 tools/verify-compact-consume.py
```

The build passes all eight standalone regression programs, including 97 Skein
ownership/configuration checks and the expanded broken-holder scheduler cases.
The source-extracted compact-consumer checks pass 521,397 scanner/histogram/section
cases and 120 full-fill cases, including nested calls and exception cleanup.

The final jar has SHA-256
`e6622249239ac2e9eda64f1ed6e74e8635a8cbc105d3c60adc9c85e9966d6bb2`.
Its current-package Skein 1.0.0 integration run passes fresh generation of 1,089
requested chunks, save/restart, tick-function reload, three command-block reloads,
two local spreadplayers commands, one cross-dimension spreadplayers command with
a conditional chain, a powered command minecart, and item transfers through
hopper/chest pairs in all three dimensions. Both phases exit zero, preserve
dimension-only status, and report no captured errors or quarantined dimensions.
The Skein jar used for this run has SHA-256
`b82b027a32edbc864521585307e401d7f62b5b16ee0121b8424c71414b809f2f`.
The same final SuperChunk jar also passes 81-chunk boot/generation/save checks with
the legacy Skein package and without Skein. The latter verifies 1,537,115 biome
cache hits and 709,434 surface-gradient samples with zero mismatches.

```sh
python3 tools/verify-skein-server.py \
  --server-template /path/to/neoforge-test-server \
  --jar build/libs/superchunk-0.3.0.jar \
  --skein-jar /path/to/skein-1.0.0.jar \
  --output run/skein/new-verification-directory
```

This test uses Java 21 and NeoForge 21.1.248, three Skein workers, four C2ME workers,
a 4 GiB heap, and fresh seed 8675309. Logs and JSON results for this review are
under `run/publication-review-20260922/` (ignored by Git). The final command test
validates safe server-thread replay; the preceding diagnostic runs exercised
worker-side chunk waits and exposed the guards fixed above. No connected-player
or arbitrary third-party modpack coverage is claimed.

The pre-command-deferral candidate jar has SHA-256
`49c3212c4a6e7fad5e9478b2ebe134ed65eb2f1bb89b3d8f7c38f0b4e8393b24`.
The legacy Skein 1.0.0 package passed a 289-chunk fresh-world run with forced chunks,
pigs and hopper/chest pairs in all three dimensions, a configuration reload,
dimension-only status, save-all and clean shutdown. Its jar SHA-256 is
`080bca518c350550c2dfbce5191c1846253316273ae7ffc9e6d8ffd490896c27`.

A preceding 289-chunk run without Skein booted and saved cleanly, with 3,209,683 biome
cache hits and 1,495,526 surface-gradient draws checked with zero mismatches.
OpenCL context creation returned error -6, so generation used CPU fallback.
**This was not a successful GPU parity run:** zero blocks reached the GPU verifier.
The benchmark harness now requires successful context/queue initialization when
`--gpu` is requested, rather than accepting device selection alone. The earlier
successful GPU parity and allocation evidence is in the optimization reports;
no further GPU timing runs were attempted under the competing server load.

PowerShell syntax received manual review; PowerShell is unavailable in the test
environment. Existing optimization timing limitations and the rare default-fp32
parity difference remain documented in the two optimization reports. This review
does not claim compatibility with every third-party mixin or modpack.
