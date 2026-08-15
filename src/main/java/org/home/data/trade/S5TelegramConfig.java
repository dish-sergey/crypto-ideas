package org.home.data.trade;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Токен и chat_id Telegram-бота. Приоритет — env ({@code S5_TELEGRAM_TOKEN}/{@code S5_TELEGRAM_CHAT},
 * так на проде-микро через systemd), иначе локальный gitignored-файл вида
 * {@code access_token: <...>} / {@code chat_id: <...>}. Секрет в код/логи/git не попадает.
 */
public record S5TelegramConfig(String token, long chatId) {

    public static S5TelegramConfig load(String filePath) throws Exception {
        String token = System.getenv("S5_TELEGRAM_TOKEN");
        String chat = System.getenv("S5_TELEGRAM_CHAT");
        if ((token == null || chat == null) && filePath != null && Files.exists(Path.of(filePath))) {
            for (String line : Files.readAllLines(Path.of(filePath))) {
                int i = line.indexOf(':');
                if (i < 0) i = line.indexOf('=');
                if (i < 0) continue;
                String k = line.substring(0, i).trim().toLowerCase();
                String v = line.substring(i + 1).trim();               // токен содержит ':' — берём всё после первого
                if (token == null && (k.equals("access_token") || k.equals("token") || k.equals("s5_telegram_token"))) token = v;
                if (chat == null && k.contains("chat")) chat = v;
            }
        }
        if (token == null || token.isBlank())
            throw new IllegalStateException("нет Telegram-токена (env S5_TELEGRAM_TOKEN или " + filePath + ")");
        if (chat == null || chat.isBlank())
            throw new IllegalStateException("нет chat_id (env S5_TELEGRAM_CHAT или " + filePath + ")");
        return new S5TelegramConfig(token.trim(), Long.parseLong(chat.trim()));
    }
}
