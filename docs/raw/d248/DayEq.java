import java.sql.*; import java.util.*;
// Итог по суткам (захват + перенос, поминутно) для журналов стенда; аргументы: метка=путь,путь …
public class DayEq { public static void main(String[] a) throws Exception {
  Map<String, TreeMap<Long, Double>> res = new LinkedHashMap<>();
  for (String arg : a) { String[] lp = arg.split("=", 2); TreeMap<Long, Double> day = new TreeMap<>();
    for (String p : lp[1].split(",")) try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + p + "?mode=ro")) {
      TreeMap<Long, double[]> m = new TreeMap<>();
      ResultSet r = c.createStatement().executeQuery("select ts_ms, fair, inventory from exec_quote where fair > 0 order by ts_ms");
      while (r.next()) m.put(r.getLong(1) / 60000, new double[]{r.getDouble(2), r.getDouble(3)});
      Long prev = null; for (var e : m.entrySet()) { if (prev != null) { double[] pv = m.get(prev); day.merge(e.getKey() / 1440, pv[1] * (e.getValue()[0] - pv[0]), Double::sum); } prev = e.getKey(); }
      r = c.createStatement().executeQuery("select ts_ms, upper(side), qty, price from exec_fill");
      while (r.next()) { var e = m.floorEntry(r.getLong(1) / 60000); if (e == null) continue; double f = e.getValue()[0], px = r.getDouble(4), q = r.getDouble(3);
        day.merge(r.getLong(1) / 86400000, (r.getString(2).equals("BUY") ? f - px : px - f) * q, Double::sum); }
    }
    res.put(lp[0], day); }
  Set<Long> days = new TreeSet<>(); res.values().forEach(d -> days.addAll(d.keySet()));
  System.out.print("сутки"); for (String k : res.keySet()) System.out.print("\t" + k); System.out.println();
  for (long d : days) { System.out.print(java.time.LocalDate.ofEpochDay(d).toString().substring(5)); for (var t : res.values()) System.out.printf(Locale.ROOT, "\t%+.2f", t.getOrDefault(d, 0.0)); System.out.println(); }
} }
