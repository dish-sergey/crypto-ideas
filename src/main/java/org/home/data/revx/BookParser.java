package org.home.data.revx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Разбор снимка книги.
 *
 * ГЛАВНОЕ: порядок уровней в ответе НЕ гарантирован. На живом API 19.08.2026
 * массив asks приходит по УБЫВАНИЮ цены — asks[0] это худший аск. Наивное
 * чтение asks[0] дало по ETH/USDC спред 0.52% вместо реальных 0.335%, то есть
 * завысило бы предмет исследования в полтора раза.
 *
 * Поэтому: всегда сортируем сами, факт пересортировки отмечаем флагом, а
 * инвариант best_ask > best_bid проверяем и при нарушении снимок отбраковываем
 * (ТЗ §3.2) — не падая и молча не «починив».
 */
public final class BookParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Level(double price, double qty, int count) {
    }

    /** Разобранный снимок одной ноги. bids — по убыванию, asks — по возрастанию. */
    public record Book(List<Level> bids, List<Level> asks, Long serverTsMs, int flags) {

        public boolean usable() {
            return !BookFlags.has(flags, BookFlags.CROSSED)
                    && !BookFlags.has(flags, BookFlags.EMPTY_SIDE);
        }

        public double bestBid() {
            return bids.isEmpty() ? Double.NaN : bids.get(0).price();
        }

        public double bestAsk() {
            return asks.isEmpty() ? Double.NaN : asks.get(0).price();
        }

        public double mid() {
            return (bestBid() + bestAsk()) / 2;
        }

        /** Относительный спред: (ask - bid) / mid. */
        public double relativeSpread() {
            double mid = mid();
            return mid <= 0 ? Double.NaN : (bestAsk() - bestBid()) / mid;
        }
    }

    private BookParser() {
    }

    public static Book parse(String json, int expectedDepth) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException("некорректный JSON книги", e);
        }
        JsonNode data = root.path("data");
        List<Level> bids = levels(data.path("bids"));
        List<Level> asks = levels(data.path("asks"));

        int flags = 0;
        if (!isDescending(bids)) {
            flags |= BookFlags.BIDS_REORDERED;
        }
        if (!isAscending(asks)) {
            flags |= BookFlags.ASKS_REORDERED;
        }
        bids.sort(Comparator.comparingDouble(Level::price).reversed());
        asks.sort(Comparator.comparingDouble(Level::price));

        if (bids.isEmpty() || asks.isEmpty()) {
            flags |= BookFlags.EMPTY_SIDE;
        } else if (asks.get(0).price() <= bids.get(0).price()) {
            flags |= BookFlags.CROSSED;
        }
        if (bids.size() < expectedDepth || asks.size() < expectedDepth) {
            flags |= BookFlags.PARTIAL_DEPTH;
        }

        JsonNode ts = root.path("metadata").path("timestamp");
        return new Book(bids, asks, ts.isNumber() ? ts.asLong() : null, flags);
    }

    private static List<Level> levels(JsonNode array) {
        List<Level> out = new ArrayList<>();
        if (!array.isArray()) {
            return out;
        }
        for (JsonNode n : array) {
            // Две схемы одного и того же. Публичные эндпоинты отдают
            // price/quantity/count, авторизованные — компактные p/q/no
            // (плюс метаданные актива). Цены и объёмы в обеих строками.
            double price = asDouble(first(n, "price", "p"));
            double qty = asDouble(first(n, "quantity", "q"));
            if (Double.isNaN(price) || Double.isNaN(qty)) {
                continue;
            }
            double count = asDouble(first(n, "count", "no"));
            out.add(new Level(price, qty, Double.isNaN(count) ? 0 : (int) count));
        }
        return out;
    }

    /** Первое непустое поле из списка имён — публичная схема или авторизованная. */
    private static JsonNode first(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode v = node.get(name);
            if (v != null && !v.isNull()) {
                return v;
            }
        }
        return null;
    }

    private static double asDouble(JsonNode node) {
        if (node == null || node.isNull()) {
            return Double.NaN;
        }
        try {
            return node.isNumber() ? node.asDouble() : Double.parseDouble(node.asText());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static boolean isDescending(List<Level> levels) {
        for (int i = 1; i < levels.size(); i++) {
            if (levels.get(i).price() > levels.get(i - 1).price()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAscending(List<Level> levels) {
        for (int i = 1; i < levels.size(); i++) {
            if (levels.get(i).price() < levels.get(i - 1).price()) {
                return false;
            }
        }
        return true;
    }
}
