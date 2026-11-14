import java.sql.*; import java.util.*;
public class FillOff { public static void main(String[] a) throws Exception { for (String x : a) { String[] lp = x.split("=", 2);
  try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + lp[1] + "?mode=ro")) {
    for (String s : new String[]{"BUY", "SELL"}) { List<Double> o = new ArrayList<>();
      ResultSet r = c.createStatement().executeQuery("select price, fair from exec_fill where upper(side)='" + s + "' and fair > 0");
      while (r.next()) { double px = r.getDouble(1), f = r.getDouble(2); o.add(s.equals("BUY") ? (f - px) / f * 1e4 : (px - f) / f * 1e4); }
      Collections.sort(o); int m = o.size(); if (m == 0) continue;
      System.out.printf(Locale.ROOT, "стенд %s %s: %d сделок, отступ мед %.1f (25%% %.1f, 75%% %.1f)%n", lp[0], s.toLowerCase(), m, o.get(m / 2), o.get(m / 4), o.get(3 * m / 4)); } } } } }
