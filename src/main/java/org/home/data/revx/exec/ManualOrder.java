package org.home.data.revx.exec;

import org.home.data.revx.RevxConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * {@code --revx-order}: поставить ОДНУ лимитную заявку руками и выйти.
 *
 * <h2>Зачем отдельный режим</h2>
 *
 * Котировщик умеет только своё: держать две заявки вокруг справедливой цены и
 * двигать их. Выйти из позиции по заданной цене он не умеет — а такая нужда
 * возникает, когда пару выводят из работы и остаётся инвентарь. Класть это в
 * {@link QuoteLoop} нельзя: он должен уметь ровно то, что меряется стендом.
 *
 * ⚠️ Ставит НАСТОЯЩУЮ заявку на настоящие деньги. Отмены здесь нет намеренно —
 * заявка должна остаться в книге. Снимать её — {@code --revx-panic} или руками.
 *
 * <h2>Предохранители</h2>
 *
 * <ul>
 *   <li>цена и размер обязательны — умолчаний нет, чтобы промах в аргументах не
 *       превратился в заявку по случайной цене;</li>
 *   <li>оборот заявки ограничен {@link #MAX_MANUAL_NOTIONAL_USDC}. Это не
 *       боевой предел котировщика ($10): выход из позиции по определению крупнее
 *       лота, и занижать здесь значило бы дробить заявку без нужды;</li>
 *   <li>{@code post_only} по умолчанию ВКЛЮЧЁН. Он же и главная ловушка: заявка,
 *       пересекающая книгу, будет ОТКЛОНЕНА, а не исполнена по рынку. Продажа
 *       ниже лучшего бида и покупка выше лучшего аска через этот режим не
 *       проходят — так и задумано.</li>
 * </ul>
 */
@Component
@Lazy
public class ManualOrder {

    private static final Logger log = LoggerFactory.getLogger(ManualOrder.class);

    /**
     * Потолок ручной заявки. Выход из позиции крупнее лота по определению:
     * на 09.09.2026 остатки выводимых пар были $13.3 (ADA), $13.3 (ENA) и
     * $11.3 (PEPE). Сорок — вдвое больше самого крупного из них и совпадает с
     * пределом экспозиции бота, то есть ошибка на порядок сюда не пройдёт.
     */
    public static final double MAX_MANUAL_NOTIONAL_USDC = 40.0;

    private final RevxConfig cfg;
    private final String symbol;
    private final String side;
    private final double size;
    private final double price;
    private final boolean postOnly;
    private final String journalPath;

    public ManualOrder(RevxConfig cfg,
                       @Value("${revx.exec.symbol}") String symbol,
                       @Value("${revx.order.side:sell}") String side,
                       @Value("${revx.order.size:0}") double size,
                       @Value("${revx.order.price:0}") double price,
                       @Value("${revx.order.post-only:true}") boolean postOnly,
                       @Value("${revx.exec.journal}") String journalPath) {
        this.cfg = cfg;
        this.symbol = symbol;
        this.side = side;
        this.size = size;
        this.price = price;
        this.postOnly = postOnly;
        this.journalPath = journalPath;
    }

    public void run() {
        if (!(size > 0) || !(price > 0)) {
            log.error("нужны --revx.order.size и --revx.order.price, оба больше нуля");
            return;
        }
        if (!"buy".equalsIgnoreCase(side) && !"sell".equalsIgnoreCase(side)) {
            log.error("--revx.order.side должен быть buy или sell, а не {}", side);
            return;
        }
        double notional = size * price;
        if (notional > MAX_MANUAL_NOTIONAL_USDC) {
            log.error("оборот заявки {} USDC больше предела {} — не ставлю",
                    fmt(notional), fmt(MAX_MANUAL_NOTIONAL_USDC));
            return;
        }

        TradeAuth auth = TradeAuth.fromEnvironment();
        try (ExecJournal journal = new ExecJournal(journalPath)) {
            TradeClient client = new TradeClient(cfg.baseUrl(), auth, journal);

            // ⚠️ Символ в теле пишется через ДЕФИС, а в каталоге пар — через слэш.
            String venueSymbol = symbol.replace('/', '-');
            String body = """
                    {"client_order_id":"%s","symbol":"%s","side":"%s",
                     "order_configuration":{"limit":{"base_size":"%s","price":"%s"%s}}}"""
                    .formatted(UUID.randomUUID(), venueSymbol, side.toLowerCase(),
                            fmt(size), fmt(price),
                            postOnly ? ",\"execution_instructions\":[\"post_only\"]" : "")
                    .replaceAll("\\s*\\n\\s*", "");

            log.warn("ставлю {} {} {} по {} ({} USDC){}", side.toUpperCase(),
                    fmt(size), symbol, fmt(price), fmt(notional),
                    postOnly ? ", post_only" : ", БЕЗ post_only");
            journal.event("manual_order", side + " " + fmt(size) + " " + symbol
                    + " по " + fmt(price) + (postOnly ? " post_only" : ""));

            Venue.Response r = client.place(body);
            if (r.ok()) {
                log.warn("принята: {}", r.body());
                journal.event("manual_order_ok", String.valueOf(r.body()));
            } else {
                // ⚠️ Самый вероятный отказ — post_only на пересекающей цене.
                log.error("ОТКАЗ {}: {}", r.status(), r.body());
                journal.event("manual_order_fail", r.status() + " " + r.body());
            }
        }
    }

    /** Без экспоненты: площадка принимает только десятичную запись. */
    private static String fmt(double v) {
        return BigDecimal.valueOf(v).setScale(12, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }
}
