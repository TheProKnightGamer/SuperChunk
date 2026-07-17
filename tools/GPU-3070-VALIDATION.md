# SuperChunk GPU backend — RTX 3070 validation guide

This is the **accuracy + performance acceptance procedure** for SuperChunk's
OpenCL density-function offload, to be run on the **RTX 3070 PC** (the dev laptop's
Intel Iris Xe has **no fp64**, so it can only validate the fp32 *structural* path —
not vanilla accuracy or real perf).

The GPU offload accelerates the **parallel chunk-noise density-function** fills
that C2ME's density-function compiler (DFC) produces. It is gated so that it
**never** runs in an accuracy-unsafe configuration: with `requireFp64=true` (the
default) the GPU only engages on hardware that advertises `cl_khr_fp64`.

---

## 0. Prerequisites

- **GPU driver with OpenCL 1.2+ and `cl_khr_fp64`.** NVIDIA's standard
  Game Ready / Studio driver ships an OpenCL ICD that supports doubles on the
  3070. Verify with any OpenCL info tool (e.g. `clinfo`, GPU-Z's "OpenCL" line,
  or the SuperChunk boot log, see step 2):
  - `Device: NVIDIA GeForce RTX 3070 ... fp64=yes`
  - extensions list contains `cl_khr_fp64`.
- **Java 21** (the mod's runtime). No native/CMake build is needed — OpenCL
  kernels compile at runtime via the driver.
- A working SuperChunk install (the `superchunk-*.jar` from `dist/`, plus its
  embedded libs — LWJGL OpenCL is bundled in the jar via jarJar).

> If `fp64=no` appears for the 3070, you are on a broken/old ICD — update the
> NVIDIA driver. Do **not** proceed with `requireFp64=false` for an accuracy run;
> fp32 will not be bit-exact.

---

## 1. Configure for the accuracy run

Edit `config/superchunk-gpu.properties`:

```properties
enabled=true
requireFp64=true
selftest.gpu_parity=true
platformIndex=-1
deviceIndex=-1
```

Also ensure the density-function compiler is ON (it is the live route the GPU
rides on) — `config/c2me.toml`:

```toml
useDensityFunctionCompiler = true
```

(`requireFp64=true` + an fp64 GPU is the **only** accuracy-valid combination. If
the chosen device lacks fp64 the backend logs a warning and the GPU does **not**
engage — terrain falls back to the bit-exact CPU path.)

---

## 2. Run the GPU parity self-test (bit-exact gate)

Boot the server (or a single-player world) once. At startup the
`selftest.gpu_parity=true` flag runs **`GpuVanillaParityTest`**, which compiles a
set of representative overworld density functions (y-gradient, noise+arith,
shifted noise, the shift family, range-choice+clamp, weird-scaled, spline, and a
deep overworld composite) to OpenCL and compares the GPU output against the
**vanilla** `DensityFunction.compute()` ground truth over a deterministic ~3072-
point grid.

In the log (logger `SuperChunk-GPU`), look for:

```
===== GPU-vs-VANILLA parity test (Stage 3) =====
Device: NVIDIA GeForce RTX 3070 [...] | fp64=yes
Precision mode: FP64 (double) — expect BIT-EXACT vs vanilla
[y_clamped_gradient] (GPU vs vanilla) n=3072 EXACT=3072 ... -> PASS (bit-identical)
[noise+arith]        ... -> PASS (bit-identical)
...
[overworld_composite] ... -> PASS (bit-identical)
===== GPU-vs-VANILLA parity test PASS (FP64 bit-exact) =====
```

**Expected result: every case `PASS (bit-identical)`**, i.e. `EXACT == n`,
`DIFF == 0`. A handful of `near (<= 1e-12)` values (instead of EXACT) on one or
two cases is acceptable — that is the GPU contracting a multiply-add (FMA) and
reordering one ULP; it still reports `PASS (within FMA epsilon)`. Any `FAIL` /
`DIFF > 0` is a real accuracy regression — **report it**.

> What to report from this step:
> - The `Device:` line (confirms fp64=yes).
> - The final `===== ... PASS/FAIL =====` line.
> - For each case: `EXACT`/`near`/`DIFF` counts (copy the 8 case lines).

Turn `selftest.gpu_parity=false` again before the perf run (you don't want the
boot self-test in the timed run).

---

## 3. Performance bench — GPU on vs off

Use the included bench harness, which pregens a fixed-seed region with Chunky and
reports chunks/sec. **Measure at radius >= 512** (smaller radii under-report due
to DFC JIT warmup; the warm/amortized ceiling shows up past ~512).

From the repo root, with the server's `run/` configured as above:

```powershell
# GPU ON  (enabled=true, requireFp64=true, DFC on)
pwsh -File bench\run-bench.ps1 -Radius 512

# GPU OFF (set enabled=false in config/superchunk-gpu.properties, DFC still on)
pwsh -File bench\run-bench.ps1 -Radius 512
```

Each run prints:

```
CHUNKS=<n> SECONDS=<s> CHUNKS_PER_SEC=<r> PARALLELISM=<n>
```

Run each config **twice** (discard the first, JIT/cache warmup) and report the
second. Keep everything else identical between the two runs (same machine state,
same seed 8675309, same radius).

To confirm the GPU actually carried the bulk of the density fills during the ON
run, check the log for the periodic and shutdown summary lines:

```
density-fill stats (running): GPU batches=… (NN.N%), CPU batches=…; GPU values=… (NN.N%) …
density-fill stats (server stopping): GPU batches=… …
```

The CPU batches that remain are mostly `CacheLikeNode` router-cache markers (a
known, expected non-GPU-compilable node family) — see "What's still CPU" below.

> What to report from this step:
> - `CHUNKS_PER_SEC` for GPU **ON** and GPU **OFF** (radius 512, 2nd run each).
> - The `PARALLELISM` value (C2ME global executor parallelism).
> - The final `density-fill stats (server stopping)` line from the ON run
>   (GPU % of batches and of values).
> - CPU model + core count, GPU driver version.

---

## 4. Interpreting the perf result

- The 3070 has fp64 but at a **rate-limited** ratio versus fp32 (consumer Ampere
  doubles run ~1/32 of fp32 throughput). Density-function math is double-precision,
  so the uplift depends on **how CPU-bound** worldgen is on your box: the GPU
  offloads the parallel noise/DF arithmetic off the c2me-worker threads, which
  helps most when those workers are the bottleneck (many cores saturated). If your
  CPU already keeps the serial server-thread finalization fed, the win is smaller.
- A correct outcome is: **GPU ON >= GPU OFF chunks/sec, and bit-exact terrain**
  (proven in step 2). Even a neutral perf result is a *success for accuracy*: it
  proves the GPU path is a safe, vanilla-accurate, toggleable option.
- If GPU ON is **slower**, that tells us this machine is serial-bound, not
  worker-bound — report the numbers and we tune (batch sizing, fewer host
  round-trips, or restrict GPU to the heaviest DFs).

---

## 5. Safety check (do this once)

Confirm the guard that protects non-fp64 machines:

1. Temporarily set `requireFp64=true` and (if you have an fp32-only device, e.g.
   an iGPU) `platformIndex/deviceIndex` to point at it.
2. Boot. The log must show the device selected but a warning that fp64 is absent,
   and the GPU must **not** route gen (DFs stay CPU; terrain is bit-exact).

On the 3070 (fp64 present) this guard is a no-op — the GPU engages normally.

---

## What's still CPU (known, expected)

- **`CacheLikeNode`** (DFC cache/interpolation markers) and `DelegateNode` are not
  GPU-compiled — they hold per-cell cache state. These are the bulk of the
  remaining CPU density-fill batches and are *correct* to leave on CPU.
- **Biome/climate (MultiNoise) sampler** density functions: covered by the same
  compiler path where they reduce to supported nodes; some climate DFs fall back.
- The offload targets the **compilable overworld density functions** (~11 of the
  router/sampler entries on this build). Everything not GPU-compiled falls back
  cleanly to the bit-exact CPU bytecode path per density function.

---

## TL;DR for the 3070 owner

1. Update NVIDIA driver; confirm OpenCL `cl_khr_fp64`.
2. `enabled=true`, `requireFp64=true`, `selftest.gpu_parity=true`, DFC on.
3. Boot once -> copy the **GPU-vs-VANILLA parity** result (expect all
   `PASS (bit-identical)`).
4. `selftest.gpu_parity=false`; run `bench\run-bench.ps1 -Radius 512` with
   `enabled=true`, then again with `enabled=false`. Report both
   `CHUNKS_PER_SEC`, the parallelism, and the `density-fill stats` GPU%.
5. Report CPU/GPU/driver details so we can interpret the uplift.
