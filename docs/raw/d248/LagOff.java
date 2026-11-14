import java.io.*; import java.nio.file.*; import java.util.*; import java.util.zip.GZIPInputStream;
// Живой: отступ покупки от опоры в момент записи исполнения и за 1/3/5/10 с до неё (опора из посекундного журнала котировок).
public class LagOff { public static void main(String[] a) throws Exception { String d = a[0];
  for (int k = 1; k < a.length; k++) { String b = a[k]; TreeMap<Long, Double> f = new TreeMap<>();
    try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(d + "/q-" + b + ".csv.gz"))))) {
      for (String l; (l = r.readLine()) != null; ) { String[] p = l.split(",", -1); if (!p[1].isEmpty()) f.put(Long.parseLong(p[0]), Double.parseDouble(p[1])); } }
    for (String side : new String[]{"BUY", "SELL"}) { StringBuilder s = new StringBuilder("живой " + b + " " + side.toLowerCase() + ", медиана отступа по опоре за:");
      for (long lag : new long[]{0, 1000, 3000, 5000, 10000, 30000}) { List<Double> o = new ArrayList<>();
        for (String l : Files.readAllLines(Path.of(d + "/f-" + b + ".csv"))) { String[] p = l.split(","); if (!p[2].equalsIgnoreCase(side)) continue;
          var e = f.floorEntry(Long.parseLong(p[0]) - lag); if (e == null) continue; double fr = e.getValue(), px = Double.parseDouble(p[4]);
          o.add(side.equals("BUY") ? (fr - px) / fr * 1e4 : (px - fr) / fr * 1e4); }
        Collections.sort(o); s.append(String.format(Locale.ROOT, " %d с %.1f (25%% %.1f);", lag / 1000, o.get(o.size() / 2), o.get(o.size() / 4))); }
      System.out.println(s); } } } }
