import java.nio.*; import java.nio.file.*;
// Lists PE32 sections and imports with their IAT virtual addresses.
public class PeImports {
    static ByteBuffer b; static int nsec, secOff;
    public static void main(String[] a) throws Exception {
        b = ByteBuffer.wrap(Files.readAllBytes(Paths.get(a[0]))).order(ByteOrder.LITTLE_ENDIAN);
        int pe = b.getInt(0x3C); nsec = b.getShort(pe + 6) & 0xffff; int opt = pe + 24;
        int imageBase = b.getInt(opt + 28); secOff = opt + (b.getShort(pe + 20) & 0xffff);
        System.out.printf("ImageBase %08X%n", imageBase);
        for (int i = 0; i < nsec; i++) { int s = secOff + 40 * i; byte[] n = new byte[8]; b.get(s, n);
            System.out.printf("%-8s va=%08X vsz=%08X raw=%08X rsz=%08X%n", new String(n).trim(), b.getInt(s + 12), b.getInt(s + 8), b.getInt(s + 20), b.getInt(s + 16)); }
        int impRva = b.getInt(opt + 104);
        for (int d = off(impRva); b.getInt(d + 12) != 0; d += 20) {
            String dll = str(off(b.getInt(d + 12)));
            int oft = b.getInt(d), ft = b.getInt(d + 16); int names = off(oft != 0 ? oft : ft);
            for (int k = 0; ; k++) { int t = b.getInt(names + 4 * k); if (t == 0) break;
                String fn = t < 0 ? "#" + (t & 0xffff) : str(off(t) + 2);
                if (a.length < 2 || fn.matches(a[1])) System.out.printf("%08X %s!%s%n", imageBase + ft + 4 * k, dll, fn); }
        }
    }
    static int off(int rva) { for (int i = 0; i < nsec; i++) { int s = secOff + 40 * i, va = b.getInt(s + 12), sz = Math.max(b.getInt(s + 8), b.getInt(s + 16));
        if (rva >= va && rva < va + sz) return rva - va + b.getInt(s + 20); } throw new RuntimeException("rva " + rva); }
    static String str(int o) { StringBuilder sb = new StringBuilder(); while (b.get(o) != 0) sb.append((char) b.get(o++)); return sb.toString(); }
}
