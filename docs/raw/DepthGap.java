import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

// Проверка записи стакана Бинанса (bnb-depth): цепочка номеров изменений по каждому потоку. Перп: pu == u предыдущего;
// спот: U == u предыдущего + 1. Печатает число сообщений, разрывов, тишину дольше 5 с и дыры по времени.
// Аргументы: <spot|perp> <файл .jsonl.gz>…
public class DepthGap {
    static long num(String l, String key) { int i = l.indexOf(key); if (i < 0) return Long.MIN_VALUE; i += key.length(); int j = i; while (j < l.length() && (Character.isDigit(l.charAt(j)) || l.charAt(j) == '-')) j++; return Long.parseLong(l.substring(i, j)); }
    static String str(String l, String key) { int i = l.indexOf(key); if (i < 0) return null; i += key.length(); return l.substring(i, l.indexOf('"', i)); }

    public static void main(String[] a) throws Exception {
        boolean perp = a[0].equals("perp");
        for (int k = 1; k < a.length; k++) {
            Map<String, long[]> st = new TreeMap<>();   // поток → сообщений, разрывов, последний u, последнее время, тишин > 5 с, макс тишина
            Map<String, List<String>> breaks = new HashMap<>();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(a[k]), 1 << 16), StandardCharsets.UTF_8), 1 << 20)) {
                for (String l; (l = br.readLine()) != null; ) {
                    String s = str(l, "\"stream\":\""); if (s == null) s = str(l, "\"s\":\""); if (s == null) continue;
                    long t = Long.parseLong(l.substring(0, l.indexOf(' ')));
                    long U = num(l, "\"U\":"), u = num(l, "\"u\":"), pu = perp ? num(l, "\"pu\":") : 0;
                    long[] x = st.computeIfAbsent(s, z -> new long[]{0, 0, Long.MIN_VALUE, 0, 0, 0});
                    x[0]++;
                    if (x[2] != Long.MIN_VALUE) {
                        boolean ok = perp ? pu == x[2] : U == x[2] + 1;
                        if (!ok) { x[1]++; var b = breaks.computeIfAbsent(s, z -> new ArrayList<>()); if (b.size() < 5) b.add(java.time.Instant.ofEpochMilli(t).toString().substring(11, 19) + " (тишина " + (t - x[3]) / 1000 + " с)"); }
                        long gap = t - x[3]; if (gap > 5000) x[4]++; x[5] = Math.max(x[5], gap);
                    }
                    x[2] = u; x[3] = t;
                }
            }
            System.out.println("== " + a[k]);
            for (var e : st.entrySet()) { long[] x = e.getValue();
                System.out.printf("   %s: сообщений %d, разрывов цепочки %d, тишина > 5 с: %d раз, самая долгая %.1f с %s%n", e.getKey(), x[0], x[1], x[4], x[5] / 1000.0, breaks.getOrDefault(e.getKey(), List.of())); }
        }
    }
}
