package org.home.data.revx.exec;

import org.home.data.revx.RevxConfig;
import org.home.data.revx.sim.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code --revx-book --revx.order.id=<venue_order_id>}: провести исполнение по
 * ОДНОЙ заявке, о которой бот не узнал.
 *
 * <h2>Зачем</h2>
 *
 * Бот узнаёт о сделке единственным способом — заметив, что заявка ушла из
 * {@code /orders/active}, и прочитав по ней {@code filled_quantity}. Пока он
 * работает, этого достаточно. Но заявку можно поставить и в обход цикла
 * ({@code --revx-order}), а исполниться она может, когда бот уже остановлен, —
 * и тогда сообщить о сделке некому.
 *
 * Ровно это случилось 09.09.2026: остановленные боты E и F продали свои остатки
 * вручную выставленными заявками, а в их журналах на два часа осталась позиция,
 * которой уже не было. Сводка показывала $13.5 и $11.3 несуществующего
 * инвентаря, а реестр владения числил за ними проданные монеты.
 *
 * <h2>Почему не правкой базы</h2>
 *
 * Потому что источник правды — площадка, а не наши представления о ней.
 * Здесь запрашивается состояние заявки и проводится ровно то, что ответила
 * площадка, тем же порядком, что и в цикле ({@code QuoteLoop.applyFill}):
 *
 * <ol>
 *   <li>запись в {@code exec_fill} — для P&L и отчётов;</li>
 *   <li>позиция и касса в {@code exec_state} — по ним бот торгует после
 *       перезапуска, и именно их {@link Park} не трогает;</li>
 *   <li>реестр владения — монеты и деньги двигаются зеркально, иначе
 *       заработанное станет ничьим.</li>
 * </ol>
 *
 * <h2>Идемпотентность</h2>
 *
 * ⚠️ {@code filled_quantity} НАКОПИТЕЛЬНЫЙ, и по одной заявке спрашивать можно
 * не раз. Проводится РАЗНИЦА с тем, что уже записано в журнале по этому
 * идентификатору. Повторный запуск не удваивает сделку — это то же правило, по
 * которому живёт {@code QuoteLoop.inspectGoneOrder}.
 */
@Component
@Lazy
public class BookOrder {

    private static final Logger log = LoggerFactory.getLogger(BookOrder.class);

    private final RevxConfig cfg;
    private final String botId;
    private final String symbol;
    private final String journalPath;
    private final String allocPath;
    private final String orderId;
    private final boolean ownPosition;

    public BookOrder(RevxConfig cfg,
                     @Value("${revx.exec.bot-id}") String botId,
                     @Value("${revx.exec.symbol}") String symbol,
                     @Value("${revx.exec.journal}") String journalPath,
                     @Value("${revx.exec.alloc}") String allocPath,
                     @Value("${revx.exec.own-position}") boolean ownPosition,
                     @Value("${revx.order.id:}") String orderId) {
        this.cfg = cfg;
        this.botId = botId;
        this.symbol = symbol;
        this.journalPath = journalPath;
        this.allocPath = allocPath;
        this.ownPosition = ownPosition;
        this.orderId = orderId;
    }

    public void run() {
        if (orderId == null || orderId.isBlank()) {
            log.error("нужен --revx.order.id=<venue_order_id>");
            return;
        }
        String base = symbol.substring(0, symbol.indexOf('/'));
        String quote = symbol.substring(symbol.indexOf('/') + 1);

        TradeAuth auth = TradeAuth.fromEnvironment();
        try (ExecJournal journal = new ExecJournal(journalPath)) {
            TradeClient client = new TradeClient(cfg.baseUrl(), auth, journal);
            Venue.Response state = client.order(orderId);
            if (!state.ok() || state.body() == null) {
                log.error("площадка не отдала состояние заявки {} ({})", orderId, state.status());
                return;
            }
            double total = num(state.body(), "filled_quantity");
            double price = num(state.body(), "average_fill_price");
            String sideText = str(state.body(), "side");
            String status = str(state.body(), "status");
            log.warn("заявка {}: сторона {}, статус {}, исполнено {} по {}",
                    orderId, sideText, status, fmt(total), fmt(price));

            if (!(total > 0)) {
                log.warn("по этой заявке ничего не исполнилось — проводить нечего");
                return;
            }
            if (sideText == null) {
                log.error("площадка не назвала сторону — проводить вслепую нельзя");
                return;
            }
            Side side = "buy".equalsIgnoreCase(sideText) ? Side.BUY : Side.SELL;

            // ⚠️ Проводим РАЗНИЦУ: filled_quantity накопительный, а спрашивать
            // одну и ту же заявку можно не раз.
            double already = journal.filledByOrder(orderId);
            double delta = total - already;
            if (!(delta > 1e-12)) {
                log.warn("уже проведено {} — повторно не записываю", fmt(already));
                return;
            }
            if (!(price > 0)) {
                log.error("цена исполнения не прочитана — позицию сдвинуть можно, "
                        + "а кассу нет; не провожу");
                return;
            }

            journal.fill(orderId, side.name(), delta, price, price,
                    num(state.body(), "total_fee"), str(state.body(), "fee_currency"), status);

            if (ownPosition) {
                double pos = orZero(journal.getState("position"));
                double cash = orZero(journal.getState("cash"));
                pos += side.sign() * delta;
                cash -= side.sign() * delta * price;
                journal.putState("position", pos);
                journal.putState("cash", cash);
                log.warn("позиция {} → {}, касса → {}", base, fmt(pos), fmt(cash));

                // Реестр двигается зеркально: монеты туда, деньги обратно.
                try (AllocRegistry alloc = new AllocRegistry(allocPath)) {
                    long now = System.currentTimeMillis();
                    alloc.applyFill(botId, base, side.sign() * delta, now);
                    alloc.applyFill(botId, quote, -side.sign() * delta * price, now);
                    log.warn("реестр: {} {} → {}, {} → {}", base, botId,
                            fmt(alloc.own(botId, base)), quote, fmt(alloc.own(botId, quote)));
                }
            }
            journal.event("book_order", side + " " + orderId + " проведено "
                    + fmt(delta) + " по " + fmt(price) + " (вне цикла)");
            log.warn("ПРОВЕДЕНО: {} {} по {}", side, fmt(delta), fmt(price));
        } catch (Exception e) {
            log.error("проведение не прошло: {}", e.toString(), e);
        }
    }

    private static double orZero(Double v) {
        return v == null ? 0 : v;
    }

    private static double num(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"?([0-9.eE+-]+)\"?").matcher(body);
        try {
            return m.find() ? Double.parseDouble(m.group(1)) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String str(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private static String fmt(double v) {
        return BigDecimal.valueOf(v).setScale(10, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }
}
