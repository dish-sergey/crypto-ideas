import java.sql.*; import java.util.*;
// Медиана отступа бида и аска (б.п. от опоры) по запасу в долях лота: аргументы лот_$ метка=журнал…
public class OffQ { public static void main(String[] a) throws Exception { double lot = Double.parseDouble(a[0]);
  double[] edges = {0, 0.02, 0.15, 0.35, 0.65, 0.9, 1.1, 1.5, 2.0, 99};
  String[] nm = {"0", "~0.1", "~0.25", "~0.5", "~0.75", "~1", "1.1–1.5", "1.5–2", ">2"};
  System.out.print("запас, лотов"); for (String n : nm) System.out.print("\t" + n); System.out.println();
  for (int k = 1; k < a.length; k++) { String[] lp = a[k].split("=", 2);
    List<List<Double>> b = new ArrayList<>(), s = new ArrayList<>(); int[] cnt = new int[nm.length];
    for (int i = 0; i < nm.length; i++) { b.add(new ArrayList<>()); s.add(new ArrayList<>()); }
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + lp[1] + "?mode=ro")) {
      ResultSet r = c.createStatement().executeQuery("select fair, bid, ask, inventory from exec_quote where fair > 0 and quotable = 1 and ts_ms % 10000 < 1000");
      while (r.next()) { double f = r.getDouble(1), q = r.getDouble(4) * f / lot; int i = 0; while (i < nm.length - 1 && q >= edges[i + 1]) i++; cnt[i]++;
        double bi = r.getDouble(2); if (!r.wasNull()) b.get(i).add((f - bi) / f * 1e4); double as = r.getDouble(3); if (!r.wasNull()) s.get(i).add((as - f) / f * 1e4); } }
    long tot = Arrays.stream(cnt).sum(); StringBuilder o = new StringBuilder(lp[0]);
    for (int i = 0; i < nm.length; i++) o.append('\t').append(cnt[i] * 100 / tot < 1 ? "·" : med(b.get(i)) + "/" + med(s.get(i)) + " (" + cnt[i] * 100 / tot + "%)");
    System.out.println(o); } }
  static String med(List<Double> l) { if (l.isEmpty()) return "–"; Collections.sort(l); return String.format(Locale.ROOT, "%.1f", l.get(l.size() / 2)); } }
