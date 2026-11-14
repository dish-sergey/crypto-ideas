import java.sql.*;
// Постановки (placed) по сторонам и в сутки. Аргументы: метка=журнал …
public class Pl { public static void main(String[] a) throws Exception { for (String x : a) { String[] lp = x.split("=", 2);
  try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + lp[1] + "?mode=ro")) {
    ResultSet r = c.createStatement().executeQuery("select count(*), sum(detail like 'buy%'), sum(detail like 'sell%'), (max(ts_ms)-min(ts_ms))/86400000.0 from exec_event where kind='placed'");
    r.next(); double d = Math.max(r.getDouble(4), 0.01);
    System.out.printf(java.util.Locale.ROOT, "%s: постановок %d (%.0f/сут): покупка %d, продажа %d%n", lp[0], r.getLong(1), r.getLong(1) / d, r.getLong(2), r.getLong(3)); } } } }
