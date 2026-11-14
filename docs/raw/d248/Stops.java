import java.sql.*;
public class Stops { public static void main(String[] a) throws Exception { for (String p : a) { try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + p + "?mode=ro")) {
  ResultSet r = c.createStatement().executeQuery("select kind, ts_ms from exec_event where kind in ('loss_stop','stop','limit_blocked','dd_stop') order by ts_ms");
  StringBuilder s = new StringBuilder(p.replaceAll(".*j-w0714-dNF100000r0.95H", "").replace("-ETH/bot-", " ") + ":");
  while (r.next()) s.append(' ').append(r.getString(1)).append('@').append(java.time.Instant.ofEpochMilli(r.getLong(2)).toString().substring(5, 16));
  System.out.println(s); } } } }
