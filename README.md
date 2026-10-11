# deusex-fex-firefix

A patch for **Deus Ex (2000, GOTY 1112fm)** that makes its `Fire.dll` work under the
**FEX x86 emulator** on ARM devices — e.g. Android with GameHub or Winlator, using FEX as CPU translator.

Without it, the game floods its log with `Anomalous singularity in URender::DrawWorld`, water surfaces and
electricity effects (the riot prod, menu lasers) are invisible, and the game crashes — reliably when saving.

## Symptoms this fixes

- `DeusEx.log` full of entries like:
  ```
  Critical: UFireTexture::ConstantTimeTick
  Critical: (FireTexture Effects.Electricity.Nano_SFX)
  Log: Anomalous singularity in URender::DrawWorld
  ```
  or the same with `UWetTexture::ConstantTimeTick` / `Effects.water.drtywater_a`
- water without a surface, invisible riot prod, missing fire/electricity effects
- crash when saving (`UObject::SavePackage`), when entering busy scenes, or a GPF in `Fire.dll` when drawing the prod
- stuttering or a frozen game during the error flood

## Requirements

- Deus Ex: Game of the Year Edition, version 1112fm (GOG or Steam). The stock `Fire.dll` has
  MD5 `694b91490525a967270d8b04fa53217e`.
- Java 11 or newer on any PC (to run the patcher once).
- Works with or without the [DXController](https://github.com/dsgls/DXController) gamepad mod. You need a launcher that
  fixes the game's timing (Kentie's *Deus Exe*, which DXController ships); with the original launcher the game runs
  far too slowly under FEX anyway.

## How to use

1. Copy `Deus Ex/System/Fire.dll` from your game to a PC.
2. Run the patcher (only `patcher/FirePatch.java` is needed):
   ```
   java FirePatch.java path/to/Fire.dll
   ```
   It checks the input, then writes `Fire.dll.fixed` next to it. The original file is not modified.
3. Back up the original and copy `Fire.dll.fixed` to `Deus Ex/System/Fire.dll`.
4. Start the game. `DeusEx.log` should no longer contain `Anomalous singularity`.

The patcher only accepts the stock file (or a copy where only the `.data` section was made executable) and verifies
the result against a known checksum (`f002c84059aca023f537016b26a4a47c`) before writing it.

## Tested setup

- REDMAGIC Astra 2 (Snapdragon), Android 16, GameHub 6.3.1
- proton11.0-arm64x, FEX `Fex_20260913` / `Fex_20260921`, translation profile *Custom*:
  TSO on, X87ReducedPrecision on, Multiblock off, MaxInst 5000, SMCChecks **mtrack**
- DXVK 2.7.1, Kentie's D3D10 renderer, DXController mod
- Liberty Island: water, riot prod, scripted scenes, saving — 0 errors

Not tested yet: other levels/effects (fire, ice, waves), very long sessions, Kentie's launcher without DXController,
Box64. Reports welcome.

## What is going on

`Fire.dll` renders the procedural fire, water and ice textures with hand-written assembly from 2000. That code lives in
the DLL's data section and **rewrites its own instructions** before running them (row offsets, palette addresses; two
water loops even raise their own loop limit while running). Real CPUs handle this. FEX, with its default
self-modifying-code detection (`mtrack`), misses some of those rewrites and keeps executing stale code — which reads
past the texture buffers, corrupts data and eventually crashes. Switching FEX to `SMCChecks=full` hides the problem but
breaks Wine's Vulkan/DXGI path on the tested setup.

The patch leaves the algorithms untouched and changes *where* the code runs:

1. The whole assembly area is mirrored into a new read-only code section and executed from there. The self-patching
   writes still go to the original bytes, which are never executed any more.
2. Each inner loop is called through a small dispatcher that keeps an immutable copy per distinct patched state
   (looked up by hash), so no executed code is ever modified.
3. The two self-raising water loops read their limit from the original bytes instead of an immediate.
4. Relocations are rebuilt so the mirror works when the DLL is rebased.

Details, addresses and the full investigation: [docs/TECHNICAL.md](docs/TECHNICAL.md).

## Repository layout

| Path | Contents |
|---|---|
| `patcher/FirePatch.java` | the patcher (self-contained, includes the assembled dispatcher) |
| `src/kcache.asm` | dispatcher and stubs, NASM source (`nasm -f bin -o kcache.bin src/kcache.asm`) |
| `tools/` | analysis helpers used during the investigation (PE imports/exports, kernel analysis) |
| `docs/TECHNICAL.md` | root cause, addresses, test matrix |

No game files are included. `Fire.dll` is © Ion Storm / Epic Games; this project only distributes its own code.

## License

GPL-3.0-or-later, see [LICENSE](LICENSE).
