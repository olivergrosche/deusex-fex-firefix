/*
 * FirePatch - makes Deus Ex's Fire.dll work under the FEX x86 emulator (GameHub, Winlator, ...).
 * Copyright (C) 2026  deusex-fex-firefix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE.
 *
 * Usage:  java FirePatch.java <path-to-original-Fire.dll> [output.dll]
 *
 * Input must be the stock Deus Ex GOTY 1112fm Fire.dll (GOG/Steam). The original is never modified;
 * the result is written next to it as Fire.dll.fixed (or to the given output path).
 */
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public class FirePatch {
    static final String MD5_ORIGINAL = "694b91490525a967270d8b04fa53217e";   // stock GOTY 1112fm
    static final String MD5_EXECDATA = "517d8406216803fa8cb1b23a4f7b33dd";   // stock + .data exec flag
    static final String MD5_RESULT   = "f002c84059aca023f537016b26a4a47c";   // expected output

    // dispatcher + kernel stubs, assembled from src/kcache.asm (nasm -f bin), 400 bytes at 0x1050F100
    static final String KCACHE = "aORXURBo3gAAAOt0kJCQkGiQWVEQaNEAAADrZJCQkJBoQF5REGjjAAAA61SQkJCQaABgURBo1gAAAOtEkJCQkGjDZFEQaFkCAADrNJCQkJBoHGdREGhsAgCA6ySQkJCQaEhtURBoVAIAAOsUkJCQkGicb1EQaGwCAIDrBJCQkJCcYPzoAAAAAFuB64jxUBCLdCQoAd6LTCQkgeH//wAAuMWdHIEx0g+2PBYx+GnAkwEAAUI5ynLvicWNuwDAURCJ6oHi/z8AAInQweAEAfiDOAB0RjkwdTk5aAR1NFZXUYt4CPdEJDAAAACAdBi5TQIAAPOmdRGDxgaDxwaLDCSB6VMCAADzpllfXnUFi0AI63VCgeL/PwAA666JwouDAMBVEIXAdQaNgwDQVRCNfAgPg+fwUo2TAMCFEDnXWndFibsAwFUQVlGJx/OkWV73RCQkAAAAgHQgZoG+TQIAAIH9dRVmx4BNAgAAOy2Nvk8CAACJuE8CAACJQgiJSgyJagSJMusCifCJRCQoYZ2NZCQEww==";

    static final long BASE = 0x10500000L, CODE = 0x1050F100L;
    static final int DATA_RVA = 0x15000, DATA_RAW = 0x15000, DATA_LEN = 0x3000;
    static final int KC_RVA = 0x1C000, KC_SIZE = 0x340000;
    static final int FC_RVA = KC_RVA + KC_SIZE;               // mirror section .fcode
    static final int MD = FC_RVA - DATA_RVA;                   // mirror delta
    static final int RL_RVA = FC_RVA + DATA_LEN;               // rebuilt relocations .reloc2

    // kernel call site (in the asm area) -> {original kernel, stub in .text}
    static final long[][] KSITES = {
        {0x10515700L, 0x105157E4L, 0x1050F100L}, {0x10515726L, 0x105157E4L, 0x1050F100L}, {0x10515752L, 0x105157E4L, 0x1050F100L},
        {0x10515923L, 0x10515990L, 0x1050F110L}, {0x10515949L, 0x10515990L, 0x1050F110L},
        {0x10515D50L, 0x10515E40L, 0x1050F120L}, {0x10515D76L, 0x10515E40L, 0x1050F120L}, {0x10515DA2L, 0x10515E40L, 0x1050F120L},
        {0x10515F8FL, 0x10516000L, 0x1050F130L}, {0x10515FB5L, 0x10516000L, 0x1050F130L},
        {0x105164A4L, 0x105164C3L, 0x1050F140L}, {0x105164BDL, 0x1051671CL, 0x1050F150L},
        {0x10516D42L, 0x10516D48L, 0x1050F160L}, {0x10516D23L, 0x10516F9CL, 0x1050F170L},
    };
    // .text call site -> original asm routine (redirected to the mirror)
    static final long[][] ENTRIES = {
        {0x1050E489L, 0x105156A0L}, {0x1050E4BDL, 0x105158C4L}, {0x1050E4F9L, 0x10515CF0L},
        {0x1050E52DL, 0x10515F30L}, {0x1050E595L, 0x10516CE0L}, {0x1050E59CL, 0x10516470L},
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java FirePatch.java <original Fire.dll> [output.dll]");
            System.exit(2);
        }
        Path in = Paths.get(args[0]);
        Path out = args.length > 1 ? Paths.get(args[1]) : in.resolveSibling(in.getFileName() + ".fixed");
        byte[] d = Files.readAllBytes(in);
        String md5 = md5(d);
        if (md5.equals(MD5_RESULT)) { System.out.println("This Fire.dll is already patched."); return; }
        if (md5.equals(MD5_ORIGINAL)) {
            int pe = le32(d, 0x3C), sec = pe + 24 + u16(d, pe + 20);
            int dataChar = sec + 2 * 40 + 36;                  // .data characteristics
            if (le32(d, dataChar) != 0xC0000040) fail("unexpected .data flags");
            put32(d, dataChar, 0xE0000040);                    // allow executing .data
        } else if (!md5.equals(MD5_EXECDATA)) {
            fail("unknown Fire.dll (md5 " + md5 + "). Only the stock Deus Ex GOTY 1112fm Fire.dll is supported.");
        }

        byte[] result = patch(d, java.util.Base64.getDecoder().decode(KCACHE));
        String outMd5 = md5(result);
        if (!outMd5.equals(MD5_RESULT)) fail("result checksum mismatch (" + outMd5 + "), not writing output");
        Files.write(out, result);
        System.out.println("OK: wrote " + out + " (md5 " + outMd5 + ")");
        System.out.println("Copy it over <Deus Ex>/System/Fire.dll (keep a backup of the original).");
    }

    static byte[] patch(byte[] d, byte[] code) throws Exception {
        if (d.length != 0x1A000) fail("unexpected file size " + d.length);
        int pe = le32(d, 0x3C), opt = pe + 24, sec = opt + u16(d, pe + 20);
        if (u16(d, pe + 6) != 4) fail("expected 4 sections");

        // 1) cache dispatcher + kernel stubs into the zero-filled tail of .text
        int off = (int) (CODE - BASE);
        for (int i = 0; i < code.length; i++) {
            if (d[off + i] != 0) fail("no room in .text");
            d[off + i] = code[i];
        }
        put32(d, sec + 8, 0xF000);

        // 2) mirror of the .data asm area; its kernel calls go through the cache stubs
        byte[] fc = Arrays.copyOfRange(d, DATA_RAW, DATA_RAW + DATA_LEN);
        for (long[] s : KSITES) {
            int o = (int) (s[0] - BASE) - DATA_RVA;
            if ((fc[o] & 0xff) != 0xE8 || s[0] + 5 + le32(fc, o + 1) != s[1]) fail("kernel call mismatch");
            put32(fc, o + 1, (int) (s[2] - (s[0] + MD + 5)));
        }
        // 3) the C++ code calls the mirror instead of the original asm
        for (long[] e : ENTRIES) {
            int o = (int) (e[0] - BASE);
            if ((d[o] & 0xff) != 0xE8 || e[0] + 5 + le32(d, o + 1) != e[1]) fail("entry call mismatch");
            put32(d, o + 1, (int) ((e[1] + MD) - (e[0] + 5)));
        }

        // 4) relocations: originals + duplicates for the mirrored area
        int relRva = le32(d, opt + 136), relSize = le32(d, opt + 140);
        int relRaw = rvaToRaw(d, sec, 4, relRva);
        TreeSet<Integer> rel = new TreeSet<>();
        for (int p = relRaw; p < relRaw + relSize; ) {
            int page = le32(d, p), bsz = le32(d, p + 4);
            for (int q = p + 8; q < p + bsz; q += 2) {
                int ent = u16(d, q), type = ent >>> 12;
                if (type == 0) continue;
                if (type != 3) fail("unsupported relocation type " + type);
                rel.add(page + (ent & 0xfff));
            }
            p += bsz;
        }
        for (int r : new ArrayList<>(rel)) if (r >= DATA_RVA && r < DATA_RVA + DATA_LEN) rel.add(r + MD);
        ByteArrayOutputStream rb = new ByteArrayOutputStream();
        List<Integer> all = new ArrayList<>(rel);
        for (int i = 0; i < all.size(); ) {
            int page = all.get(i) & ~0xfff;
            List<Integer> blk = new ArrayList<>();
            while (i < all.size() && (all.get(i) & ~0xfff) == page) blk.add(all.get(i++));
            if (blk.size() % 2 == 1) blk.add(-1);
            w32(rb, page); w32(rb, 8 + 2 * blk.size());
            for (int r : blk) w16(rb, r < 0 ? 0 : (3 << 12) | (r & 0xfff));
        }
        byte[] rl = rb.toByteArray();
        int rlRawSize = align(rl.length, 0x1000);

        // 5) new section headers: .kcache (bss arena), .fcode (mirror), .reloc2
        int ns = sec + 40 * 4;
        for (int i = 0; i < 40 * 3; i++) if (d[ns + i] != 0) fail("no room for section headers");
        hdr(d, ns, ".kcache", KC_SIZE, KC_RVA, 0, 0, 0xE0000080);
        hdr(d, ns + 40, ".fcode", DATA_LEN, FC_RVA, DATA_LEN, 0x1A000, 0x60000020);
        hdr(d, ns + 80, ".reloc2", rl.length, RL_RVA, rlRawSize, 0x1A000 + DATA_LEN, 0x42000040);
        d[pe + 6] = 7;
        put32(d, opt + 136, RL_RVA);
        put32(d, opt + 140, rl.length);
        put32(d, opt + 56, RL_RVA + align(rl.length, 0x1000));

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(d); o.write(fc); o.write(Arrays.copyOf(rl, rlRawSize));
        return o.toByteArray();
    }

    static void fail(String msg) { System.err.println("ERROR: " + msg); System.exit(1); }
    static String md5(byte[] b) throws Exception {
        StringBuilder s = new StringBuilder();
        for (byte x : MessageDigest.getInstance("MD5").digest(b)) s.append(String.format("%02x", x));
        return s.toString();
    }
    static void hdr(byte[] d, int o, String name, int vsz, int va, int rsz, int raw, int flags) throws Exception {
        System.arraycopy(Arrays.copyOf(name.getBytes("US-ASCII"), 8), 0, d, o, 8);
        put32(d, o + 8, vsz); put32(d, o + 12, va); put32(d, o + 16, rsz); put32(d, o + 20, raw); put32(d, o + 36, flags);
    }
    static int rvaToRaw(byte[] d, int sec, int n, int rva) {
        for (int i = 0; i < n; i++) {
            int s = sec + 40 * i, va = le32(d, s + 12), sz = Math.max(le32(d, s + 8), le32(d, s + 16));
            if (rva >= va && rva < va + sz) return rva - va + le32(d, s + 20);
        }
        fail("bad rva " + Integer.toHexString(rva));
        return -1;
    }
    static int align(int v, int a) { return (v + a - 1) & -a; }
    static int u16(byte[] b, int o) { return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8; }
    static int le32(byte[] b, int o) { return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24; }
    static void put32(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24); }
    static void w32(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >> 8); o.write(v >> 16); o.write(v >> 24); }
    static void w16(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >> 8); }
}
