package org.home.data.revx.exec;

import org.home.data.revx.RevxConfig;
import org.home.data.revx.sim.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code --revx-park}: увести свои заявки далеко от цены и выйти, НЕ снимая их.
 *
 * <h2>Зачем это вместо отмены</h2>
 *
 * Суточный лимит тратится только на {@code POST}. Отмена бесплатна, а вот
 * восстановление после неё — нет: замер 07.09.2026 показал, что перезапуск
 * стоит 3–6 постановок на бота и 27 на полную выкатку, тогда как ровный расход
 * у SOL всего 0.2 постановки за пять минут. То есть один перезапуск съедал у
 * SOL два с половиной часа бюджета, а у ADA — сорок минут.
 *
 * Заявка, оставленная в книге, переживает перезапуск бесплатно: вернувшийся бот
 * находит её в {@code GET /orders/active} по своей метке, усыновляет
 * ({@code QuoteLoop.adoptSide}) и возвращает на место через {@code PUT}, у
 * которого суточного лимита нет вовсе. Механизм усыновления уже работал — ему
 * просто нечего было усыновлять, потому что {@code ExecStopPost} звал
 * {@link Panic} и всё сносил.
 *
 * <h2>Чем это безопасно</h2>
 *
 * Отодвинутая заявка исполняется РЕЖЕ, чем та же заявка на рабочем месте.
 * Значит парковка строго безопаснее, чем просто оставить всё как есть, и
 * строго дешевле, чем снять. Проигрывает она отмене только в одном: если
 * процесс не вернётся, заявка останется в книге. Поэтому:
 *
 * <ul>
 *   <li>парковка — для ПЛАНОВОЙ остановки (выкатка, перезапуск юнита);</li>
 *   <li>{@link Panic} остаётся нетронутым для аварии и для кнопки в боте —
 *       требование ТЗ §6 «аварийная остановка одной командой с отменой всех
 *       заявок» выполняется по-прежнему;</li>
 *   <li>не удалось отодвинуть — СНИМАЕМ. Оставить заявку на рабочем месте после
 *       неудачной парковки хуже, чем потратить постановку на её восстановление.</li>
 * </ul>
 */
@Component
@Lazy
public class Park {

    private static final Logger log = LoggerFactory.getLogger(Park.class);

    /**
     * На сколько отодвигать. Пять процентов — это заметно больше суточного хода
     * любой из шести пар в обычный день и больше, чем успевает пройти рынок за
     * перезапуск юнита (секунды). При этом заявка остаётся в книге, то есть
     * возвращается на место заменой, а не постановкой.
     */
    private static final double PARK_PCT = 0.05;

    private final RevxConfig cfg;
    private final BotTag tag;
    private final String journalPath;
    private final String symbol;
    private final String standDbPath;

    public Park(RevxConfig cfg,
                @Value("${revx.exec.bot-id}") String botId,
                @Value("${revx.exec.journal}") String journalPath,
                @Value("${revx.exec.symbol}") String symbol,
                @Value("${revx.exec.stand-db}") String standDbPath) {
        this.cfg = cfg;
        this.tag = botId == null || botId.isBlank() ? null : new BotTag(botId);
        this.journalPath = journalPath;
        this.symbol = symbol;
        this.standDbPath = standDbPath;
    }

    public void run() {
        if (tag == null) {
            log.error("парковка без метки бота невозможна: не задан revx.exec.bot-id");
            return;
        }
        TradeAuth auth = TradeAuth.fromEnvironment();
        try (ExecJournal journal = new ExecJournal(journalPath)) {
            TradeClient client = new TradeClient(cfg.baseUrl(), auth, journal);
            journal.event("park", "плановая остановка: увожу заявки от цены");

            double quoteStep = quoteStep();
            Venue.Response active = client.activeOrders();
            if (!active.ok() || active.body() == null) {
                log.error("не прочитать активные заявки ({}) — на всякий случай снимаю всё",
                        active.status());
                cancelAll(client, journal);
                return;
            }
            List<ActiveOrder> mine = ActiveOrder.parse(active.body()).stream()
                    .filter(o -> ActiveOrder.normalize(symbol).equals(o.symbol()))
                    .filter(o -> tag.owns(o.clientId()))
                    .toList();
            if (mine.isEmpty()) {
                log.info("своих заявок в книге нет — парковать нечего");
                return;
            }
            log.warn("своих заявок: {} — отвожу на {}%", mine.size(), (int) (PARK_PCT * 100));
            int parked = 0;
            for (ActiveOrder order : mine) {
                if (park(client, journal, order, quoteStep)) {
                    parked++;
                } else {
                    // Отодвинуть не вышло — заявка осталась на рабочем месте, а
                    // хозяина у неё сейчас не будет. Снимаем.
                    Venue.Response cancelled = client.cancel(order.id());
                    journal.event("park_cancel",
                            order.id() + " не отодвинулась → " + cancelled.status());
                    log.warn("заявка {} не отодвинулась — снята ({})",
                            order.id(), cancelled.status());
                }
            }
            log.warn("припарковано {} из {}", parked, mine.size());
            journal.event("park_done", "припарковано " + parked + " из " + mine.size());
        } catch (Exception e) {
            log.error("парковка не прошла: {}", e.toString(), e);
        }
    }

    private boolean park(TradeClient client, ExecJournal journal,
                         ActiveOrder order, double quoteStep) {
        double target = order.side() == Side.BUY
                ? order.price() * (1 - PARK_PCT)
                : order.price() * (1 + PARK_PCT);
        // Округляем В СТОРОНУ ОТ РЫНКА: покупку вниз, продажу вверх. Округление
        // «к ближайшему» может подтянуть заявку обратно на полшага, а весь смысл
        // парковки в том, чтобы она гарантированно стояла дальше, чем стояла.
        double price = round(target, quoteStep, order.side() == Side.SELL);
        String body = """
                {"client_order_id":"%s","base_size":"%s","price":"%s",
                 "execution_instructions":["post_only"]}"""
                .formatted(tag.newClientOrderId(), fmt(order.size()), fmt(price))
                .replaceAll("\\s*\\n\\s*", "");
        Venue.Response response = client.replace(order.id(), body);
        if (response.ok()) {
            journal.event("park_move", order.side() + " " + order.id()
                    + " " + fmt(order.price()) + " → " + fmt(price));
            return true;
        }
        // ⚠️ 422 на замене НЕ значит, что замены не было (док. 111): заявка
        // может уже быть `cancelled/replaced`, а идентификатор наследника не
        // приехал никуда. Судьбу выясняем по списку активных, а не по статусу.
        log.warn("замена {} отдала {} — сверяюсь с книгой", order.id(), response.status());
        Venue.Response after = client.activeOrders();
        if (after.ok() && after.body() != null) {
            boolean stillThere = ActiveOrder.parse(after.body()).stream()
                    .anyMatch(o -> o.id().equals(order.id()));
            if (!stillThere) {
                // Наследник создан, но его номер потерян. Снимать нечего и
                // некого; вернувшийся бот усыновит его по метке.
                journal.event("park_move", order.id()
                        + " заменена вслепую (наследник по метке)");
                return true;
            }
        }
        return false;
    }

    private void cancelAll(TradeClient client, ExecJournal journal) {
        Venue.Response active = client.activeOrders();
        if (!active.ok() || active.body() == null) {
            log.error("и активные заявки не читаются — оставляю как есть, нужен --revx-panic");
            return;
        }
        for (ActiveOrder order : ActiveOrder.parse(active.body())) {
            if (tag.owns(order.clientId())) {
                journal.event("park_cancel", order.id() + " → "
                        + client.cancel(order.id()).status());
            }
        }
    }

    /** Шаг цены берём у площадки через каталог стенда; нет каталога — нет парковки. */
    private double quoteStep() {
        try (StandReader stand = new StandReader(standDbPath, cfg.memecoins(),
                new org.home.data.revx.sim.FairPrice.Limits(cfg.fairMinPairs(),
                        cfg.fairMaxDispersionPct(), cfg.fairMaxReferenceSpreadPct(),
                        cfg.fairMaxResidualPct()),
                cfg.fairMaxSkewMs())) {
            var spec = stand.spec(symbol);
            return spec != null && spec.quoteStep() > 0 ? spec.quoteStep() : 0;
        } catch (Exception e) {
            log.warn("шаг цены не прочитан ({}) — округляю по цене заявки", e.getMessage());
            return 0;
        }
    }

    private static double round(double price, double step, boolean up) {
        if (!(step > 0)) {
            return price;
        }
        double units = price / step;
        return (up ? Math.ceil(units) : Math.floor(units)) * step;
    }

    private static String fmt(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
