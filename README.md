# deusex-fex-firefix

Fixes for running **Deus Ex (2000, GOTY 1112fm)** on ARM devices through Wine and the **FEX x86 emulator** — e.g.
Android with GameHub or Winlator, using FEX as CPU translator:

1. **[Fire.dll patch](#1-firedll-patch)**: water, electricity and fire effects work again, no more
   `Anomalous singularity` floods and crashes when saving.
2. **[Loading-time fix](#2-loading-time-fix)**: a patched `DeusEx.exe` for the
   [DXController](https://github.com/dsgls/DXController) mod. Savegames load in seconds instead of minutes.

The two are independent of each other; on Android you want both.

---

## 1. Fire.dll patch

Without it, the game floods its log with `Anomalous singularity in URender::DrawWorld`, water surfaces and
electricity effects (the riot prod, menu lasers) are invisible, and the game crashes — reliably when saving.

### Symptoms this fixes

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

### Requirements

- Deus Ex: Game of the Year Edition, version 1112fm (GOG or Steam). The stock `Fire.dll` has
  MD5 `694b91490525a967270d8b04fa53217e`.
- Java 11 or newer on any PC (to run the patcher once).
- Works with or without the [DXController](https://github.com/dsgls/DXController) gamepad mod. You need a launcher that
  fixes the game's timing (Kentie's *Deus Exe*, which DXController ships); with the original launcher the game runs
  far too slowly under FEX anyway.

### How to use

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

### Tested setup

- REDMAGIC Astra 2 (Snapdragon), Android 16, GameHub 6.3.1
- proton11.0-arm64x, FEX `Fex_20260913` / `Fex_20260921`, translation profile *Custom*:
  TSO on, X87ReducedPrecision on, Multiblock off, MaxInst 5000, SMCChecks **mtrack**
- DXVK 2.7.1, Kentie's D3D10 renderer, DXController mod
- Liberty Island: water, riot prod, scripted scenes, saving — 0 errors

Not tested yet: other levels/effects (fire, ice, waves), very long sessions, Kentie's launcher without DXController,
Box64. Reports welcome.

### What is going on

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

---

## 2. Loading-time fix

With the game on Android's shared storage (`/sdcard`), Deus Ex under Wine takes minutes to load — even though the same
files load in about a second on a PC. The time does not go into reading the game files but into **looking for files
that don't exist**, thousands of times per load (details [below](#what-is-going-on-1)).

### Symptoms

- the main menu takes most of a minute to appear, a savegame several minutes, a level change about two minutes
- during the loading screen the game is mostly idle: little CPU, no GPU load
- the same installation loads in about a second on a PC

### Results

Measured on the tested setup below:

| | before | with the fix |
|---|---|---|
| loading the main menu (D3D10 renderer) | 45.6 s | **3.1 s** |
| loading a savegame (Liberty Island, DECoR renderer) | 4–5 min | **1.1 s** |

The savegame time is from the release build; the main-menu time from an earlier build of the patch that didn't yet
answer the engine's package-DLL probes from the cache (about one second more). A level change from Liberty Island to
UNATCO HQ took 1.9 s with that earlier build (0.4 s to save the old map, 1.45 s to load the new one). With DECoR,
loading the main menu takes 9–13 s, because DECoR compiles its shaders at startup.

### Requirements

- [DXController](https://github.com/dsgls/DXController) **1.6.1**. The fix replaces its `DeusEx.exe`: it is
  DXController's launcher with one added patch, so all of DXController's features and settings stay as they are.
- Start `DeusEx.exe` with **`-localdata`** (in GameHub or Winlator: the launch arguments of the game; the tested setup
  used `-localdata -skipdialog`). Without it, DXController keeps `DeusEx.ini`, the log and the savegames in
  `Documents\Deus Ex` inside the Wine prefix and checks that folder for every file first. Those checks are not cached,
  so the gain will be smaller (not measured). If you already played without `-localdata`, copy your savegames from
  `Documents\Deus Ex\Save` into the game's `Save` folder.
- Any renderer (tested: Kentie's D3D10 renderer and DECoR).

### How to use

1. Download `DeusEx.exe` from the [latest release](https://github.com/olivergrosche/deusex-fex-firefix/releases/latest)
   (the checksum is on the release page).
2. In `Deus Ex/System/`, rename DXController's `DeusEx.exe` to `DeusEx.exe.bak` and put the downloaded one in its place.
3. Start the game. `DeusEx.log` (in `Deus Ex/System/`) now starts with
   ```
   Path cache: on for 'E:\...\DEUS EX GOTY\' (not cached: '...\SAVE\', '...\CACHE\').
   File hooks: ... import slots patched; trace off, path cache on.
   ```
   and after every level change it reports what the cache answered, e.g.
   ```
   Path cache: 15068 lookups, 14916 answered (14838 not found); 5 directory listings (19 ms), ...
   ```

On Windows the fix changes nothing you would notice; loads were fast there already.

### Settings

All optional, in `DeusEx.ini`, section `[DeusExe]`:

| Key | Default | Effect |
|---|---|---|
| `PathCache` | `True` | The fix itself. `False` turns the cache and the file hooks off, so lookups go to the file system as before. |
| `FastFileReader` | `True` | Reads packages through memory mapping instead of the engine's 1 KB reads. `False` uses the engine's reader. With `PathCache=False` as well, file access is as in DXController 1.6.1. |
| `FileTrace` | `False` | After each level change, logs which file calls cost how much time, for which files, and who made them. |
| `LoadProfiler` | `False` | Samples the main thread during map loads and logs where the time went. |

### What is going on

Wine has to give Windows programs case-insensitive file names on Linux file systems, which are case-sensitive. When a
file isn't found under its exact name, Wine reads the whole directory and compares every entry. On Android's shared
storage every directory read is a round trip through the FUSE daemon, so each lookup of a *missing* file costs about
2–15 ms, depending on the size of the directory.

Deus Ex makes tens of thousands of those lookups:

- For every translatable property of every object it loads — names, descriptions; a weapon alone has dozens — the
  engine looks for a localization file named after the object's package, e.g. `01_NYC_UNATCOIsland.int` for the
  actors of that map. Most packages have none, and the engine doesn't remember that, so it asks again for every single
  property.
- For every package it binds, the engine tries to load a native DLL of the same name, three times; most packages have
  none. It also searches each package in every search path (`System`, `Maps`, `Textures`, `Sounds`, `Music`, then once
  more through `CdPath`).
- Kentie's D3D10 renderer looks for a replacement texture (`..\textures\<package>\<group>\<texture>.dds`) for every
  texture it uploads, and for some textures also for `.detail.dds` and `.dds.flags` files.

Loading the Liberty Island savegame made 15,127 lookups, 14,897 of them for files that don't exist.

The patched launcher lists each directory of the game once and answers these lookups from the listing:

- **PathCache** keeps the listings of the directories below the game directory. The `Save` and `Cache` directories are
  never cached.
- **FileHooks** patches the import tables of all loaded modules (the engine, the renderers, Wine's own DLLs) for the
  Win32 file calls. A read-only open or attribute query of a file the cache knows to be missing fails at once, with the
  error Windows would report; so does the engine's attempt to load a package DLL that isn't there. Directory
  enumerations go to the file system as before. Anything that creates, writes, moves or deletes a file drops the
  affected listing, and every map load starts with fresh listings.

How this was found, with profiles and numbers, and the design details: [docs/LOADTIME.md](docs/LOADTIME.md).

### Tested setup

- REDMAGIC Astra 2 (Snapdragon), Android 16, GameHub 6.3.1, the game on `/sdcard` (Wine drive `E:`), started with
  `-localdata -skipdialog`
- proton11.0-arm64x, FEX `Fex_20260921` with the settings from part 1, DXVK 2.7.1
- DXController 1.6.1's launcher with this patch, with Kentie's D3D10 renderer and with DECoR. The game packages carried
  gameplay changes of my own, which don't touch file access.
- Liberty Island and UNATCO HQ: new game, saving, loading (also the same savegame twice in a row), level change
- Windows 11 PC: main menu and quitting, no regressions

### Building it yourself

The fix is a single patch against DXController 1.6.1, in [launcher/patches/](launcher/patches/):

```
git clone https://github.com/dsgls/DXController.git
cd DXController
git checkout v1.6.1
git -c user.name=build -c user.email=build@localhost am path/to/0001-Launcher-fix-minute-long-level-loads-under-Wine-on-A.patch
.\build.ps1 -GameDir <your Deus Ex directory> -SkipScripts
```

This installs the new `DeusEx.exe` (and `SDL3.dll`) into `<your Deus Ex directory>\System`, replacing the ones
there. The build needs Visual Studio 2022 or its Build Tools with the "Desktop development with C++" workload plus the
"C++ MFC" component (the launcher's resource script includes `afxres.h`), and on the first run an internet connection
(`build.ps1` downloads SDL3). See DXController's `development.md` for details.

---

## Repository layout

| Path | Contents |
|---|---|
| `patcher/FirePatch.java` | the Fire.dll patcher (self-contained, includes the assembled dispatcher) |
| `src/kcache.asm` | dispatcher and stubs, NASM source (`nasm -f bin -o kcache.bin src/kcache.asm`) |
| `tools/` | analysis helpers used during the Fire.dll investigation (PE imports/exports, kernel analysis) |
| `docs/TECHNICAL.md` | Fire.dll: root cause, addresses, test matrix |
| `launcher/patches/` | the loading-time fix, as a patch for DXController 1.6.1 |
| `docs/LOADTIME.md` | loading times: investigation, profiles, design of the fix |

No game files are included; `Fire.dll` is © Ion Storm / Epic Games and is not distributed here.

## License

This project's own code — the patcher, the dispatcher and the launcher patch — is GPL-3.0-or-later, see
[LICENSE](LICENSE).

The `DeusEx.exe` on the releases page is DXController 1.6.1's launcher, rebuilt with the patch in `launcher/patches/`,
so it is not only this project's code. DXController (by dsgls) is based on Marijn Kentie's *Deus Exe*. According to
DXController's README, dsgls's modifications are licensed GPLv3 or any license the original author chooses, and no
license has been published for Kentie's original, whose copyright remains with him.
