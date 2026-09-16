package org.home.data.revx.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code --revx-unbook}: убрать ПОВТОРНО записанное исполнение.
 *
 * <h2>Когда нужно</h2>
 *
 * 16.09.2026 (задача A56) продажа {@code f2430d44} записалась дважды — живым
 * процессом и повторно восстановлением после перезапуска, — и позиция спотового
 * бота {@code a} ушла в МИНУС: −2.51e-5 BTC у бота, который шортить не умеет.
 *
 * Причина исправлена (промах по карте учтённого переспрашивается у журнала), но
 * уже записанный дубликат сам не исчезнет, а санкционированного пути его убрать
 * не было: {@code /release} пропускает отрицательную претензию
 * ({@code if (qty > 0)}), {@code --revx-release-stale} тоже
 * ({@code if (own <= 0) return}). И это не недосмотр — обе команды писались с
 * расчётом, что минуса не бывает.
 *
 * <h2>Почему это не «правка истории»</h2>
 *
 * Убирается не событие, а ЛИШНЯЯ ЗАПИСЬ о событии, которое случилось один раз.
 * Журнал остаётся истиной; истиной он быть перестаёт ровно тогда, когда в нём
 * одна сделка лежит дважды.
 *
 * ⚠️ Реестр правится через СВОЙ ЖЕ API ({@link AllocRegistry#applyFill}), а не
 * запросом к {@code alloc.db}. Удаление дубликата — это в точности обратная
 * проводка: продажа, которой не было, возвращает монету владельцу.
 *
 * <h2>Предохранители</h2>
 *
 * <ul>
 *   <li>без {@code --apply} команда только ПОКАЗЫВАЕТ, что нашла;</li>
 *   <li>трогаются лишь заявки, записанные более одного раза: одиночную запись
 *       команда не удалит, сколько её ни проси;</li>
 *   <li>частичные исполнения не считаются дубликатом по построению — они и
 *       должны лежать несколькими строками, поэтому лишней признаётся только
 *       строка, ПОВТОРЯЮЩАЯ объём предыдущей.</li>
 * </ul>
 */
@Component
@Lazy
public class Unbook {

    private static final Logger log = LoggerFactory.getLogger(Unbook.class);

    private final String defaultAlloc;

    public Unbook(@Value("${revx.exec.alloc}") String defaultAlloc) {
        this.defaultAlloc = defaultAlloc;
    }

    private record Dup(long tsMs, String venueId, String side, double qty) { }

    public void run(String journalPath, String botId, String currency,
                    String allocOverride, String fromIso, boolean apply) {
        if (journalPath == null || journalPath.isBlank()) {
            log.error("нужен --journal=<путь>");
            return;
        }
        long since = 0;
        if (fromIso != null && !fromIso.isBlank()) {
            since = java.time.Instant.parse(fromIso.trim()).toEpochMilli();
        }
        // 🔑 ГРАНИЦА ПО ВРЕМЕНИ ОБЯЗАТЕЛЬНА ДЛЯ ПРИМЕНЕНИЯ, и это не формальность.
        // 16.09.2026 команда нашла у бота `a` ТРИ дубликата — от 07.09, 15.09 и
        // сегодняшний. Но позицию обнулял `/release` в 13:58, и старые два уже
        // смыты: их исправление увело бы претензию ВЫШЕ, чем монет на счёте.
        // Считать можно только то, что случилось после последнего обнуления.
        if (apply && since <= 0) {
            log.error("для --apply нужен --from=<ISO>: момент последнего обнуления позиции "
                    + "(/release или /claim). Без него применились бы и старые дубликаты, "
                    + "уже смытые обнулением, и претензия превысила бы остаток счёта");
            return;
        }
        List<Dup> dups = duplicates(journalPath, since);
        if (dups.isEmpty()) {
            log.info("дубликатов исполнений в {} нет", journalPath);
            return;
        }
        double delta = 0;
        for (Dup d : dups) {
            double back = "SELL".equalsIgnoreCase(d.side()) ? d.qty() : -d.qty();
            delta += back;
            log.warn("лишняя запись: {} {} {} в {} — вернёт позиции {}",
                    d.side(), fmt(d.qty()), d.venueId(),
                    java.time.Instant.ofEpochMilli(d.tsMs()), fmt(back));
        }
        log.warn("итого дубликатов {}, позиция изменится на {}", dups.size(), fmt(delta));
        if (!apply) {
            log.warn("это показ; чтобы применить — добавь --apply");
            return;
        }
        if (botId == null || botId.isBlank() || currency == null || currency.isBlank()) {
            log.error("для --apply нужны --bot=<метка> и --currency=<монета>: "
                    + "без них не поправить реестр, а журнал один без реестра чинить нельзя");
            return;
        }

        try (ExecJournal journal = new ExecJournal(journalPath)) {
            Double position = journal.getState("position");
            if (position == null) {
                log.error("в журнале нет записи position — не за что зацепиться");
                return;
            }
            for (Dup d : dups) {
                journal.deleteFill(d.venueId(), d.tsMs());
            }
            double fixed = position + delta;
            journal.putState("position", fixed);
            journal.event("unbook", String.format(Locale.ROOT,
                    "убрано дубликатов %d, позиция %s → %s", dups.size(), fmt(position), fmt(fixed)));
            log.warn("журнал: позиция {} → {}", fmt(position), fmt(fixed));

            String alloc = allocOverride == null || allocOverride.isBlank()
                    ? defaultAlloc : allocOverride;
            String coin = currency.trim().toUpperCase(Locale.ROOT);
            try (AllocRegistry registry = new AllocRegistry(alloc)) {
                double before = registry.own(botId, coin);
                registry.applyFill(botId, coin, delta, System.currentTimeMillis());
                log.warn("реестр: за {} числилось {} {}, стало {}",
                        botId, fmt(before), coin, fmt(registry.own(botId, coin)));
            }
        }
    }

    /**
     * Заявки, по которым одна и та же величина записана повторно.
     *
     * ⚠️ Частичное исполнение тоже даёт несколько строк по одной заявке, и
     * трогать их нельзя. Отличие в том, что у частичных объёмы РАЗНЫЕ и в сумме
     * дают размер заявки, а у дубликата вторая строка повторяет первую. Поэтому
     * лишней признаётся только строка с тем же объёмом и той же стороной.
     */
    private List<Dup> duplicates(String journalPath, long sinceMs) {
        List<Dup> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(
                "jdbc:sqlite:file:" + journalPath + "?mode=ro");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT venue_id, side, qty, min(ts_ms) AS first_ts, max(ts_ms) AS last_ts,"
                             + " count(*) AS n FROM exec_fill"
                             + " WHERE venue_id IS NOT NULL AND ts_ms >= " + sinceMs
                             + " GROUP BY venue_id, side, qty HAVING n > 1")) {
            while (rs.next()) {
                int n = rs.getInt("n");
                // Оставляем ПЕРВУЮ запись, лишними считаем последующие.
                for (int i = 1; i < n; i++) {
                    out.add(new Dup(rs.getLong("last_ts"), rs.getString("venue_id"),
                            rs.getString("side"), rs.getDouble("qty")));
                }
            }
        } catch (Exception e) {
            log.error("не прочитался журнал {}: {}", journalPath, e.toString());
        }
        return out;
    }

    private static String fmt(double v) {
        return java.math.BigDecimal.valueOf(v).setScale(10, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }
}
