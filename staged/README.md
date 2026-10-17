# Staged (not yet compiled) — pending later port milestones

## gpu-noise-parity/
`FieldAccess` + `GpuNoiseParityTest` read vanilla noise **private** fields
(`ImprovedNoise.p`, `PerlinNoise.noiseLevels`/`amplitudes`/`lowestFreq*`, `NormalNoise.first`/`second`/`valueFactor`).

They need the access transformer to widen those fields. On **Forge 1.20.1** ATs are applied
at the **SRG** stage, so `accesstransformer.cfg` entries must use SRG names (`f_XXXXX_`), NOT the
mojmap names the NeoForge 1.21.1 AT uses. Restore path: either (a) convert the AT entries to SRG,
or (b) rewrite `FieldAccess` to use reflection (`setAccessible`, no AT needed). This is part of the
full AT mojmap→SRG conversion required for the worldgen-offload stage.
