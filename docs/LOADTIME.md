# Loading times under Wine on Android storage

How the minute-long loading times of Deus Ex on an Android tablet were tracked down, and how the patched launcher
avoids them. The setup is the one in the README (GameHub, proton11.0-arm64x, FEX, DXVK, DXController 1.6.1, the game
on `/sdcard`, Wine drive `E:`).

## Symptom

| | tablet |
|---|---|
| loading the main menu | 45.6 s |
| new game / new map | ~2 min |
| loading a savegame (Liberty Island) | 4–5 min |

On a PC the same loads take about a second. On the tablet it happened independently of renderer, resolution, FEX
settings (multiblock), audio backend, gameplay mods and reboots. While loading, the main thread was asleep most of the
time (about 10 s of CPU time in the 45.6 s, most of it kernel time); the GPU had nothing to do.

## Not the file reads

The engine's package reader (`FArchiveFileReader` in `Core/Inc/FFileManagerWindows.h`) has a 1 KB buffer, and each
`Seek` calls `SetFilePointer` and discards the buffer. Loading the main-menu map takes about 60,000 `ReadFile` and
83,000 `SetFilePointer` calls. A memory-mapped reader (`FastFileReader` in the patch) brings that to zero — and the
savegame load still took 4:22. The reads were not the problem. The reader stayed in the patch anyway: it saves those
system calls, each of them a round trip out of the emulated game code through Wine.

## Sampling the main thread

Without root there is no `perf` or `strace` on the device. So the launcher got a small sampler (`LoadProfiler`):
during a map load, a helper thread suspends the main thread every 15 ms, copies EIP and the return addresses found on
its stack, and resumes it. The samples are symbolized through the export tables of the loaded modules, and the
summary goes to `DeusEx.log`.

Main menu, 45.6 s, 2,619 samples:

| | share of samples |
|---|---|
| EIP in Wine's system call stub | 93.5 % |
| …called from `ZwCreateFile` ← `CreateFileW` | 91.3 % |
| first game frame `FFileManagerWindows::FileSize` (existence probes by the engine) | 61 % |
| first game frame in the engine's texture code, called from `Render.dll` | 31 % |

So almost all of the load time was spent inside `CreateFileW`. The texture group showed no caller between
`CreateFileW` and `Engine.dll`, because the sampler only knew the modules that were loaded when the map load
began — the renderer had been loaded later.

## Tracing the file calls

To get file names and exact callers, the launcher patches the import tables of all modules for the Win32 file calls
(`FileHooks`) and records count, time, file name and caller (`FileTrace=True`). On the PC the main menu already
showed both sources:

```
File trace:   CreateFileW: 1479 calls (1371 failed, ...)
File trace:  CreateFileW  d3dx10_43.dll+0x3d82d <- D3D10Drv.DLL+0x4b72   1256x (1220 failed)
             e.g. '..\textures\DeusExUI\UserInterface\InventoryBackground_1.dds'
Path cache: 3083 lookups, 3083 answered (2913 not found)
```

1. **Localization.** Objects of a class with translatable properties are localized when they are loaded
   (`AActor::PostLoad` → `UObject::LoadLocalized`, and `UClass::Serialize` for class defaults): for every such property
   and array element, `Localize()` asks the config cache for `<package>.int`. `FConfigCacheIni::Find` checks with
   `GFileManager->FileSize()` — which opens the file to find out whether it exists — and does not remember a file that
   isn't there. So a map without its own `.int` file (none of them has one) costs one failed lookup per translatable
   property of every actor in it; Deus Ex's `Actor` class alone has two, a weapon dozens.
2. **Package search and native DLLs.** `appFindPackageFile` tries each package name in every `Paths` entry (`System`,
   `Maps`, `Textures`, `Sounds`, `Music`), with and without the extension, and then again through `CdPath=..\`. Binding
   a package, Core tries to load a native DLL of the same name: `<System>\<package>`, `<System>\<package>.dll` and
   `_<System>\<package>.dll` (that last name can never exist). Most packages have none.
3. **D3D10Drv's texture replacements.** For every texture it uploads, Kentie's D3D10 renderer asks D3DX to load
   `..\textures\<package>\<group>\<texture>.dds`, and for some textures also `.detail.dds` and a `.dds.flags` file.
   There is no setting to turn this off.

The same PC run with the cache switched off (`PathCache=False`, `FileTrace=True`) shows the shares up to the main menu:

| | failed lookups |
|---|---|
| D3D10Drv replacements (1,220 `.dds`, 108 `.detail.dds`, 36 `.flags`) | 1,364 |
| `DXController.int` (class defaults of DXController's classes) | 1,085 |
| `UBrowser.int` | 177 |
| `DX.int` (actors of the menu map) | 86 |
| other localization files and package search paths | ~200 |
| native DLLs of packages (`LoadLibraryW`) | 48 |

Loading the Liberty Island savegame made 15,127 lookups, 14,897 of them for files that don't exist. The savegame holds
about 2,700 actors; counting the translatable properties of the saved actors gives about 14,700 lookups of the map's
`01_NYC_UNATCOIsland.int` alone.

## Why Wine is slow here

Windows file names are case-insensitive, Linux file systems are not. Wine first tries the exact name; if that fails, it
opens the directory and compares every entry case-insensitively. For a file that doesn't exist, that is always a full
directory scan. On Android's shared storage, every directory read and every `stat` goes through the FUSE daemon. On
the tablet a failed lookup cost about 2 ms in a small directory and around 15 ms in the large ones (`System`,
`Textures`) — times 15,000 makes the 4 minutes.

## The fix

### PathCache

- Lists a directory once (`FindFirstFileExW`, `FindExInfoBasic`, `FIND_FIRST_EX_LARGE_FETCH`) and keeps name, size and
  type of every entry, keyed by the upper-cased name.
- Answers *exists / size / does not exist / directory does not exist* from the listing. Missing directories are
  cached too (D3D10Drv asks for `..\textures\<package>\...` directories that don't exist).
- Only for paths below the game directory. Never for the `Save` and `Cache` directories, names with wildcards, `~`
  (8.3 aliases), alternate data streams, device or UNC paths.
- A listing that ends with an error other than "no more files" is not kept.
- Data on a private heap with an SRW lock: lookups come from any thread, and the engine's allocator (behind the global
  `operator new`) is not thread-safe.

### FileHooks

- Patches the import address tables of all loaded modules except `kernel32`, `kernelbase` and `ntdll` for
  `CreateFileA/W`, `GetFileAttributes(Ex)A/W`, `FindFirstFile(Ex)A/W`, `CopyFile(Ex)A/W`, `MoveFile(Ex)A/W`,
  `DeleteFileA/W`, `Create/RemoveDirectoryA/W` and `LoadLibrary(Ex)A/W`. A module loaded later gets patched when its
  `LoadLibrary` returns, and all modules are checked again at every map load.
- A read-only open (`OPEN_EXISTING`, no write access) or an attribute query of a path the cache knows to be missing
  fails at once with `ERROR_FILE_NOT_FOUND`, or `ERROR_PATH_NOT_FOUND` when the directory is missing — what Windows
  reports.
- Every call that can create, change, move or delete something drops the listing of the affected directory (and of the
  path itself, if it is a directory). Every map load starts with fresh listings, so even changes from outside the
  process are seen at the next load.
- Calls answered from the cache set the error Windows would report, and the hooks' own bookkeeping leaves
  `GetLastError()` alone. One exception: a call that goes on to the OS and succeeds right after the cache listed a new
  directory can leave `ERROR_NO_MORE_FILES` behind; callers don't read the error after success.
- Core's attempts to load a native DLL for a package fail at once (`ERROR_MOD_NOT_FOUND`) when the name can't be a
  valid path (`_E:\...`) or the cache knows the file is missing (with `.dll` appended if the name has no extension).
  Only calls made by `Core.dll` are answered this way; every other `LoadLibrary` goes to the loader as usual.
- `FFileManagerDeusExe::FileSize` answers from the cache directly, and `FastFileReader` opens packages by their on-disk
  spelling, so a differently cased name (`..\Maps\entry.dx` for `Entry.dx`) doesn't cost a scan either.
- On a crash the hooks switch to plain pass-through before the crash report is written.

### Known limitation

Files that another program adds to the game directory while a level is running are seen at the next map load, not
before. That matters for one case: D3D10Drv replacement textures copied in during play and reloaded with `flush` —
load the level again instead.

### Results

Tablet, in the order the parts were added:

| | before | file lookups cached | + package DLL probes cached (release) |
|---|---|---|---|
| loading the main menu (D3D10 renderer) | 45.6 s | 3.1 s | — |
| loading a savegame (Liberty Island) | 4–5 min | 3.4 s (D3D10), 3.2–3.3 s (DECoR) | 1.1 s (DECoR) |
| loading the same savegame again | — | 1.0 s (DECoR) | — |

A level change from Liberty Island to UNATCO HQ took 1.9 s with the file lookups cached: 0.4 s to save the old map,
1.45 s to load the new one. With DECoR, loading the main menu takes 9–13 s, because DECoR compiles its shaders at
startup.

Before the package DLL probes were answered from the cache, they were the largest cost left: 84 failed `LoadLibraryW`
calls per savegame load, 2.2 s in total. The malformed `_E:\...` name alone cost about 45–80 ms per call, because a
relative name is looked up in every directory of the DLL search path.

Still left, by design: lookups in the `Save` directory are never cached (the save and load menus probe
`SaveNNNN\SaveInfo.dxs` and a `QuickSave` that may not exist), and directory enumerations with wildcards
(`FindFirstFile("..\System\*.u")`) always go to the file system. In a long session they add up to a few seconds,
spread over the menus.

## Diagnostics

With `[DeusExe] FileTrace=True` the launcher logs after every level change, here from a savegame load with the build
that didn't yet answer the package DLL probes:

```
File trace: 292 calls, 2.65 s in total; 59 answered from the path cache.
File trace:   LoadLibraryW: 84 calls (84 failed, 0 from cache), 2.20 s, 26.24 ms each.
File trace:   CreateFileW: 161 calls (116 failed, 59 from cache), 0.18 s, 1.12 ms each.
File trace: --- callers by time ---
File trace:  31.2%    0.83 s     15x (15 failed, 0 from cache)  LoadLibraryW  Core.dll+0x6c3cb <- ...  e.g. '_E:\...\System\saveInfo.dll'
...
File trace: --- files by time (257 distinct) ---
...
Path cache: 15127 lookups, 14975 answered (14897 not found); 9 directory listings (32 ms), 4 invalidations; ...
```

Callers are shown as the direct caller of the Win32 function plus the next game frames found on the stack,
symbolized as `module+offset (nearest export+offset)`.

`[DeusExe] LoadProfiler=True` adds the sampling profile of each map load: the load time, main-thread CPU time, CPU time
per thread, and the most frequent leaf functions, callers and stack chains.
