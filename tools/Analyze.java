import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

// Analyzes Fire.dll .data asm: for each function (entry..ret) count patched sites inside and list branches leaving it.
public class Analyze {
    public static void main(String[] a) throws Exception {
        List<long[]> ins = new ArrayList<>();
        List<String> txt = new ArrayList<>();
        for (String l : Files.readAllLines(Paths.get(a[0]))) {
            String[] p = l.trim().split("\\s+", 3);
            if (p.length < 3) continue;
            long addr = Long.parseLong(p[0], 16);
            if (addr < 0x10515600L || addr > 0x10517210L) continue;
            ins.add(new long[]{addr, p[1].length() / 2});
            txt.add(p[2]);
        }
        Pattern wr = Pattern.compile("mov (?:dword )?\\[(?:dword )?0x(1051[5-7][0-9a-f]{3})\\],e[a-z]x");
        Pattern br = Pattern.compile("^(call|jmp|j[a-z]{1,3})\\s+(?:near |short )?0x([0-9a-f]+)$");
        TreeSet<Long> patched = new TreeSet<>(), targets = new TreeSet<>();
        for (String t : txt) {
            Matcher m = wr.matcher(t);
            if (m.find()) patched.add(Long.parseLong(m.group(1), 16));
            Matcher c = br.matcher(t);
            if (c.find() && c.group(1).equals("call")) targets.add(Long.parseLong(c.group(2), 16));
        }
        for (String e : a[1].split(",")) targets.add(Long.parseLong(e, 16));
        TreeSet<Long> covered = new TreeSet<>();
        for (long t : targets) {
            int i = 0;
            while (i < ins.size() && ins.get(i)[0] < t) i++;
            int j = i;
            while (j < ins.size() && !txt.get(j).startsWith("ret")) j++;
            long end = ins.get(j)[0] + ins.get(j)[1];
            SortedSet<Long> inside = patched.subSet(t, end);
            covered.addAll(inside);
            StringBuilder out = new StringBuilder();
            for (int k = i; k <= j; k++) {
                Matcher c = br.matcher(txt.get(k));
                if (c.find()) {
                    long d = Long.parseLong(c.group(2), 16);
                    if (d < t || d >= end || c.group(1).equals("call"))
                        out.append(" ").append(c.group(1)).append("->").append(Long.toHexString(d));
                }
            }
            System.out.printf("%08X..%08X len=%4d patched=%3d outside:%s%n", t, end, end - t, inside.size(), out);
        }
        TreeSet<Long> rest = new TreeSet<>(patched);
        rest.removeAll(covered);
        System.out.print("patched sites outside all functions (data vars?):");
        for (long r : rest) System.out.print(" " + Long.toHexString(r));
        System.out.println();
    }
}
