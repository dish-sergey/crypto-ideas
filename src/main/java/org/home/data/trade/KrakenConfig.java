package org.home.data.trade;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ключ и секрет Kraken Futures. Приоритет — env ({@code S5_KRAKEN_KEY}/{@code S5_KRAKEN_SECRET}, прод-микро
 * через systemd), иначе локальный gitignored-файл вида {@code api_key: <...>} / {@code api_secret: <...>}.
 * Секрет в код/логи/git не попадает. Ключи заводить БЕЗ права вывода средств (только торговля).
 */
public record KrakenConfig(String apiKey, String apiSecretB64) {

    public static KrakenConfig load(String filePath) throws Exception {
        String key = System.getenv("S5_KRAKEN_KEY");
        String secret = System.getenv("S5_KRAKEN_SECRET");
        if ((key == null || secret == null) && filePath != null && Files.exists(Path.of(filePath))) {
            for (String line : Files.readAllLines(Path.of(filePath))) {
                int i = line.indexOf(':');
                if (i < 0) i = line.indexOf('=');
                if (i < 0) continue;
                String k = line.substring(0, i).trim().toLowerCase();
                String v = line.substring(i + 1).trim();
                if (key == null && (k.equals("api_key") || k.equals("apikey") || k.equals("key") || k.equals("s5_kraken_key"))) key = v;
                if (secret == null && (k.equals("api_secret") || k.equals("apisecret") || k.equals("secret") || k.equals("s5_kraken_secret"))) secret = v;
            }
        }
        if (key == null || key.isBlank()) throw new IllegalStateException("нет Kraken key (env S5_KRAKEN_KEY или " + filePath + ")");
        if (secret == null || secret.isBlank()) throw new IllegalStateException("нет Kraken secret (env S5_KRAKEN_SECRET или " + filePath + ")");
        return new KrakenConfig(key.trim(), secret.trim());
    }
}
