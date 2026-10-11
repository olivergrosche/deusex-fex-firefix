import java.nio.*; import java.nio.file.*; import java.util.*;
// Prints exported functions of a PE32 DLL sorted by address: VA name
public class PeExports {
    public static void main(String[] a) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(Paths.get(a[0]))).order(ByteOrder.LITTLE_ENDIAN);
        int pe = b.getInt(0x3C), opt = pe + 24, base = b.getInt(opt + 28), exp = b.getInt(opt + 96);
        int n = b.getInt(exp + 0x18), fns = b.getInt(exp + 0x1C), names = b.getInt(exp + 0x20), ords = b.getInt(exp + 0x24);
        TreeMap<Integer, String> m = new TreeMap<>(Integer::compareUnsigned);
        for (int i = 0; i < n; i++) {
            int nameRva = b.getInt(names + 4 * i), ord = b.getShort(ords + 2 * i) & 0xffff, rva = b.getInt(fns + 4 * ord);
            StringBuilder s = new StringBuilder(); for (int o = nameRva; b.get(o) != 0; o++) s.append((char) b.get(o));
            m.put(base + rva, s.toString());
        }
        m.forEach((k, v) -> System.out.printf("%08X %s%n", k, v));
    }
}
