# Technical notes

## Symptoms

- `DeusEx.log` floods with `Anomalous singularity in URender::DrawWorld` (Unreal's guard around a caught access
  violation). History: `UFireTexture::ConstantTimeTick` / `UWetTexture::ConstantTimeTick` with textures such as
  `Effects.Electricity.Nano_SFX` (main-menu laser), `Effects.Electricity.Wepn_EMPG_SFX` (riot prod),
  `Effects.water.drtywater_a` (Liberty Island).
- Invisible water / prod effects; the game crawls while hundreds of exceptions per second are handled.
- Later crashes in Core.dll / Engine.dll (e.g. `UObject::SavePackage` while saving), presumably from corrupted data.
- A direct hit: GPF in `Fire.dll+0x16027` while drawing the prod.

## Root cause

`Fire.dll` (image base 0x10500000) keeps hand-written assembly in its **.data section**, 0x10515600–0x10517210.
Six driver routines write displacement/immediate fields into their inner-loop *kernels* right before calling them
(row offsets, palette base, wrap offsets for the last row). Two water kernels additionally execute
`add [own cmp imm32], eax` at the top of each outer iteration, raising their own inner-loop limit.

With FEX's default `SMCChecks=mtrack` some of these writes are missed and stale translated code is executed.

The prod crash shows it directly. Registers at the fault: `EIP=Fire.dll+0x16027`, `EBP=0x0412AFE0`,
`ECX=0x0412B000`. The instruction is `mov bl,[ebp+X]` in kernel K4 (0x10516000). Its displacement `X` is first
patched to the row width (read the row below) and then, before the final call, re-patched so the last row reads a saved
copy of the first row (vertical wrap). The executed code still used the row width: `0x0412AFE0 + 0x20 = 0x0412B000`,
one row past the texture buffer, on an unmapped page.

### Where the routines are used

| Routine | Address | Called from |
|---|---|---|
| 1–4 | 0x105156A0, 0x105158C4, 0x10515CF0, 0x10515F30 | `UFireTexture::ConstantTimeTick` (fire / electricity) |
| 5–6 | 0x10516470, 0x10516CE0 | `UWaterTexture::CalculateWater` (water) |

Entry calls in `.text`: 0x1050E489, 0x1050E4BD, 0x1050E4F9, 0x1050E52D, 0x1050E595, 0x1050E59C.

### Kernels

All kernels are self-contained (only relative branches within themselves, no calls), and every patched site lies inside
a kernel. The remaining written addresses are plain data variables. Code addresses only appear as data for two buffers
(0x10515420, 0x10515A70); there are no indirect jumps.

| Kernel | Start | Length | Call sites (in drivers) | Note |
|---|---|---|---|---|
| K1 | 0x105157E4 | 222 | 0x10515700, 0x10515726, 0x10515752 | |
| K2 | 0x10515990 | 209 | 0x10515923, 0x10515949 | |
| K3 | 0x10515E40 | 227 | 0x10515D50, 0x10515D76, 0x10515DA2 | |
| K4 | 0x10516000 | 214 | 0x10515F8F, 0x10515FB5 | prod crash site |
| K5a | 0x105164C3 | 601 | 0x105164A4 | |
| K5b | 0x1051671C | 620 | 0x105164BD | self-raises `cmp ebp,imm32` at +0x24D |
| K6a | 0x10516D48 | 596 | 0x10516D42 | |
| K6b | 0x10516F9C | 620 | 0x10516D23 | self-raises `cmp ebp,imm32` at +0x24D |

## The patch

Input: stock `Fire.dll` (MD5 `694b91490525a967270d8b04fa53217e`). Step 0 sets the `.data` characteristics from
`0xC0000040` to `0xE0000040` (executable), giving MD5 `517d8406216803fa8cb1b23a4f7b33dd`. Output MD5:
`f002c84059aca023f537016b26a4a47c`.

1. **`.fcode` mirror** (RVA 0x35C000, read+execute): a copy of `.data` raw 0x15000–0x18000 (mirror delta 0x347000).
   The six entry calls in `.text` are redirected into the mirror. All asm runs there. Driver writes still target the
   original `.data` bytes via absolute operands, and those bytes are never executed. So no write ever hits a page that
   contains executed code.
2. **Kernel copy cache.** The 14 kernel call sites in the mirror call per-kernel stubs in the zero-filled tail of
   `.text`, at 0x1050F100–0x1050F17F (16 bytes each). Each stub pushes the kernel's start and length, then jumps to
   `dispatch` (0x1050F180). The dispatcher:
   - computes FNV-1a over the kernel's current bytes;
   - looks up an entry in a 16384-slot table in the new bss section `.kcache` (RVA 0x1C000, 0x340000 bytes);
   - verifies a hit byte by byte;
   - on a miss, copies the kernel into the arena (~3 MB);
   - jumps to the copy with all registers and flags preserved.

   Copies are never modified after creation. If the arena is full, it falls back to the original kernel.
3. **K5b/K6b.** When creating their copies, `81 FD imm32` (`cmp ebp,imm32`) at +0x24D is rewritten to `3B 2D abs32`
   (`cmp ebp,[abs32]`, same length) pointing at the original immediate. The kernel's own `add` and the driver (which
   reads the final value afterwards) keep working on the original bytes, and the copy reads the live value. Hit
   verification skips those 6 bytes. The start value is set by the driver before each call, so the hash stays stable.
4. **Relocations.** `Fire.dll` gets rebased at runtime because its preferred base overlaps Engine.dll. The relocation
   directory is rebuilt in a new section `.reloc2` with all original entries, plus duplicates (+0x347000) for every
   entry inside the mirrored range. The mirror's absolute operands are therefore fixed up exactly like the originals.
5. `.text` VirtualSize is raised to 0xF000 to cover the stub area. `SizeOfImage` and `NumberOfSections` (7) are updated.

### Rebuilding the dispatcher

```
nasm -f bin -o kcache.bin src/kcache.asm     # NASM 2.16
base64 kcache.bin                             # -> KCACHE constant in patcher/FirePatch.java
```

## What was tried before (GameHub, proton11.0-arm64x, FEX)

| Change | Result |
|---|---|
| X87ReducedPrecision off | more texture faults, crash in Core.dll on level load |
| SMCChecks full | crash in dxgi.dll at D3D10 init (white screen) |
| SMCChecks full + Direct3D 7 renderer (wined3d) | no texture faults, but grey screen |
| SMCChecks full + DXVK 2.6 | `Assertion failed … winevulkan/loader_thunks.c … vkCreateGraphicsPipelines` |
| mtrack + MaxInst 1 | `Assertion failed … vkAcquireNextImageKHR` |
| x64 Proton + Box64 | Wine exits with code 123, game never starts |
| older FEX (Fex-20251128, Fex-20260103) | crash in a DXVK thread at startup |
| newest FEX (Fex_20260921) | same texture faults |
| `NtFlushInstructionCache` before each kernel call | faults remain |
| kernel copy cache only (asm still executed in .data) | faults remain in the menu |
| + mirror section | fire/electricity fixed, water hangs (self-raised loop limit) |
| + K5b/K6b fixup | **0 faults, saving works** |

Related findings:
- The original GOG launcher appears fault-free, but only because its RDTSC-based timing is broken under FEX (about
  1 frame per 10 s), so the procedural textures barely tick.
- Removing DXController's SDL3 controller threads or Kentie's vectored exception handler does not change the faults.
  The mod is not the cause.

## Useful logs

- `System/DeusEx.log`. Kentie's launcher flushes it live and prints crash details: exception address, registers,
  faulting module and offset. Count `Anomalous` lines to measure.
- GameHub: `Android/data/com.xiaoji.egggame/files/logs/log_pcengine_*.txt` contains the full launch config (wine
  build, `resolvedCpuTranslator`, FEX/Box64 parameters) and the Wine exit code.
