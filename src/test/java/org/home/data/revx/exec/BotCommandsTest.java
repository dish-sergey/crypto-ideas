package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Три списка команд в {@code ExecBot} должны совпадать: обработчик, меню
 * Telegram ({@code setMyCommands}) и текст {@code /help}.
 *
 * Тест написан после того, как 04.09.2026 команды `/free`, `/claim`, `/release`
 * и `/pnl` добавили в обработчик, а меню не тронули — в клиенте их не стало
 * видно вовсе, и узнали об этом только от человека.
 */
class BotCommandsTest {

    private static final Path SOURCE =
            Path.of("src/main/java/org/home/data/revx/exec/ExecBot.java");

    private static String source() throws Exception {
        return Files.readString(SOURCE);
    }

    /** Команды из {@code switch}: {@code case "/name" ->}. */
    private static Set<String> handled(String src) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("case \"/([a-z]+)\"").matcher(src);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** Команды из меню: {@code {"command":"name"}}. */
    private static Set<String> menu(String src) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\"command\":\"([a-z]+)\"").matcher(src);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    void menuMatchesTheHandler() throws Exception {
        String src = source();
        Set<String> handled = handled(src);
        Set<String> menu = menu(src);
        assertTrue(handled.size() >= 8, "команд подозрительно мало: " + handled);

        Set<String> missingInMenu = new LinkedHashSet<>(handled);
        missingInMenu.removeAll(menu);
        missingInMenu.remove("help");          // /help в меню намеренно не нужен
        assertEquals(Set.of(), missingInMenu,
                "команды есть в обработчике, но их НЕ ВИДНО в меню Telegram: " + missingInMenu);

        Set<String> missingInHandler = new LinkedHashSet<>(menu);
        missingInHandler.removeAll(handled);
        assertEquals(Set.of(), missingInHandler,
                "команды обещаны в меню, но обработчика у них нет: " + missingInHandler);
    }

    @Test
    void helpMentionsEveryCommand() throws Exception {
        String src = source();
        String help = src.substring(src.indexOf("private String help()"));
        for (String c : handled(src)) {
            assertTrue(help.contains("/" + c),
                    "команда /" + c + " не описана в /help");
        }
    }
}
