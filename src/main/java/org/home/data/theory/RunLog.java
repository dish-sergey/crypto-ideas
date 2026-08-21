package org.home.data.theory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Запись прогона счётного стенда. Требование §2 всех четырёх ТЗ (65–68):
 * git-хэш, полный конфиг, диапазон данных, версия алгоритма, seed, результат —
 * <b>без записи прогон недействителен</b>, потому что через неделю не ответить,
 * каким кодом и на каких данных получено число.
 *
 * Хэш подставляется в ресурс при сборке (build.gradle) и получает суффикс
 * {@code -dirty} на незакоммиченном дереве: такой прогон невоспроизводим,
 * и делать вид, что воспроизводим, — самообман.
 */
@Component
@Lazy
public class RunLog {

    private static final Logger log = LoggerFactory.getLogger(RunLog.class);

    private static final String INSERT = """
            INSERT OR REPLACE INTO theory_run(run_id, created_ms, git_hash, module, algo_version,
                                              label, data_from_ms, data_to_ms, seed,
                                              config_json, result_json)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """;

    private final TheoryDb db;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String gitHash;

    public RunLog(TheoryDb db) {
        this.db = db;
        this.gitHash = readGitHash();
    }

    public String gitHash() {
        return gitHash;
    }

    /** Пишет прогон и возвращает его id. */
    public String record(String module, String algoVersion, String label,
                         long dataFromMs, long dataToMs, long seed,
                         Map<String, ?> config, Map<String, ?> result) {
        String runId = UUID.randomUUID().toString();
        db.upsert(INSERT, runId, System.currentTimeMillis(), gitHash, module, algoVersion, label,
                dataFromMs, dataToMs, seed, json(config), json(result));
        Jlog.info(log, "theory.run", Map.of("run_id", runId, "module", module,
                "algo", algoVersion, "git", gitHash, "label", label == null ? "" : label));
        return runId;
    }

    /** Конфиг+результат в одну карту для шапки отчёта. */
    public Map<String, Object> header(String module, String algoVersion) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("module", module);
        h.put("algo_version", algoVersion);
        h.put("git_hash", gitHash);
        return h;
    }

    private String json(Map<String, ?> m) {
        try {
            return mapper.writeValueAsString(m == null ? Map.of() : m);
        } catch (Exception e) {
            return "{\"serialization_error\":\"" + e.getMessage() + "\"}";
        }
    }

    private static String readGitHash() {
        try (InputStream in = RunLog.class.getResourceAsStream("/revx-build.properties")) {
            if (in == null) {
                return "unknown";
            }
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("git.hash", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }
}
