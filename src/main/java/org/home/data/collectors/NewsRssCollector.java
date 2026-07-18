package org.home.data.collectors;

import org.home.data.core.ApiClient;
import org.home.data.core.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Сырьё для новостного контура M2 (док. 05): RSS крупных крипто-СМИ.
 * Здесь только сбор и дедупликация по (source, guid); LLM-классификация —
 * отдельный этап поверх таблицы news_items.
 * available_at = момент получения нами (реальная доступность новости системе).
 */
@Component
public class NewsRssCollector implements Collector {

    private static final Logger log = LoggerFactory.getLogger(NewsRssCollector.class);

    private static final String INSERT = """
            INSERT OR IGNORE INTO news_items(source, guid, published_ts, title, url, available_at)
            VALUES(?,?,?,?,?,?)
            """;

    private final Db db;
    private final ApiClient api;
    private final List<String> feeds;

    public NewsRssCollector(Db db, ApiClient api,
                            @Value("${collectors.rss-feeds}") List<String> feeds) {
        this.db = db;
        this.api = api;
        this.feeds = feeds;
    }

    @Override
    public String name() {
        return "news";
    }

    @Override
    public void collect() {
        long now = System.currentTimeMillis();
        for (String feed : feeds) {
            try {
                String source = URI.create(feed).getHost();
                List<Object[]> rows = parseRss(source, api.get(feed), now);
                int added = db.batch(INSERT, rows);
                log.debug("news {}: {} элементов обработано", source, added);
            } catch (Exception e) {
                log.warn("news: фид {} недоступен: {}", feed, e.getMessage());
            }
        }
    }

    /** Минимальный RSS 2.0 парсер: item -> (guid|link, pubDate, title, link). */
    static List<Object[]> parseRss(String source, String xml, long fetchedAt) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setExpandEntityReferences(false);
        Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        NodeList items = doc.getElementsByTagName("item");
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String link = text(item, "link");
            String guid = text(item, "guid");
            if (guid == null) {
                guid = link;
            }
            if (guid == null) {
                continue;
            }
            rows.add(new Object[]{source, guid, parsePubDate(text(item, "pubDate")),
                    text(item, "title"), link, fetchedAt});
        }
        return rows;
    }

    private static String text(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        if (list.getLength() == 0) {
            return null;
        }
        String value = list.item(0).getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Long parsePubDate(String pubDate) {
        if (pubDate == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(pubDate, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
        } catch (java.time.format.DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(pubDate).toInstant().toEpochMilli();
            } catch (java.time.format.DateTimeParseException e2) {
                return null;
            }
        }
    }
}
