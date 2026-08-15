package org.home.data.trade;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Источник расписаний вестинга (DefiLlama datasets-CDN). Абстракция ради тестируемости:
 * прод — {@link DefiLlamaEmissionSource}, тесты — фикстуры. Формат JSON — как emissions/{slug}.
 */
public interface EmissionSource {
    /** Список slug'ов протоколов с данными о разлоках. */
    List<String> protocols() throws Exception;
    /** JSON расписания для протокола (documentedData + metadata.events). */
    JsonNode emissions(String slug) throws Exception;
}
