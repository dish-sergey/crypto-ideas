package org.home.data.revx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@code --revx-depth-probe}: сколько уровней книги площадка отдаёт на самом деле.
 *
 * <h2>Зачем</h2>
 *
 * Мы собираем пять уровней, и 10.09.2026 счётчики отсева показали, во что это
 * обходится: на BTC при отступе 12 б.п. модель теряет 19% дошедших до заявки
 * принтов «по невидимости», а при 14 б.п. — 38%. Заявка просто уходит за пятый
 * собранный уровень, и глубже данных нет. То есть вся правая часть лестницы
 * отступов систематически занижена, и это предел ДАННЫХ, а не поведения.
 *
 * <h2>Что уже известно</h2>
 *
 * ⚠️ На ПУБЛИЧНОМ пути параметр {@code limit} игнорируется: при 5, 10, 20, 50 и
 * 100 приходит один и тот же ответ в 1975 байт с ровно пятью уровнями на
 * сторону (проверено 10.09.2026 с локальной машины). Значит вопрос осмыслен
 * только для АВТОРИЗОВАННОГО пути, а ключ есть лишь на bot-arm — отсюда и
 * отдельная команда вместо разовой проверки руками.
 *
 * Зонд только читает: ни одной заявки, ни одной записи в базу. Ответы
 * логируются числом уровней и размером, чтобы сразу было видно и потолок
 * глубины, и во что обойдётся трафик.
 */
@Component
@Lazy
public class DepthProbe {

    private static final Logger log = LoggerFactory.getLogger(DepthProbe.class);

    private static final int[] LIMITS = {5, 10, 20, 50, 100, 200};

    private final RevxEndpoints endpoints;
    private final RevxHttp http;
    private final ObjectMapper mapper = new ObjectMapper();

    public DepthProbe(RevxEndpoints endpoints, RevxHttp http) {
        this.endpoints = endpoints;
        this.http = http;
    }

    /**
     * НА СКОЛЬКО БАЗИСНЫХ ПУНКТОВ от середины хватает этой глубины.
     *
     * Это и есть число, по которому выбирается настройка: заявка глубже
     * последнего собранного уровня для модели невидима, а значит ступени
     * лестницы шире охвата занижены систематически.
     */
    private String spanBp(String body, boolean bidSide) {
        try {
            JsonNode data = mapper.readTree(body).path("data");
            JsonNode bids = data.path("bids");
            JsonNode asks = data.path("asks");
            if (bids.isEmpty() || asks.isEmpty()) {
                return "нет";
            }
            // ⚠️ asks приходят по УБЫВАНИЮ (проверено на 324 снимках): лучший
            // аск — последний, а не первый. Наивное чтение завышает спред.
            double bestBid = bids.get(0).path("p").asDouble();
            double bestAsk = asks.get(asks.size() - 1).path("p").asDouble();
            double mid = (bestBid + bestAsk) / 2;
            if (!(mid > 0)) {
                return "нет";
            }
            double deepest = bidSide
                    ? bids.get(bids.size() - 1).path("p").asDouble()
                    : asks.get(0).path("p").asDouble();
            return String.format(java.util.Locale.ROOT, "%.1f б.п.",
                    Math.abs(deepest - mid) / mid * 1e4);
        } catch (Exception e) {
            return "нет";
        }
    }

    public void run(String pathSymbol) {
        log.warn("зонд глубины книги по {}: путь {}, ключ {}",
                pathSymbol, endpoints.authenticated() ? "авторизованный" : "публичный",
                endpoints.authenticated() ? "есть" : "нет");
        for (int limit : LIMITS) {
            String url = endpoints.book(pathSymbol).replaceAll("limit=[0-9]+", "limit=" + limit);
            RevxHttp.Response r = http.get(url);
            if (!r.ok()) {
                log.warn("  limit={}: HTTP {} {}", limit, r.status(),
                        r.error() == null ? "" : r.error());
                continue;
            }
            int bids = -1;
            int asks = -1;
            try {
                JsonNode data = mapper.readTree(r.body()).path("data");
                bids = data.path("bids").size();
                asks = data.path("asks").size();
            } catch (Exception e) {
                log.warn("  limit={}: ответ не разобрался — {}", limit, e.toString());
            }
            log.warn("  limit={}: уровней бид {}, аск {}, байт {}, охват от середины: бид {}, аск {}",
                    limit, bids, asks, r.body() == null ? 0 : r.body().length(),
                    spanBp(r.body(), true), spanBp(r.body(), false));
        }
        log.warn("⚠️ если глубина не растёт — она у площадки и есть предел, "
                + "и правая часть лестницы отступов не чинится сбором");
    }
}
