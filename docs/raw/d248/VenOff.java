import java.io.*; import java.nio.file.*; import java.util.*; import java.util.zip.GZIPInputStream;
// Отступ живой сделки от опоры в момент сделки у площадки (venue.db trade.tdt), по ботам; сделка → бот по oid = venue_id из exec_fill.
public class VenOff { public static void main(String[] a) throws Exception { String d = a[0];
  Map<String, String[]> tr = new HashMap<>(); // oid → список сделок
  List<String[]> vt = new ArrayList<>(); for (String l : Files.readAllLines(Path.of(d + "/vtrade.csv"))) vt.add(l.split(","));
  for (int k = 1; k < a.length; k++) { String b = a[k]; Set<String> oids = new HashSet<>(); List<Long> rec = new ArrayList<>();
    Map<String, Long> recOf = new HashMap<>();
    for (String l : Files.readAllLines(Path.of(d + "/f-" + b + ".csv"))) { String[] p = l.split(","); oids.add(p[1]); recOf.merge(p[1], Long.parseLong(p[0]), Math::min); }
    TreeMap<Long, Double> f = new TreeMap<>();
    try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(d + "/q-" + b + ".csv.gz"))))) {
      for (String l; (l = r.readLine()) != null; ) { String[] p = l.split(",", -1); if (!p[1].isEmpty()) f.put(Long.parseLong(p[0]), Double.parseDouble(p[1])); } }
    for (String side : new String[]{"buy", "sell"}) { List<Double> o = new ArrayList<>(), o3 = new ArrayList<>(), lag = new ArrayList<>(), seen = new ArrayList<>(); int n = 0;
      for (String[] t : vt) { if (!t[3].equals(side) || !oids.contains(t[1])) continue; long td = Long.parseLong(t[6]); double px = Double.parseDouble(t[5]);
        var e = f.floorEntry(td); var e3 = f.floorEntry(td + 5000); if (e == null || e3 == null) continue; n++;
        double fr = e.getValue(), f3 = e3.getValue();
        o.add(side.equals("buy") ? (fr - px) / fr * 1e4 : (px - fr) / fr * 1e4); o3.add(side.equals("buy") ? (f3 - px) / f3 * 1e4 : (px - f3) / f3 * 1e4);
        Long rc = recOf.get(t[1]); if (rc != null) lag.add((rc - td) / 1000.0); seen.add((Long.parseLong(t[7]) - td) / 1000.0); }
      Collections.sort(o); Collections.sort(o3); Collections.sort(lag); Collections.sort(seen);
      System.out.printf(Locale.ROOT, "живой %s %s: %d сделок; отступ по опоре в момент сделки площадки мед %.1f (25%% %.1f, 75%% %.1f); через 5 с %.1f; запись исполнения ботом позже сделки мед %.1f с (90%% %.1f)%n",
          b, side, n, o.get(n / 2), o.get(n / 4), o.get(3 * n / 4), o3.get(n / 2), lag.isEmpty() ? Double.NaN : lag.get(lag.size() / 2), lag.isEmpty() ? Double.NaN : lag.get(lag.size() * 9 / 10)); } } } }
