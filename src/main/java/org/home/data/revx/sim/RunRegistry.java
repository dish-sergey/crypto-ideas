package org.home.data.revx.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.home.data.revx.RevxDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Запись прогонов. ТЗ §2: каждый прогон симулятора пишет git-хэш, полный конфиг,
 * диапазон данных, версию модели исполнения и результат — БЕЗ ЭТОЙ ЗАПИСИ ПРОГОН
 * НЕДЕЙСТВИТЕЛЕН.
 *
 * Хэш подставляется в ресурс при сборке (build.gradle) и получает суффикс
 * {@code -dirty}, если дерево не закоммичено: прогон незакоммиченным кодом
 * воспроизвести нельзя, и делать вид, что можно, — самообман.
 */
@Component
@Lazy
public class RunRegistry {

    private static final Logger log = LoggerFactory.getLogger(RunRegistry.class);

    /** Версия модели исполнения. Менять при любом изменении правил §4.3. */
    public static final String MODEL_VERSION = "exec-1.0";

    private static final String INSERT = """
            INSERT INTO revx_run(run_id, created_ms, git_hash, model_version, label, symbol,
                                 data_from_ms, data_to_ms, config_json, result_json)
            VALUES(?,?,?,?,?,?,?,?,?,?)
            """;

    private final RevxDb db;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String gitHash;

    public RunRegistry(RevxDb db) {
        this.db = db;
        this.gitHash = readGitHash();
    }

    public String gitHash() {
        return gitHash;
    }

    public String record(String label, String symbol, long fromMs, long toMs,
                         Map<String, Object> config, Map<String, Object> result) {
        String runId = UUID.randomUUID().toString();
        db.upsert(INSERT, runId, System.currentTimeMillis(), gitHash, MODEL_VERSION, label, symbol,
                fromMs, toMs, json(config), json(result));
        return runId;
    }

    private String json(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("не сериализуется запись прогона", e);
        }
    }

    private static String readGitHash() {
        try (InputStream in = RunRegistry.class.getResourceAsStream("/revx-build.properties")) {
            if (in == null) {
                return "unknown";
            }
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("git.hash", "unknown");
        } catch (IOException e) {
            log.warn("не прочитался git-хэш сборки: {}", e.getMessage());
            return "unknown";
        }
    }
}
