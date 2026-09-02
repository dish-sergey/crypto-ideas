package org.home.data.revx.sim;

import org.home.data.revx.RevxDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Зеркальное падение: из окна роста делается окно падения той же формы.
 *
 * <b>Зачем.</b> Настоящих длинных падающих окон в собранных данных мало, а
 * поведение конструкции на падении — главный вопрос всей линии (доки 106–122).
 * Зеркало даёт падение с теми же микроструктурными свойствами: тот же поток,
 * те же спреды, та же плотность снимков. Преобразование
 * {@code p' = p₀²/p} меняет знак логдоходностей ТОЧНО: рост на +16.45%
 * становится падением на −14.12% (док. 95).
 *
 * <b>Что было сломано.</b> Первое зеркало делалось по паре независимо, и это
 * рушило корзину: справедливая цена считается из ВСЕЙ вселенной через
 * implied-курс USDC/USD (ТЗ §4.6), а после независимого зеркалирования
 * согласованность пар исчезала. Гейт отбраковывал **93.3% окон** против 17.3%
 * до зеркалирования, и все выводы про падение (доки 106–108, 115–117, 119, 122)
 * посчитаны на оставшихся 6.7% — то есть на отборе, а не на выборке
 * (док. 125 §3). Пункт «починить зеркало» с тех пор дважды выпадал из очередей
 * (док. 127 §15 п.9, док. 134 §7).
 *
 * <b>Как починено.</b> Обе ноги пары отражаются ОДНИМ якорем — первой
 * долларовой ценой актива в окне:
 *
 * <pre>
 * p_usd'  = p₀² / p_usd
 * p_usdc' = p₀² / p_usdc      ← тот же p₀, а не собственный якорь ноги
 * </pre>
 *
 * Отсюда implied-курс пары {@code r = p_usdc/p_usd} переходит в {@code 1/r}.
 * Он не сохраняется — но переходит в {@code 1/r} У ВСЕХ ПАР ОДИНАКОВО, и
 * именно это чинит корзину: {@link FairPrice} берёт из вселенной ЦЕНТРАЛЬНЫЙ
 * курс и проверяет РАЗБРОС вокруг него, а обе величины при {@code r ↦ 1/r}
 * сохраняются с точностью до знака отклонения (курс около единицы, поэтому
 * {@code 1/r ≈ 2 − r}). Сломанное зеркало брало для каждой ноги свой якорь, и
 * тогда каждая пара уезжала по-своему — согласованность вселенной исчезала, а
 * с ней и справедливая цена.
 *
 * Приёмка у этого одна и она прямая: **доля отбракованных гейтом окон в зеркале
 * обязана совпасть с долей в исходном окне.** Было 93.3% против 17.3%.
 *
 * <b>Стороны книги меняются местами.</b> Отображение {@code p ↦ c/p} убывающее:
 * лучший бид (высокая цена) переходит в низкую, то есть становится аском.
 * Поэтому уровни зеркалятся с перестановкой сторон и обратным порядком, иначе
 * книга выходит перекрещенной и коллектор её же и отбраковывает.
 *
 * <b>Чего зеркало НЕ делает</b> (ограничение осознанное, док. 95): объёмы и
 * поток переносятся как есть. Мир, где цена падала, а покупали в нём столько же,
 * сколько покупали на росте, — приближение. Поэтому зеркало годится для
 * сравнения КОНФИГУРАЦИЙ между собой на одинаковых данных и не годится как
 * прогноз доходности на настоящем падении.
 */
@Component
@Lazy
public class MirrorBuilder {

    private static final Logger log = LoggerFactory.getLogger(MirrorBuilder.class);

    private static final int LEVELS = 5;

    private final RevxDb db;

    public MirrorBuilder(RevxDb db) {
        this.db = db;
    }

    /** Якоря отражения: цена базового актива в долларах на начало окна. */
    private record Anchor(String base, double p0Usd) {
    }

    public void run(long fromMs, long toMs, String outPath) {
        Map<String, Anchor> anchors = anchors(fromMs, toMs);
        if (anchors.isEmpty()) {
            log.warn("в окне нет ни одной пары с обеими ногами — зеркалить нечего");
            return;
        }
        try {
            Path out = Path.of(outPath);
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.deleteIfExists(out);
            Files.deleteIfExists(Path.of(outPath + "-wal"));
            Files.deleteIfExists(Path.of(outPath + "-shm"));
        } catch (Exception e) {
            throw new IllegalStateException("не подготовить файл " + outPath, e);
        }

        try (Connection dst = DriverManager.getConnection("jdbc:sqlite:" + outPath)) {
            try (Statement st = dst.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=OFF");
            }
            org.home.data.core.Db.applySchema(dst, schema());
            dst.setAutoCommit(false);
            int books = mirrorBooks(dst, anchors, fromMs, toMs);
            int trades = mirrorTrades(dst, anchors, fromMs, toMs);
            copyPairs(dst);
            dst.commit();
            log.info("зеркало готово: {} книг, {} сделок, {} пар → {}",
                    books, trades, anchors.size(), outPath);
        } catch (Exception e) {
            throw new IllegalStateException("зеркало не собрано: " + e.getMessage(), e);
        }
    }

    /**
     * Якорь на пару — ПЕРВАЯ долларовая цена в окне.
     *
     * Первая, а не средняя: отражение обязано оставить начало окна на месте,
     * иначе зеркало ещё и сдвигает уровень цены, и сравнивать его с исходником
     * становится нельзя.
     */
    private Map<String, Anchor> anchors(long fromMs, long toMs) {
        Map<String, Anchor> out = new LinkedHashMap<>();
        List<Object[]> rows = db.query(
                "SELECT symbol, MIN(t_recv_ms), bp1, ap1 FROM revx_book "
                        + "WHERE leg='usd' AND t_recv_ms BETWEEN ? AND ? AND bp1 > 0 AND ap1 > 0 "
                        + "GROUP BY symbol",
                rs -> new Object[]{rs.getString(1), rs.getDouble(3), rs.getDouble(4)},
                fromMs, toMs);
        for (Object[] r : rows) {
            String symbol = (String) r[0];
            String base = symbol.contains("/") ? symbol.substring(0, symbol.indexOf('/')) : symbol;
            double mid = ((double) r[1] + (double) r[2]) / 2;
            if (mid > 0) {
                out.put(base, new Anchor(base, mid));
            }
        }
        return out;
    }

    private int mirrorBooks(Connection dst, Map<String, Anchor> anchors, long fromMs, long toMs) {
        String cols = "symbol,t_sent_ms,snap_id,leg,t_recv_ms,server_ts_ms,skew_ms,flags,"
                + "bp1,bq1,bp2,bq2,bp3,bq3,bp4,bq4,bp5,bq5,"
                + "ap1,aq1,ap2,aq2,ap3,aq3,ap4,aq4,ap5,aq5,n_bid,n_ask";
        int[] written = {0};
        try (PreparedStatement ins = dst.prepareStatement(
                "INSERT OR REPLACE INTO revx_book(" + cols + ") VALUES ("
                        + "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            db.query("SELECT " + cols + " FROM revx_book WHERE t_recv_ms BETWEEN ? AND ? "
                    + "ORDER BY t_recv_ms", rs -> {
                try {
                    String symbol = rs.getString("symbol");
                    String leg = rs.getString("leg");
                    String base = symbol.contains("/")
                            ? symbol.substring(0, symbol.indexOf('/')) : symbol;
                    Anchor a = anchors.get(base);
                    if (a == null) {
                        return null;                     // без опоры отражать не от чего
                    }
                    double[] bp = new double[LEVELS];
                    double[] bq = new double[LEVELS];
                    double[] ap = new double[LEVELS];
                    double[] aq = new double[LEVELS];
                    for (int i = 0; i < LEVELS; i++) {
                        bp[i] = rs.getDouble("bp" + (i + 1));
                        bq[i] = rs.getDouble("bq" + (i + 1));
                        ap[i] = rs.getDouble("ap" + (i + 1));
                        aq[i] = rs.getDouble("aq" + (i + 1));
                    }
                    // Мгновенный курс пары сохраняется: он берётся из ЭТОЙ же
                    // строки, если это USDC-нога, и равен единице для USD-ноги.
                    double factor = a.p0Usd() * a.p0Usd();
                    // Отражение p ↦ factor/p убывающее, поэтому биды становятся
                    // асками и порядок уровней переворачивается.
                    double[] nbp = new double[LEVELS];
                    double[] nbq = new double[LEVELS];
                    double[] nap = new double[LEVELS];
                    double[] naq = new double[LEVELS];
                    int nb = 0;
                    int na = 0;
                    for (int i = LEVELS - 1; i >= 0; i--) {
                        if (ap[i] > 0 && aq[i] > 0) {
                            nbp[nb] = mirror(ap[i], factor);
                            nbq[nb] = aq[i];
                            nb++;
                        }
                    }
                    for (int i = LEVELS - 1; i >= 0; i--) {
                        if (bp[i] > 0 && bq[i] > 0) {
                            nap[na] = mirror(bp[i], factor);
                            naq[na] = bq[i];
                            na++;
                        }
                    }
                    if (nb == 0 || na == 0) {
                        return null;
                    }
                    int c = 1;
                    ins.setString(c++, symbol);
                    ins.setLong(c++, rs.getLong("t_sent_ms"));
                    ins.setLong(c++, rs.getLong("snap_id"));
                    ins.setString(c++, leg);
                    ins.setLong(c++, rs.getLong("t_recv_ms"));
                    ins.setLong(c++, rs.getLong("server_ts_ms"));
                    ins.setLong(c++, rs.getLong("skew_ms"));
                    ins.setInt(c++, rs.getInt("flags"));
                    for (int i = 0; i < LEVELS; i++) {
                        setOrNull(ins, c++, nbp[i]);
                        setOrNull(ins, c++, nbq[i]);
                    }
                    for (int i = 0; i < LEVELS; i++) {
                        setOrNull(ins, c++, nap[i]);
                        setOrNull(ins, c++, naq[i]);
                    }
                    ins.setInt(c++, nb);
                    ins.setInt(c, na);
                    ins.addBatch();
                    if (++written[0] % 20_000 == 0) {
                        ins.executeBatch();
                    }
                } catch (Exception e) {
                    throw new IllegalStateException("строка книги не зеркалится", e);
                }
                return null;
            }, fromMs, toMs);
            ins.executeBatch();
        } catch (Exception e) {
            throw new IllegalStateException("книги не зеркалятся: " + e.getMessage(), e);
        }
        return written[0];
    }

    /**
     * Цена в зеркале: {@code p ↦ p₀²/p}.
     *
     * ⚠️ Якорь {@code p₀} — ОДИН на пару, общий для обеих ног. Собственный якорь
     * у каждой ноги и был причиной поломки: тогда пары уезжают друг относительно
     * друга и вселенная перестаёт быть согласованной.
     */
    private static double mirror(double price, double factor) {
        return price > 0 ? factor / price : 0;
    }

    private static void setOrNull(PreparedStatement ps, int idx, double v) throws java.sql.SQLException {
        if (v > 0) {
            ps.setDouble(idx, v);
        } else {
            ps.setNull(idx, java.sql.Types.REAL);
        }
    }

    /**
     * Сделки: цена отражается тем же якорем, сторона МЕНЯЕТСЯ.
     *
     * Сделка, снявшая аск (покупка), в зеркальном мире происходит по цене ниже
     * середины, то есть становится продажей. Не поменяв сторону, мы получили бы
     * ленту, в которой покупки идут по низким ценам, — и весь расчёт
     * неблагоприятного отбора поехал бы со знаком.
     */
    private int mirrorTrades(Connection dst, Map<String, Anchor> anchors, long fromMs, long toMs) {
        int[] written = {0};
        try (PreparedStatement ins = dst.prepareStatement(
                "INSERT OR REPLACE INTO revx_trade(trade_id,symbol,ts_ms,price,qty,side,ingest_ms) "
                        + "VALUES (?,?,?,?,?,?,?)")) {
            db.query("SELECT trade_id,symbol,ts_ms,price,qty,side,ingest_ms FROM revx_trade "
                    + "WHERE ts_ms BETWEEN ? AND ? ORDER BY ts_ms", rs -> {
                try {
                    String symbol = rs.getString(2);
                    String base = symbol.contains("/")
                            ? symbol.substring(0, symbol.indexOf('/')) : symbol;
                    Anchor a = anchors.get(base);
                    double price = rs.getDouble(4);
                    if (a == null || !(price > 0)) {
                        return null;
                    }
                    String side = rs.getString(6);
                    String flipped = "buy".equalsIgnoreCase(side) ? "sell"
                            : "sell".equalsIgnoreCase(side) ? "buy" : side;
                    ins.setString(1, rs.getString(1));
                    ins.setString(2, symbol);
                    ins.setLong(3, rs.getLong(3));
                    ins.setDouble(4, a.p0Usd() * a.p0Usd() / price);
                    ins.setDouble(5, rs.getDouble(5));
                    ins.setString(6, flipped);
                    ins.setLong(7, rs.getLong(7));
                    ins.addBatch();
                    if (++written[0] % 20_000 == 0) {
                        ins.executeBatch();
                    }
                } catch (Exception e) {
                    throw new IllegalStateException("строка ленты не зеркалится", e);
                }
                return null;
            }, fromMs, toMs);
            ins.executeBatch();
        } catch (Exception e) {
            throw new IllegalStateException("лента не зеркалится: " + e.getMessage(), e);
        }
        return written[0];
    }

    /** Каталог пар переносится как есть: шаги цены и количества от отражения не зависят. */
    private void copyPairs(Connection dst) {
        try (PreparedStatement ins = dst.prepareStatement(
                "INSERT OR REPLACE INTO revx_pair(symbol,base,quote,base_step,quote_step,"
                        + "min_order_size,max_order_size,min_order_size_quote,max_order_size_quote,"
                        + "status,first_seen_ms,last_seen_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            db.query("SELECT symbol,base,quote,base_step,quote_step,min_order_size,max_order_size,"
                    + "min_order_size_quote,max_order_size_quote,status,first_seen_ms,last_seen_ms "
                    + "FROM revx_pair", rs -> {
                try {
                    for (int i = 1; i <= 12; i++) {
                        ins.setObject(i, rs.getObject(i));
                    }
                    ins.addBatch();
                } catch (Exception e) {
                    throw new IllegalStateException("каталог пар не переносится", e);
                }
                return null;
            });
            ins.executeBatch();
        } catch (Exception e) {
            throw new IllegalStateException("каталог пар не перенесён: " + e.getMessage(), e);
        }
    }

    private static String schema() {
        try (var in = MirrorBuilder.class.getResourceAsStream("/schema-revx.sql")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("схема стенда не читается", e);
        }
    }
}
