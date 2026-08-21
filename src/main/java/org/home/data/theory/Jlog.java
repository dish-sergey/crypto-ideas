package org.home.data.theory;

import org.slf4j.Logger;

import java.util.Map;

/**
 * Структурное логирование одной строкой JSON (§2 ТЗ 65–68: «логирование
 * структурное (JSON-строки), уровень настраивается»). Уровень — обычными
 * средствами slf4j/Spring (logging.level.org.home.data.theory=...).
 *
 * Своя мини-реализация вместо logstash-encoder: одна зависимость на четыре
 * строки кода — не тот размен, а формат нужен машиночитаемый, не красивый.
 */
public final class Jlog {

    private Jlog() {
    }

    public static void info(Logger log, String event, Map<String, ?> fields) {
        if (log.isInfoEnabled()) {
            log.info(line(event, fields));
        }
    }

    public static void warn(Logger log, String event, Map<String, ?> fields) {
        if (log.isWarnEnabled()) {
            log.warn(line(event, fields));
        }
    }

    public static void debug(Logger log, String event, Map<String, ?> fields) {
        if (log.isDebugEnabled()) {
            log.debug(line(event, fields));
        }
    }

    static String line(String event, Map<String, ?> fields) {
        StringBuilder sb = new StringBuilder(64).append("{\"event\":\"").append(esc(event)).append('"');
        for (Map.Entry<String, ?> e : fields.entrySet()) {
            sb.append(",\"").append(esc(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(esc(String.valueOf(v))).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\n");
    }
}
