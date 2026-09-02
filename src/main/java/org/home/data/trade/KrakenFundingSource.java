package org.home.data.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Live-оценка фондирования для фильтра дорогого шорта.
 *
 * Фондирование Kraken — ЧАСОВОЕ, срок удержания S5 — пять суток, поэтому оценка
 * есть относительная ставка, умноженная на 120 часов. Знак по конвенции проекта
 * (док. 55 §3.2): шорт получает фондирование при положительной ставке, дорогой
 * шорт — это сумма ниже −1.5%.
 *
 * <b>Оценка берётся по СУТОЧНОМУ среднему, а не по мгновенной ставке</b>
 * (док. 141 §1). Прежняя версия читала текущую ставку из {@code tickers} и
 * ссылалась на {@code corr 0.812} — но эта величина измерена на ВОСЬМИЧАСОВОМ
 * фондировании Binance и на Kraken не проверялась: коллектор собирал два
 * символа из 274. После бэкфилла всей вселенной (2.25 млн часовых ставок)
 * измерено прямо:
 *
 * <pre>
 * мгновенная ставка → факт за 120 ч:  corr 0.317   (R² 0.100)
 * среднее за 8 ч                      corr 0.372
 * среднее за 24 ч                     corr 0.385   (R² 0.148)  ← максимум
 * среднее за 168 ч                    corr 0.327
 * </pre>
 *
 * Суточное окно — оптимум: часовая ставка слишком шумна, недельная уже
 * запаздывает. Полтора раза по R² — весь выигрыш, который здесь есть, и он
 * стоит одного лишнего запроса.
 *
 * <b>Почему фильтр всё равно полезен при R² = 0.15.</b> Не потому, что стоимость
 * предсказуема, а потому что её распределение с толстым хвостом: медиана
 * −0.10%, первый процентиль −7.9% (док. 141 §2). Один процент окон стоит дороже
 * всей премии события, и даже грубый признак ловит этот край: отсечённые окна
 * стоят в среднем −1.355% против +0.121% у пропущенных.
 *
 * <b>Отказ безопасен.</b> Если история недоступна, оценка падает обратно на
 * мгновенную ставку из {@code tickers}, а если нет и её — возвращается ноль,
 * то есть фильтр не срабатывает. Пропустить дорогое событие хуже, чем
 * остановить торговлю совсем, но молча ошибиться в другую сторону — хуже обоих.
 */
public class KrakenFundingSource implements FundingSource {

    private static final ObjectMapper M = new ObjectMapper();
    /** Пять суток часового фондирования. */
    private static final int HORIZON_HOURS = 120;
    /** Окно сглаживания оценки: суточное среднее максимизирует corr (док. 141 §1). */
    private static final int SMOOTH_HOURS = 24;

    private final KrakenApi api;

    public KrakenFundingSource(KrakenApi api) { this.api = api; }

    @Override
    public double estimate5dFunding(String base) throws Exception {
        String symbol = perp(base);
        Double smoothed = fromHistory(symbol);
        return smoothed != null ? smoothed : fromTicker(symbol);
    }

    static String perp(String base) {
        return "PF_" + (base.equalsIgnoreCase("BTC") ? "XBT" : base.toUpperCase()) + "USD";
    }

    /**
     * Среднее относительной ставки за последние сутки, приведённое к горизонту.
     *
     * {@code null} означает «истории нет» — это сигнал перейти к запасному пути,
     * а не «фондирование нулевое». Смешивать эти два случая нельзя: ноль
     * выключает фильтр, и сделать это молча означало бы открыть позицию там, где
     * мы как раз не знаем цену удержания.
     */
    private Double fromHistory(String symbol) {
        try {
            JsonNode rates = M.readTree(
                    api.get("/api/v4/historicalfundingrates?symbol=" + symbol, false)).path("rates");
            if (!rates.isArray() || rates.isEmpty()) {
                return null;
            }
            double sum = 0;
            int n = 0;
            for (int i = rates.size() - 1; i >= 0 && n < SMOOTH_HOURS; i--) {
                JsonNode node = rates.get(i);
                if (node.hasNonNull("relativeFundingRate")) {
                    sum += node.path("relativeFundingRate").asDouble();
                    n++;
                }
            }
            return n == 0 ? null : sum / n * HORIZON_HOURS;
        } catch (Exception e) {
            return null;                  // сеть или формат — уходим на запасной путь
        }
    }

    /** Запасной путь: мгновенная ставка из tickers, как было до док. 141. */
    private double fromTicker(String symbol) throws Exception {
        for (JsonNode t : M.readTree(api.get("/api/v3/tickers", false)).path("tickers")) {
            if (!symbol.equalsIgnoreCase(t.path("symbol").asText())) continue;
            double mark = t.path("markPrice").asDouble(t.path("last").asDouble(0));
            double rate = t.path("fundingRate").asDouble(0);
            if (mark <= 0) return 0.0;
            return (rate / mark) * HORIZON_HOURS;
        }
        return 0.0;   // нет перпа в tickers — нейтрально, фильтр не срабатывает
    }
}
