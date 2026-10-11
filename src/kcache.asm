; Fire.dll SMC fix (kernel copy cache)
;
; The fire/water asm routines patch displacement fields inside their inner-loop
; "kernels" and then call them. FEX misses some of those patches and runs stale
; code. Instead of calling a patched kernel in place, we look up (or create) an
; immutable copy of the kernel's current bytes in a separate arena and call the
; copy. A copy is never modified after it is written, so no code is ever changed
; after it has been executed.
;
; Kernels are self-contained (only relative branches inside themselves, no calls),
; and all their memory operands are absolute, so a byte copy behaves identically.

bits 32
org 0x1050F100

SEC          equ 0x1051C000          ; new RWX bss section (link-time address)
TABLE        equ SEC                 ; 16384 entries x 16 bytes
TABLE_MASK   equ 16383
NEXTPTR      equ SEC + 0x40000       ; arena bump pointer (0 = not initialised)
ARENA        equ SEC + 0x41000
ARENA_END    equ SEC + 0x340000          ; ~3 MB arena: fewer copies than table slots

; entry layout: +0 kernel start (abs), +4 hash, +8 copy (abs), +12 length

FIX_FLAG     equ 0x80000000          ; stub flag: rewrite the self-patched cmp in the copy
CMP_OFF      equ 0x24D               ; offset of "cmp ebp,imm32" in K5b and K6b

; stubs first, at fixed addresses (16 bytes each): push start + length, then dispatch
%macro KSTUB 2
    push dword %1
    push dword %2
    jmp dispatch
    align 16
%endmacro
stub_k1:  KSTUB 0x105157E4, 222                   ; 0x1050F100
stub_k2:  KSTUB 0x10515990, 209                   ; 0x1050F110
stub_k3:  KSTUB 0x10515E40, 227                   ; 0x1050F120
stub_k4:  KSTUB 0x10516000, 214                   ; 0x1050F130
stub_k5a: KSTUB 0x105164C3, 601                   ; 0x1050F140
stub_k5b: KSTUB 0x1051671C, 620 | FIX_FLAG        ; 0x1050F150
stub_k6a: KSTUB 0x10516D48, 596                   ; 0x1050F160
stub_k6b: KSTUB 0x10516F9C, 620 | FIX_FLAG        ; 0x1050F170

; on entry (from stub): [esp]=len, [esp+4]=kernel start (link-time), [esp+8]=return address
dispatch:
    pushfd
    pushad                              ; regs [esp..+31], flags [+32], len [+36], start [+40], ret [+44]
    cld
    call .here
.here:
    pop ebx
    sub ebx, .here                      ; ebx = relocation delta (load address - link address)

    mov esi, [esp+40]
    add esi, ebx                        ; esi = kernel start (loaded)
    mov ecx, [esp+36]
    and ecx, 0xFFFF                     ; ecx = kernel length (high bit of the slot = FIX_CMP flag)

    ; FNV-1a over the kernel's current bytes
    mov eax, 2166136261
    xor edx, edx
.hash:
    movzx edi, byte [esi+edx]
    xor eax, edi
    imul eax, eax, 16777619
    inc edx
    cmp edx, ecx
    jb .hash
    mov ebp, eax                        ; ebp = hash

    lea edi, [ebx + TABLE]              ; edi = table base
    mov edx, ebp
    and edx, TABLE_MASK
.probe:
    mov eax, edx
    shl eax, 4
    add eax, edi                        ; eax = entry
    cmp dword [eax], 0
    je .miss
    cmp [eax], esi
    jne .next
    cmp [eax+4], ebp
    jne .next
    ; verify bytes (guards against hash collisions); flagged copies differ in the 6 rewritten cmp bytes
    push esi
    push edi
    push ecx
    mov edi, [eax+8]
    test dword [esp+48], FIX_FLAG       ; len slot, shifted by the 3 pushes
    jz .cmp_rest
    mov ecx, CMP_OFF
    repe cmpsb
    jne .vdone
    add esi, 6
    add edi, 6
    mov ecx, [esp]
    sub ecx, CMP_OFF + 6
.cmp_rest:
    repe cmpsb
.vdone:
    pop ecx
    pop edi
    pop esi
    jne .next
    mov eax, [eax+8]                    ; hit: use existing copy
    jmp .go
.next:
    inc edx
    and edx, TABLE_MASK
    jmp .probe

.miss:
    ; allocate copy from arena (16-byte aligned)
    mov edx, eax                        ; edx = free entry
    mov eax, [ebx + NEXTPTR]
    test eax, eax
    jnz .have_next
    lea eax, [ebx + ARENA]
.have_next:
    lea edi, [eax + ecx + 15]
    and edi, -16                        ; edi = new bump pointer
    push edx
    lea edx, [ebx + ARENA_END]
    cmp edi, edx
    pop edx
    ja .full
    mov [ebx + NEXTPTR], edi
    ; copy kernel bytes
    push esi
    push ecx
    mov edi, eax
    rep movsb
    pop ecx
    pop esi
    ; water kernels K5b/K6b raise their own inner-loop limit ("add [own cmp imm32],eax") on every
    ; outer iteration; that add targets the original bytes, so let the copy compare against them:
    ;   81 FD imm32  (cmp ebp,imm32)  ->  3B 2D abs32  (cmp ebp,[original imm32])
    test dword [esp+36], FIX_FLAG
    jz .nofix
    cmp word [esi + CMP_OFF], 0xFD81
    jne .nofix
    mov word [eax + CMP_OFF], 0x2D3B
    lea edi, [esi + CMP_OFF + 2]
    mov [eax + CMP_OFF + 2], edi
.nofix:
    ; fill entry, start field last so a half-written entry is never matched
    mov [edx+8], eax
    mov [edx+12], ecx
    mov [edx+4], ebp
    mov [edx], esi
    jmp .go

.full:
    mov eax, esi                        ; arena exhausted: fall back to the original kernel

.go:
    mov [esp+40], eax                   ; replace "start" slot with call target
    popad
    popfd
    lea esp, [esp+4]                    ; drop len (no flag change)
    ret                                 ; jump to target; [esp] is the original return address

align 16

