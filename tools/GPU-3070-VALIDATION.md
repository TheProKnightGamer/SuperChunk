# SuperChunk GPU backend — validation guide (RTX 3070 reference box)

How to check that SuperChunk's OpenCL worldgen path is accurate and worth running on a machine:
the boot parity gate, the strict block-for-block check, the optional flip census, and a GPU-on vs
GPU-off benchmark. What the path does and what it has measured so far: `../GPU-AHEAD-PLAN.md`.
Reference numbers below are from an RTX 3070 with an i7-14700K.

---

## 0. Prerequisites

- An OpenCL 1.2+ GPU driver whose device advertises `cl_khr_fp64`. NVIDIA's standard driver
  does on the 3070 (fp64 runs at 1/64 of the fp32 rate there; the path is built around that).
- Java 21, NeoForge 1.21.1, and the SuperChunk jar. LWJGL's OpenCL bindings ship inside the jar;
  no native build is needed, and kernels compile at runtime through the driver.
- For the benchmark: a NeoForge server template directory with an accepted `eula.txt`, its
  `libraries/`, and exactly one Chunky jar in `mods/` (see `tools/run-worldgen-benchmark.py`).

The startup log names the device: look for the `[SuperChunk-GPU]` lines with `fp64=yes`.

---

## 1. Configuration

Everything is in the `gpu.*` section of `config/superchunk.properties` (the documented defaults
are in `dist/config/superchunk.properties`):

```properties
gpu.enabled=true
gpu.desiredFp=fp64        # default; devices without fp64 are not used
gpu.platformIndex=-1      # auto
gpu.deviceIndex=-1        # auto
```

With `gpu.desiredFp=fp64` a device without fp64 is refused and terrain stays on the bit-exact CPU
path. With the GPU on, compact block ids are on too (`-Dsuperchunk.gpu.compactIds`, default `on`);
their decision math runs in fp32 unless `gpu.decideFp32=false`.

The first start compiles every kernel (several minutes with a cold cache); binaries are cached on
disk and reused until the GPU driver changes (warm start ~11–13 s).

---

## 2. Boot parity gate (bit-exact, fp64)

Set `gpu.selftest.gpu_parity=true` and start once. `GpuVanillaParityTest` compiles representative
overworld density functions (gradient, noise arithmetic, shifted noise, the shift family, range
choice and clamp, weird-scaled sampler, splines, a deep overworld composite, and the biome climate
path) and compares GPU output with vanilla's `DensityFunction.compute()` on a fixed point grid.
Expected, in the `[SuperChunk-GPU]` log:

```
===== GPU-vs-VANILLA parity test (Stage 3) =====
...
===== GPU-vs-VANILLA parity test PASS (FP64 bit-exact) =====
```

Every case must be bit-identical. Any `FAIL` is an accuracy regression: report the device line
and the case lines. Set the flag back to `false` for benchmark runs. (Independently of this flag,
every density function is checked when it registers for the GPU and stays on the CPU if it does
not match.)

---

## 3. Strict block check (compact ids)

Runs the vanilla fill and ships its result, and compares the GPU's block ids and every side effect
against it:

```sh
python3 tools/run-worldgen-benchmark.py --server-template <template> --jar <superchunk.jar> \
  --output <new-dir> --radius 256 --seed -987654321 --workers 12 --heap 8G --gpu \
  --config gpu.decideFp32=false --jvm-arg=-Dsuperchunk.gpu.compactIds=verify
```

At shutdown, `[compact-consume-verify]` reports blocks compared and mismatches by class (block,
both heightmaps, section counters, Lithium flags, post-processing marks). Expected with
`gpu.decideFp32=false`: `MISMATCHES total=0` and `ZERO DIVERGENCE` (143,200,256 blocks on the
reference box, 2026-09-21). Repeat without the `--config` to see the default fp32 decisions: the
reference box shows one stone/air difference on that seed, which is the known fp32 envelope.

Optional, heavier: the flip census (`-Dsuperchunk.gpu.blockIdVerify=true`, `[blockid-census]`
lines) recomputes every block with the GPU's own density and a Java reference of the kernel, and
classifies any difference, including GPU-vs-reference corruption, which must be 0.

---

## 4. Benchmark — GPU on vs off

Use the same harness, fresh world per run, radius ≥ 1024 (smaller runs are dominated by warm-up):

```sh
python3 tools/run-worldgen-benchmark.py --server-template <template> --jar <superchunk.jar> \
  --output <new-dir-gpu> --radius 1024 --workers 12 --heap 8G --gpu
python3 tools/run-worldgen-benchmark.py --server-template <template> --jar <superchunk.jar> \
  --output <new-dir-cpu> --radius 1024 --workers 12 --heap 8G
```

Each writes `result.json` (`chunks_per_second`, `cpu_ms_per_chunk`, health checks). Warm the
template's GPU program cache first with one GPU run (radius 768 or more): with a cold cache a short
pregen can finish before the batcher is built, and the GPU run then measures little. On a shared
or busy machine, alternate the two configurations over several rounds and compare means.

Confirm the GPU carried the work: `[compact-consume]` at shutdown (chunks consumed from GPU ids)
and the `density-fill stats` lines (GPU share of density batches).

Reference, RTX 3070 + i7-14700K, radius 1024, 12 workers: GPU 903–959 chunks/s (2026-09-25),
CPU only 464–480 (2026-09-21).

---

## 5. Interpreting the result

- The GPU takes the noise-stage density and block decisions off the worker threads; surface
  rules, carvers, features and lighting stay on the CPU. The more a machine is bound by worker
  CPU, the larger the gain.
- At high worker counts the GPU becomes the scarce resource; that is why the biome climate offload
  is off by default (`gpu.offloadBiome`).
- If GPU on is slower, report both results, the worker count, the CPU and GPU models and the
  driver version.

---

## 6. Safety check (once per machine)

With `gpu.desiredFp=fp64`, point `gpu.platformIndex`/`gpu.deviceIndex` at a device without fp64
(for example an integrated GPU). The log must show the device refused and generation must stay on
the CPU. On a device with fp64 this check is a no-op.
