package org.home.data.theory.compliance;

import org.home.data.trade.S5Config;
import org.home.data.trade.UnlockFeed;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Сверка живых констант S5 со спецификацией (ТЗ 72 §5).
 *
 * <p>Задача не «проверить ещё пять чисел», а сделать так, чтобы расхождение кода
 * и спецификации <b>не могло висеть незамеченным</b>: расхождение стопа (30% в
 * коде против 8% в док. 02 v2) прожило месяц и нашлось случайно, потому что о нём
 * спросили.
 *
 * <p>Реестр значений спецификации лежит в {@code s5-spec.tsv} и читается и
 * отчётом, и автотестом — одна таблица, два потребителя. Зарегистрированное
 * расхождение (status {@code known}) фиксирует <b>обе</b> стороны: изменится код
 * или изменится строка спецификации — тест упадёт и потребует решения.
 *
 * <p>Модуль ничего не исправляет: конфигурация идущего микро-live не меняется
 * (правило одного изменения).
 */
public final class S5SpecCheck {

    private static final String RESOURCE = "s5-spec.tsv";

    private S5SpecCheck() {
    }

    /** Строка реестра плюс фактическое значение из кода. */
    public record Row(String key, String specValue, String specSource, String status,
                      String expectedCode, String note, String codeValue, String codeLocation) {

        /** Совпадает ли код со спецификацией (для {@code info} — неприменимо). */
        public boolean matches() {
            return "info".equals(status) || equalsNumeric(specValue, codeValue);
        }

        /** Расхождение зарегистрировано и выглядит ровно так, как записано. */
        public boolean asRegistered() {
            return "known".equals(status)
                    && equalsNumeric(expectedCode, codeValue)
                    && !equalsNumeric(specValue, codeValue);
        }
    }

    /** Живое значение константы и место, где оно применяется. */
    private record Live(String value, String location) {
    }

    /**
     * Фактические значения из живого пути S5. Читаются из тех же классов, что
     * работают в проде: {@link S5Config#protocol()}, {@link UnlockFeed#MIN_PCT}.
     */
    private static Map<String, Live> liveValues() {
        S5Config cfg = S5Config.protocol();
        Map<String, Live> live = new LinkedHashMap<>();
        live.put("entry_lead", new Live(String.valueOf(cfg.entryLead()),
                "S5Config.java:13 → S5Orchestrator:141 (вход на unlockDay−entryLead)"));
        live.put("min_pct_supply", new Live(String.valueOf(UnlockFeed.MIN_PCT),
                "UnlockFeed.java:19 → UnlockFeed:115 (отсев события)"));
        live.put("expensive_funding_threshold", new Live(String.valueOf(cfg.expensiveFundingThreshold()),
                "S5Config.java:13 → S5Orchestrator:88 (пропуск дорогого шорта)"));
        live.put("exit_on_unlock_day", new Live("1",
                "S5Orchestrator:209 (плановый выход при unlockDay ≤ today)"));
        live.put("position_fraction", new Live(String.valueOf(cfg.positionFraction()),
                "S5Config.java:13 → S5Orchestrator:112 (размер позиции)"));
        // Лимита на ЧИСЛО одновременных позиций в коде нет: ограничение идёт по
        // суммарной экспозиции, поэтому эффективный потолок — это отношение.
        live.put("max_concurrent", new Live(String.valueOf((int) Math.floor(cfg.maxExposure()
                        / Math.max(cfg.positionFraction(), 1e-12))),
                "S5Orchestrator:256 (wouldBreachExposure: maxExposure/positionFraction)"));
        live.put("stop_frac", new Live(String.valueOf(cfg.stopFrac()),
                "S5Config.java:13 → StopEngine:55,59 (уровень стопа и биржевой stop-buy)"));
        // Категория получателя вычисляется, но в отборе не участвует — это и есть
        // «фильтра нет», а не «фильтр есть и пропускает всех».
        live.put("recipient_filter", new Live("0",
                "UnlockFeed.classify считает категорию; S5Orchestrator её только показывает"));
        live.put("approval_lead_days", new Live(String.valueOf(cfg.approvalLeadDays()),
                "S5Config.java:13 → S5Orchestrator:83 (окно всплытия кандидата)"));
        return live;
    }

    /** Реестр вместе с фактическими значениями. */
    public static List<Row> rows() {
        Map<String, Live> live = liveValues();
        List<Row> out = new ArrayList<>();
        for (String[] f : readRegistry()) {
            Live actual = live.get(f[0]);
            if (actual == null) {
                throw new IllegalStateException("в реестре " + RESOURCE + " есть ключ «" + f[0]
                        + "», которому не сопоставлено значение из кода — сверять не с чем");
            }
            out.add(new Row(f[0], f[1], f[2], f[3], f[4], f[5], actual.value(), actual.location()));
        }
        for (String key : live.keySet()) {
            if (out.stream().noneMatch(r -> r.key().equals(key))) {
                throw new IllegalStateException("константа «" + key + "» есть в коде, но её нет в реестре "
                        + RESOURCE + " — добавить строку со ссылкой на спецификацию");
            }
        }
        return out;
    }

    /** Расхождения, которых нет в реестре: их появление обязано ломать тест. */
    public static List<Row> unregisteredDivergences() {
        return rows().stream()
                .filter(r -> !"info".equals(r.status()))
                .filter(r -> "ok".equals(r.status()) ? !r.matches() : !r.asRegistered())
                .toList();
    }

    /** Зарегистрированные расхождения — то, что ждёт решения «править код или спецификацию». */
    public static List<Row> registeredDivergences() {
        return rows().stream().filter(r -> "known".equals(r.status())).toList();
    }

    private static List<String[]> readRegistry() {
        try (var in = new ClassPathResource(RESOURCE).getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<String[]> rows = new ArrayList<>();
            for (String line : text.split("\n")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("key\t")) {
                    continue;
                }
                String[] fields = trimmed.split("\t");
                if (fields.length < 6) {
                    throw new IllegalStateException("строка реестра короче шести полей: " + trimmed);
                }
                rows.add(fields);
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException("не читается " + RESOURCE, e);
        }
    }

    /** Числа сравниваются как числа («0.30» и «0.3» — одно и то же), прочее — как текст. */
    private static boolean equalsNumeric(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return Math.abs(Double.parseDouble(a) - Double.parseDouble(b)) < 1e-9;
        } catch (NumberFormatException e) {
            return a.equals(b);
        }
    }
}
