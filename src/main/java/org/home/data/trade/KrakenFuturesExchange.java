package org.home.data.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Прод-исполнение S5 на Kraken Futures (перпы PF_*USD) — замена {@link MockExchange} той же формы.
 * Только шорт: открытие — market sell, закрытие — market buy reduceOnly. Позиции/баланс — источник истины
 * с биржи (openpositions/accounts). Разрыв/сбой сети → {@link ExchangeDisconnectedException} (StopEngine
 * реагирует реконнектом); отклонение ордера биржей → {@link OrderResult} REJECTED (не исключение).
 *
 * <p>Реальные вызовы включаются ключами (см. {@link KrakenConfig}); разбор ответов тестируется на фейке.
 */
public class KrakenFuturesExchange implements ExchangeAdapter {

    private static final Logger log = LoggerFactory.getLogger(KrakenFuturesExchange.class);
    private static final ObjectMapper M = new ObjectMapper();
    private static final long MARK_TTL_MS = 2000;
    private static final long INSTR_TTL_MS = 3_600_000;   // спецификации меняются редко — час

    private final KrakenApi api;

    private Map<String, Double> markCache = Map.of();
    private long markCacheAt = 0;
    private Map<String, Double> minSizeCache = Map.of();
    private Map<String, Double> tickSizeCache = Map.of();
    private long minSizeCacheAt = 0;

    public KrakenFuturesExchange(KrakenApi api) { this.api = api; }

    @Override
    public double mark(String symbol) throws ExchangeDisconnectedException {
        try {
            long now = System.currentTimeMillis();
            if (now - markCacheAt > MARK_TTL_MS) {
                Map<String, Double> m = new HashMap<>();
                for (JsonNode t : M.readTree(api.get("/api/v3/tickers", false)).path("tickers")) {
                    double mk = t.path("markPrice").asDouble(t.path("last").asDouble(0));
                    if (mk > 0) m.put(t.path("symbol").asText("").toUpperCase(), mk);
                }
                markCache = m; markCacheAt = now;
            }
            return markCache.getOrDefault(symbol.toUpperCase(), 0.0);
        } catch (Exception e) {
            throw new ExchangeDisconnectedException("Kraken mark: " + e);
        }
    }

    @Override
    public OrderResult openShort(String symbol, double qty) throws ExchangeDisconnectedException {
        return sendMarket(symbol, qty, "sell", false);
    }

    @Override
    public OrderResult closeShort(String symbol, double qty) throws ExchangeDisconnectedException {
        return sendMarket(symbol, qty, "buy", true);
    }

    private OrderResult sendMarket(String symbol, double qty, String side, boolean reduceOnly)
            throws ExchangeDisconnectedException {
        String body = "orderType=mkt&symbol=" + symbol + "&side=" + side
                + "&size=" + trimNum(qty) + (reduceOnly ? "&reduceOnly=true" : "");
        String resp;
        try {
            resp = api.post("/api/v3/sendorder", body);
        } catch (Exception e) {
            throw new ExchangeDisconnectedException("Kraken sendorder: " + e);  // неизвестный исход → как разрыв
        }
        log.info("Kraken sendorder resp: {}", truncate(resp));   // диагностика формата orderEvents/fill
        try {
            JsonNode root = M.readTree(resp);
            if (!"success".equals(root.path("result").asText()))
                return OrderResult.rejected("result=" + root.path("result").asText() + " " + root.path("error").asText(""));
            JsonNode ss = root.path("sendStatus");
            String status = ss.path("status").asText("");
            if (status.contains("Reject") || status.equals("invalidOrder") || status.equals("cancelled"))
                return OrderResult.rejected("status=" + status);
            double sumQty = 0, notional = 0;
            for (JsonNode ev : ss.path("orderEvents")) {
                // цена/объём исполнения могут лежать прямо в событии или в orderPriorExecution (формат Kraken)
                double px = firstPositive(ev.path("price"), ev.path("executionPrice"),
                        ev.path("orderPriorExecution").path("limitPrice"), ev.path("orderPriorExecution").path("price"));
                double amt = firstPositive(ev.path("amount"), ev.path("executionSize"),
                        ev.path("orderPriorExecution").path("quantity"), ev.path("orderPriorExecution").path("filled"));
                if (px > 0 && amt > 0) { sumQty += amt; notional += amt * px; }
            }
            String id = ss.path("order_id").asText(ss.path("orderId").asText(""));
            double fillPx;
            if (sumQty > 0) fillPx = notional / sumQty;
            else if (!reduceOnly) fillPx = firstPositive(positionEntryPrice(symbol), mark(symbol));  // открытие: цена из позиции
            else fillPx = mark(symbol);                                                              // закрытие: марка
            return OrderResult.filled(id, fillPx, sumQty > 0 ? sumQty : qty);
        } catch (ExchangeDisconnectedException e) {
            throw e;
        } catch (Exception e) {
            return OrderResult.rejected("parse: " + e);
        }
    }

    /** Цена входа позиции по символу с биржи (источник истины); 0 — если позиции нет. */
    private double positionEntryPrice(String symbol) {
        try {
            for (Position p : positions()) if (p.symbol().equalsIgnoreCase(symbol)) return p.entryPx();
        } catch (Exception ignore) { /* нет позиции/сеть — вернём 0, вызывающий возьмёт марку */ }
        return 0;
    }

    private static double firstPositive(JsonNode... nodes) {
        for (JsonNode n : nodes) { double v = n.asDouble(0); if (v > 0) return v; }
        return 0;
    }
    private static double firstPositive(double... vals) {
        for (double v : vals) if (v > 0) return v;
        return 0;
    }
    private static String truncate(String s) { return s == null ? "" : s.substring(0, Math.min(s.length(), 600)); }

    @Override
    public List<Position> positions() throws ExchangeDisconnectedException {
        try {
            List<Position> out = new ArrayList<>();
            for (JsonNode p : M.readTree(api.get("/api/v3/openpositions", true)).path("openPositions")) {
                double size = p.path("size").asDouble(0);
                if (size <= 0) continue;
                Side side = "short".equalsIgnoreCase(p.path("side").asText()) ? Side.SHORT : Side.LONG;
                out.add(new Position(p.path("symbol").asText("").toUpperCase(), side, size, p.path("price").asDouble(0)));
            }
            return out;
        } catch (Exception e) {
            throw new ExchangeDisconnectedException("Kraken openpositions: " + e);
        }
    }

    @Override
    public double balance() throws ExchangeDisconnectedException {
        try {
            JsonNode flex = M.readTree(api.get("/api/v3/accounts", true)).path("accounts").path("flex");
            JsonNode pv = flex.path("portfolioValue");
            if (pv.isMissingNode()) {   // 0 — валидно (пустой счёт); тревога только если поля НЕТ
                log.warn("Kraken balance: accounts.flex.portfolioValue отсутствует — проверить структуру accounts");
                return flex.path("availableMargin").asDouble(0);
            }
            return pv.asDouble(0);
        } catch (Exception e) {
            throw new ExchangeDisconnectedException("Kraken accounts: " + e);
        }
    }

    /** Полная разбивка счёта: flex (маржа фьючерсов) + cash (не идёт в маржу) — диагностика «куда легли деньги». */
    @Override
    public String balanceBreakdown() throws ExchangeDisconnectedException {
        try {
            JsonNode acc = M.readTree(api.get("/api/v3/accounts", true)).path("accounts");
            JsonNode flex = acc.path("flex");
            StringBuilder sb = new StringBuilder("Kraken Futures — счёт:\n");
            sb.append("flex (multi-collateral, ИДЁТ в маржу):\n")
              .append("  portfolioValue: $").append(fmtUsd(flex.path("portfolioValue").asDouble(0))).append("\n")
              .append("  collateralValue: $").append(fmtUsd(flex.path("collateralValue").asDouble(0))).append("\n")
              .append("  availableMargin: $").append(fmtUsd(flex.path("availableMargin").asDouble(0))).append("\n");
            JsonNode curr = flex.path("currencies");
            if (curr.isObject() && curr.size() > 0) {
                sb.append("  залог:");
                for (var it = curr.fields(); it.hasNext(); ) {
                    var en = it.next();
                    JsonNode c = en.getValue();
                    double q = c.path("quantity").asDouble(c.path("balance").asDouble(0));
                    sb.append(" ").append(en.getKey().toUpperCase()).append("=").append(q);
                }
                sb.append("\n");
            }
            // cash-кошелёк: ненулевые валюты
            StringBuilder cash = new StringBuilder();
            for (var it = acc.path("cash").path("balances").fields(); it.hasNext(); ) {
                var en = it.next();
                double v = en.getValue().asDouble(0);
                if (v != 0) cash.append(" ").append(en.getKey().toUpperCase()).append("=").append(v);
            }
            if (cash.length() > 0)
                sb.append("cash-кошелёк (НЕ идёт в маржу фьючерсов!):").append(cash).append("\n")
                  .append("→ переведи в multi-collateral (flex) кошелёк, иначе для торговли недоступно.\n");
            else if (flex.path("portfolioValue").asDouble(0) <= 0)
                sb.append("(пусто — пополни flex/multi-collateral кошелёк фьючерсов)\n");
            return sb.toString();
        } catch (Exception e) {
            throw new ExchangeDisconnectedException("Kraken accounts: " + e);
        }
    }

    private static String fmtUsd(double v) { return String.format(java.util.Locale.ROOT, "%.2f", v); }

    @Override
    public double minOrderSize(String symbol) throws ExchangeDisconnectedException {
        refreshInstruments();
        return minSizeCache.getOrDefault(symbol.toUpperCase(), 0.0);
    }

    private void refreshInstruments() throws ExchangeDisconnectedException {
        try {
            long now = System.currentTimeMillis();
            if (now - minSizeCacheAt <= INSTR_TTL_MS && !minSizeCache.isEmpty()) return;
            Map<String, Double> mins = new HashMap<>(), ticks = new HashMap<>();
            for (JsonNode i : M.readTree(api.get("/api/v3/instruments", false)).path("instruments")) {
                if (!i.path("tradeable").asBoolean(false)) continue;
                String sym = i.path("symbol").asText("").toUpperCase();
                mins.put(sym, Math.pow(10, -i.path("contractValueTradePrecision").asInt(0)));  // мин.лот
                double tick = i.path("tickSize").asDouble(0);
                if (tick > 0) ticks.put(sym, tick);
            }
            minSizeCache = mins; tickSizeCache = ticks; minSizeCacheAt = now;
        } catch (Exception e) {
            throw new ExchangeDisconnectedException("Kraken instruments: " + e);
        }
    }

    /**
     * Биржевой стоп-ордер: reduceOnly buy-stop (закрытие шорта), триггер stopPrice по МАРКЕ, без limitPrice →
     * рыночное исполнение. Best-effort: любую ошибку логируем и возвращаем "" (софт-стоп — основной).
     */
    @Override
    public String placeStopBuy(String symbol, double qty, double stopPrice) throws ExchangeDisconnectedException {
        try {
            double px = roundToTick(symbol, stopPrice);
            String body = "orderType=stp&symbol=" + symbol + "&side=buy&size=" + trimNum(qty)
                    + "&stopPrice=" + trimNum(px) + "&triggerSignal=mark&reduceOnly=true";
            String resp = api.post("/api/v3/sendorder", body);
            JsonNode root = M.readTree(resp);
            String status = root.path("sendStatus").path("status").asText("");
            if (!"success".equals(root.path("result").asText()) || status.contains("Reject") || status.equals("invalidOrder")) {
                log.warn("Kraken биржевой стоп не поставлен ({} @ {}): {}", symbol, px, truncate(resp));
                return "";
            }
            log.info("Kraken биржевой стоп поставлен: {} buy-stop {} @ {}", symbol, trimNum(qty), trimNum(px));
            return root.path("sendStatus").path("order_id").asText("");
        } catch (Exception e) {
            log.warn("Kraken биржевой стоп ошибка ({}): {}", symbol, e.toString());
            return "";                                    // best-effort — не роняем открытие
        }
    }

    @Override
    public void cancelStops(String symbol) throws ExchangeDisconnectedException {
        try {
            api.post("/api/v3/cancelallorders", "symbol=" + symbol);
        } catch (Exception e) {
            log.warn("Kraken cancelallorders ({}) ошибка: {}", symbol, e.toString());
        }
    }

    /** Цена последнего исполнения по символу из истории сделок (для закрытия биржевым стопом). 0 — не найдено. */
    @Override
    public double lastFillPrice(String symbol) throws ExchangeDisconnectedException {
        try {
            String resp = api.get("/api/v3/fills", true);
            log.info("Kraken fills resp (искомый {}): {}", symbol, truncate(resp));
            String bestTime = ""; double bestPx = 0;
            for (JsonNode f : M.readTree(resp).path("fills")) {
                if (!symbol.equalsIgnoreCase(f.path("symbol").asText())) continue;
                double px = f.path("price").asDouble(0);
                String t = f.path("fillTime").asText("");
                if (px > 0 && t.compareTo(bestTime) > 0) { bestTime = t; bestPx = px; }   // самое свежее по времени
            }
            return bestPx;
        } catch (Exception e) {
            log.warn("Kraken fills ({}): {}", symbol, e.toString());
            return 0.0;
        }
    }

    /** Округлить цену к tickSize инструмента (для buy-stop — вверх, чтобы триггер не оказался ниже допустимого). */
    private double roundToTick(String symbol, double price) throws ExchangeDisconnectedException {
        refreshInstruments();
        double tick = tickSizeCache.getOrDefault(symbol.toUpperCase(), 0.0);
        if (tick <= 0) return price;
        return Math.ceil(price / tick) * tick;
    }

    /** size без хвостовых нулей (Kraken принимает десятичную строку). */
    private static String trimNum(double v) {
        String s = String.format(java.util.Locale.ROOT, "%.8f", v);
        return s.indexOf('.') < 0 ? s : s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
