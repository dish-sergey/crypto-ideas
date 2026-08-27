package org.home.data.revx.exec;

import org.home.data.revx.sim.Quoter;
import org.home.data.revx.sim.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Цикл котирования. Та же формула, что в симуляции ({@link Quoter}), те же
 * параметры из того же конфига — иначе сверять предсказание с фактом
 * бессмысленно.
 *
 * Цель этапа — НЕ прибыль, а одно число: доля исполнений, которые модель
 * предсказала (док. 91 §3). Предсказание для нашего размера и периода опроса
 * посчитано заранее: **104 исполнения в сутки** и **6.0% исполнений с
 * отрицательным захватом**. Поэтому размеры минимальные, а весь риск ограничен
 * пределами из {@link ExecLimits}.
 *
 * Три правила, без которых цикл опасен:
 *
 * 1. **Стартует остановленным.** Котирование начинается только по явной команде.
 *    Перезапуск после падения не должен сам возобновлять торговлю — сначала
 *    человек смотрит, почему упало.
 * 2. **Гейт справедливой цены соблюдается буквально.** Опора сломана или курс
 *    ненадёжен — заявки снимаются целиком, а не «оставим пока висеть».
 * 3. **Замена создаёт НОВУЮ заявку.** Площадка возвращает новый
 *    {@code venue_order_id}, и состояние читается из ответа, а не помнится
 *    (проверено живой заявкой 27.08.2026).
 */
public final class QuoteLoop implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(QuoteLoop.class);

    private static final Pattern VENUE_ID =
            Pattern.compile("\"venue_order_id\"\\s*:\\s*\"([^\"]+)\"");
    /**
     * Из остатков нужны ОБА числа, и путать их нельзя:
     * {@code available} — на что можно поставить новую заявку,
     * {@code total} — сколько мы на самом деле держим (включая зарезервированное
     * под уже стоящей заявкой).
     *
     * Скос считается от ПОЗИЦИИ, то есть от {@code total}: пока аск стоит, его
     * объём зарезервирован и из {@code available} исчезает, а позицией быть не
     * перестаёт. Если брать {@code available}, инвентарь занижается ровно на
     * размер стоящей заявки, скос выходит слабее задуманного, и живое перестаёт
     * совпадать с симуляцией — где инвентарь всегда полный.
     */
    private static final Pattern BALANCE = Pattern.compile(
            "\\{\"currency\":\"([A-Z0-9]+)\",\"available\":\"([0-9.]+)\",\"reserved\":\"([0-9.]+)\",\"total\":\"([0-9.]+)\"");

    /** Стоящая заявка: id площадки и цена, по которой она стоит. */
    private static final class Resting {
        String venueId;
        double price;
        double size;
    }

    public record Stats(long placements, long replaces, long cancels, long fills,
                        double inventory, double lastFair, String state, String pausedReason) {
    }

    private final TradeClient client;
    private final StandReader stand;
    private final ExecJournal journal;
    private final Quoter quoter;
    private final Quoter.Params params;
    private final String symbol;
    private final String base;
    private final long periodMs;

    private final AtomicBoolean quoting = new AtomicBoolean(false);
    private volatile boolean alive = true;

    private final Resting bid = new Resting();
    private final Resting ask = new Resting();

    private volatile double inventory;
    private volatile double quoteBalance;
    private volatile double lastFair;
    private volatile String pausedReason = "не запущен";
    private long placements;
    private long replaces;
    private long cancels;
    private long fills;
    private long dayStartMs = System.currentTimeMillis();
    private long minuteStartMs = System.currentTimeMillis();
    private int replacesThisMinute;

    public QuoteLoop(TradeClient client, StandReader stand, ExecJournal journal,
                     Quoter.Params params, String symbol, long periodMs) {
        this.client = client;
        this.stand = stand;
        this.journal = journal;
        this.params = params;
        this.quoter = new Quoter(params);
        this.symbol = symbol;
        this.base = symbol.substring(0, symbol.indexOf('/'));
        this.periodMs = periodMs;
    }

    public void startQuoting() {
        if (quoting.compareAndSet(false, true)) {
            journal.event("start", "котирование включено");
            log.warn("КОТИРОВАНИЕ ВКЛЮЧЕНО: {} по {} USDC", symbol, params.size());
        }
    }

    public void stopQuoting() {
        if (quoting.compareAndSet(true, false)) {
            journal.event("stop", "котирование выключено, снимаю заявки");
            cancelAll("остановка по команде");
            log.warn("котирование выключено");
        }
    }

    public boolean isQuoting() {
        return quoting.get();
    }

    public void shutdown() {
        alive = false;
        cancelAll("выключение процесса");
    }

    public Stats stats() {
        return new Stats(placements, replaces, cancels, fills, inventory, lastFair,
                quoting.get() ? "котирует" : "остановлен", pausedReason);
    }

    @Override
    public void run() {
        refreshBalances();
        while (alive) {
            long started = System.currentTimeMillis();
            try {
                tick();
            } catch (Exception e) {
                // Цикл не должен умирать от единичной ошибки: заявки останутся
                // висеть, а следующий тик их подхватит. Но молчать нельзя.
                log.error("тик упал: {}", e.toString());
                journal.event("tick_error", e.toString());
            }
            long sleep = periodMs - (System.currentTimeMillis() - started);
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void tick() {
        rollCounters();
        StandReader.Fair fair = stand.latest(base, 30_000);
        lastFair = fair.price();

        if (!quoting.get()) {
            pausedReason = "не запущен";
            return;
        }
        if (!fair.quotable() || !(fair.price() > 0)) {
            // Гейт ТЗ §4.1: опора сломана — снимаем всё, а не ждём.
            pausedReason = fair.pausedReason() == null ? "курс ненадёжен" : fair.pausedReason();
            cancelAll(pausedReason);
            return;
        }
        long staleMs = System.currentTimeMillis() - fair.asOfMs();
        if (staleMs > 15_000) {
            pausedReason = "данные стенда устарели на " + staleMs / 1000 + " с";
            cancelAll(pausedReason);
            return;
        }
        pausedReason = null;

        Quoter.Quotes target = quoter.quotes(fair.price(), inventory);
        syncSide(Side.BUY, bid, target.bid(), fair.price());
        syncSide(Side.SELL, ask, target.ask(), fair.price());
    }

    /** Приводит одну сторону к целевой цене: поставить, переставить или снять. */
    private void syncSide(Side side, Resting resting, Double targetPrice, double fair) {
        if (targetPrice == null) {
            if (resting.venueId != null) {
                cancel(side, resting, "сторона не котируется");
            }
            return;
        }
        double size = sizeFor(side, targetPrice);
        if (size <= 0) {
            if (resting.venueId != null) {
                cancel(side, resting, "нечем котировать эту сторону");
            }
            return;
        }
        double notional = size * targetPrice;
        if (!ExecLimits.orderAllowed(notional)) {
            log.error("заявка {} на {} USDC превышает предел {} — не ставлю", side, notional,
                    ExecLimits.MAX_ORDER_NOTIONAL_USDC);
            journal.event("limit_blocked", side + " нотионал " + notional);
            return;
        }
        if (!ExecLimits.exposureAllowed(exposure() + notional)) {
            log.error("экспозиция превысила бы предел {} — не ставлю",
                    ExecLimits.MAX_TOTAL_EXPOSURE_USDC);
            journal.event("limit_blocked", "экспозиция");
            return;
        }

        if (resting.venueId == null) {
            place(side, resting, targetPrice, size);
        } else if (quoter.shouldRequote(resting.price, targetPrice)) {
            replace(side, resting, targetPrice, size);
        }
    }

    /**
     * Спот: продать можно только то, что есть, купить — только на что есть USDC.
     * Это физика площадки, а не настройка, и проверять её надо ДО отправки.
     */
    private double sizeFor(Side side, double price) {
        if (side == Side.SELL) {
            return Math.min(params.size(), inventory);
        }
        double affordable = price > 0 ? quoteBalance / price : 0;
        return Math.min(params.size(), affordable);
    }

    private double exposure() {
        return inventory * lastFair
                + (bid.venueId != null ? bid.price * bid.size : 0)
                + (ask.venueId != null ? ask.price * ask.size : 0);
    }

    private void place(Side side, Resting resting, double price, double size) {
        if (placements >= ExecLimits.MAX_PLACEMENTS_PER_DAY) {
            log.error("исчерпан суточный лимит постановок ({}) — останавливаю котирование",
                    ExecLimits.MAX_PLACEMENTS_PER_DAY);
            journal.event("limit_blocked", "постановки за сутки");
            stopQuoting();
            return;
        }
        String body = """
                {"client_order_id":"%s","symbol":"%s","side":"%s",
                 "order_configuration":{"limit":{"base_size":"%s","price":"%s",
                 "execution_instructions":["post_only"]}}}"""
                .formatted(UUID.randomUUID(), symbol.replace('/', '-'),
                        side == Side.BUY ? "buy" : "sell", fmt(size), fmt(price))
                .replaceAll("\\s*\\n\\s*", "");
        TradeClient.Response response = client.place(body);
        placements++;
        if (response.ok()) {
            resting.venueId = extract(response.body());
            resting.price = price;
            resting.size = size;
        } else {
            log.warn("постановка {} не прошла: {} {}", side, response.status(), response.body());
        }
    }

    private void replace(Side side, Resting resting, double price, double size) {
        if (replacesThisMinute >= ExecLimits.MAX_REPLACES_PER_MINUTE) {
            return;                       // защита от зацикливания; молча, но с паузой
        }
        String body = """
                {"client_order_id":"%s","base_size":"%s","price":"%s",
                 "execution_instructions":["post_only"]}"""
                .formatted(UUID.randomUUID(), fmt(size), fmt(price))
                .replaceAll("\\s*\\n\\s*", "");
        TradeClient.Response response = client.replace(resting.venueId, body);
        replaces++;
        replacesThisMinute++;
        if (response.ok()) {
            // Замена создаёт ДРУГУЮ заявку: новый id обязателен к перечитыванию.
            String newId = extract(response.body());
            resting.venueId = newId != null ? newId : resting.venueId;
            resting.price = price;
            resting.size = size;
        } else {
            // Заявки уже нет — скорее всего исполнилась. Считаем это исполнением
            // и обновляем остатки: истина о позиции — на бирже, а не у нас.
            log.info("замена {} не прошла ({}), считаю заявку ушедшей", side, response.status());
            resting.venueId = null;
            fills++;
            refreshBalances();
        }
    }

    private void cancel(Side side, Resting resting, String why) {
        if (resting.venueId == null) {
            return;
        }
        TradeClient.Response response = client.cancel(resting.venueId);
        cancels++;
        journal.event("cancel", side + " " + resting.venueId + " (" + why + ") → "
                + response.status());
        resting.venueId = null;
    }

    private void cancelAll(String why) {
        cancel(Side.BUY, bid, why);
        cancel(Side.SELL, ask, why);
    }

    /** Истина о позиции — остатки на бирже, а не наш счётчик исполнений. */
    private void refreshBalances() {
        TradeClient.Response response = client.balances();
        if (!response.ok() || response.body() == null) {
            return;
        }
        Matcher matcher = BALANCE.matcher(response.body());
        while (matcher.find()) {
            double available = Double.parseDouble(matcher.group(2));
            double total = Double.parseDouble(matcher.group(4));
            if (base.equals(matcher.group(1))) {
                inventory = total;            // позиция целиком, вместе с зарезервированным
            } else if ("USDC".equals(matcher.group(1))) {
                quoteBalance = available;     // а тут важно именно «на что можно поставить»
            }
        }
    }

    private void rollCounters() {
        long now = System.currentTimeMillis();
        if (now - minuteStartMs >= 60_000) {
            minuteStartMs = now;
            replacesThisMinute = 0;
        }
        if (now - dayStartMs >= 86_400_000L) {
            dayStartMs = now;
            placements = 0;
            journal.event("day_roll", "суточные счётчики обнулены");
        }
        // Остатки перечитываются раз в минуту: исполнение могло случиться молча.
        if (now - minuteStartMs < periodMs) {
            refreshBalances();
        }
    }

    private static String extract(String body) {
        Matcher matcher = VENUE_ID.matcher(body == null ? "" : body);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Без экспоненты и без лишних нулей: площадка принимает десятичную строку. */
    private static String fmt(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
