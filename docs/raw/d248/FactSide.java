import java.sql.*; import java.util.*;
// Факт стенда по сторонам: исполнений в лотах в сутки, маркаут 5 мин (б.п., сырой и за вычетом хода часа), доход б.п.·лот/сут. Аргументы: лот_$ журнал t0 t1
public class FactSide { public static void main(String[] a) throws Exception { double lot = Double.parseDouble(a[0]);
  long t0 = java.time.Instant.parse(a[2]).toEpochMilli(), t1 = java.time.Instant.parse(a[3]).toEpochMilli();
  try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + a[1] + "?mode=ro")) {
    TreeMap<Long, Double> f = new TreeMap<>();
    ResultSet r = c.createStatement().executeQuery("select ts_ms, fair from exec_quote where fair > 0 and ts_ms % 5000 < 1100");
    while (r.next()) f.put(r.getLong(1), r.getDouble(2));
    double[] L = new double[2], M = new double[2], MD = new double[2]; int[] n = new int[2];
    r = c.createStatement().executeQuery("select ts_ms, upper(side), qty, price from exec_fill where ts_ms >= " + t0 + " and ts_ms < " + t1);
    while (r.next()) { long t = r.getLong(1); int s = r.getString(2).equals("BUY") ? 0 : 1; double px = r.getDouble(4), l = r.getDouble(3) * px / lot;
      var e5 = f.ceilingEntry(t + 300_000L); var em = f.ceilingEntry(t + 1_800_000L); var ep = f.floorEntry(t - 1_800_000L);
      if (e5 == null || e5.getKey() > t + 360_000L) continue;
      double mk = (s == 0 ? e5.getValue() / px - 1 : 1 - e5.getValue() / px) * 1e4;
      double dr = (em != null && ep != null) ? (em.getValue() / ep.getValue() - 1) * 1e4 / 12 : 0;
      double mkd = mk - (s == 0 ? dr : -dr);
      L[s] += l; M[s] += mk * l; MD[s] += mkd * l; n[s]++; }
    double days = (t1 - t0) / 86_400_000.0;
    for (int s = 0; s < 2; s++) System.out.printf(Locale.ROOT, "  %s: %.1f лотов/сут (%d сделок), маркаут 5 мин %.2f б.п., за вычетом хода часа %.2f; доход %.0f б.п.·лот/сут%n",
        s == 0 ? "бид" : "аск", L[s] / days, n[s], M[s] / L[s], MD[s] / L[s], MD[s] / days); } } }
