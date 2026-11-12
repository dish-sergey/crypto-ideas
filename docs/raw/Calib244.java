import java.nio.file.*;
import java.sql.*;
import java.util.*;

// Док. 244, этап 7: калибровка шанса снятия кривой по исполнениям стенда.
// Стенд: заявки одиночных прогонов базы (exec_open_order) — расстояние от опоры в момент постановки (exec_quote.fair), сколько
// минут стояла, исполнилась ли (status filled). Интенсивность h_stand(δ) = исполнений / минут стояния, по корзинам сетки δ.
// Мысленная заявка (fpvr тех же окон): доля снятых за 5 мин P5(δ) → интенсивность h_mental = −ln(1 − P5) / 5 в минуту.
// Множитель c(δ) = h_stand / h_mental — во сколько раз реальная заявка снимается чаще мысленной. По монете и стороне.
// Аргументы: <каталог журналов hp> <окна через запятую> <каталоги fpvr через запятую>
public class Calib244 {
    static final int[] DL = {2, 4, 6, 8, 10, 12, 14, 16, 20, 25, 30, 35, 40, 50, 60};
    static final String[][] BOTS = {{"BTC", "a", "acb"}, {"BTC", "e", "edf"}, {"ETH", "c", "acb"}, {"ETH", "d", "edf"}, {"SOL", "b", "acb"}, {"SOL", "f", "edf"}};

    public static void main(String[] a) throws Exception {
        Class.forName("org.sqlite.JDBC");
        StringBuilder csv = new StringBuilder("# монета;сторона;δ;c — множитель интенсивности снятия (стенд / мысленная)\n");
        for (String coin : new String[]{"BTC", "ETH", "SOL"}) {
            double[][] mins = new double[2][DL.length], fills = new double[2][DL.length];
            for (String w : a[1].split(",")) for (String[] b : BOTS) {
                if (!b[0].equals(coin)) continue;
                String db = a[0] + "/j-" + w + "-dNF100000r0.95Hv5allo" + b[2] + "n-" + coin + "/bot-" + b[1] + ".db";
                if (!Files.exists(Path.of(db))) { System.err.println("нет " + db); continue; }
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + db + "?mode=ro")) {
                    TreeMap<Long, Double> fair = new TreeMap<>();
                    try (ResultSet r = c.createStatement().executeQuery("select ts_ms, fair from exec_quote where fair > 0")) { while (r.next()) fair.put(r.getLong(1), r.getDouble(2)); }
                    try (ResultSet r = c.createStatement().executeQuery("select upper(side), price, opened_ms, closed_ms, status from exec_open_order where closed_ms is not null")) {
                        while (r.next()) {
                            var e = fair.floorEntry(r.getLong(3)); if (e == null) continue;
                            int sd = r.getString(1).equals("BUY") ? 0 : 1; double f = e.getValue(), px = r.getDouble(2);
                            double d = (sd == 0 ? f - px : px - f) / f * 1e4;
                            if (d < 1 || d > 65) continue;
                            int k = nearest(d);
                            double m = (r.getLong(4) - r.getLong(3)) / 60000.0;
                            mins[sd][k] += m; if ("filled".equals(r.getString(5))) fills[sd][k]++;
                        }
                    }
                }
            }
            long[][] nP = new long[2][DL.length], nF = new long[2][DL.length];
            for (String dir : a[2].split(",")) for (String l : Files.readAllLines(Path.of(dir, "fpvr-" + coin + ".csv"))) {
                String[] p = l.split(";"); int sd = Integer.parseInt(p[1]);
                for (int k = 0; k < DL.length; k++) { int o = 8 + 5 * k; if (p[o].equals("NaN")) continue; nP[sd][k]++; if (p[o + 2].equals("1")) nF[sd][k]++; }
            }
            System.out.printf("%n######## %s: δ | стенд: минут стояния, исполнений, в час | мысленная: P5, в час | множитель c%n", coin);
            for (int sd = 0; sd < 2; sd++) for (int k = 0; k < DL.length; k++) {
                double hs = mins[sd][k] > 0 ? fills[sd][k] / mins[sd][k] : Double.NaN;
                double p5 = nP[sd][k] > 0 ? (double) nF[sd][k] / nP[sd][k] : Double.NaN;
                double hm = p5 > 0 && p5 < 1 ? -Math.log(1 - p5) / 5 : Double.NaN;
                double cc = hs / hm;
                System.out.printf(Locale.ROOT, "   %s %2d | %9.0f %6.0f %7.3f | %.4f %7.3f | %s%n", sd == 0 ? "БИД" : "АСК", DL[k], mins[sd][k], fills[sd][k], 60 * hs, p5, 60 * hm,
                        fills[sd][k] >= 20 && Double.isFinite(cc) ? String.format(Locale.ROOT, "%.2f", cc) : "— (мало)");
                if (fills[sd][k] >= 20 && Double.isFinite(cc)) csv.append(String.format(Locale.ROOT, "%s;%s;%d;%.4f%n", coin, sd == 0 ? "BUY" : "SELL", DL[k], cc));
            }
        }
        Files.writeString(Path.of("calib244.csv"), csv.toString());
    }

    static int nearest(double d) { int b = 0; for (int k = 1; k < DL.length; k++) if (Math.abs(DL[k] - d) < Math.abs(DL[b] - d)) b = k; return b; }
}
