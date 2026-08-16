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

    private final KrakenApi api;

    private Map<String, Double> markCache = Map.of();
    private long markCacheAt = 0;

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
                if (!ev.path("type").asText("").toUpperCase().contains("EXECUTION")) continue;
                double px = ev.path("price").asDouble(ev.path("executionPrice").asDouble(0));
                double amt = ev.path("amount").asDouble(0);
                sumQty += amt; notional += amt * px;
            }
            double fillPx = sumQty > 0 ? notional / sumQty : mark(symbol);   // нет событий исполнения → марка
            String id = ss.path("order_id").asText(ss.path("orderId").asText(""));
            return OrderResult.filled(id, fillPx, sumQty > 0 ? sumQty : qty);
        } catch (ExchangeDisconnectedException e) {
            throw e;
        } catch (Exception e) {
            return OrderResult.rejected("parse: " + e);
        }
    }

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

    /** size без хвостовых нулей (Kraken принимает десятичную строку). */
    private static String trimNum(double v) {
        String s = String.format(java.util.Locale.ROOT, "%.8f", v);
        return s.indexOf('.') < 0 ? s : s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
