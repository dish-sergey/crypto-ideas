package org.home.data.revx.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Длинная сводка режется по строкам на части, которые Telegram примет (≤ 4096). */
class InfoBotChunksTest {

    @Test
    void shortTextIsOnePart() {
        assertEquals(List.of("a\nb"), InfoBot.chunks("a\nb", 10));
    }

    @Test
    void splitsAtLineEndsAndKeepsEverything() {
        String text = "1234\n5678\n90";
        List<String> parts = InfoBot.chunks(text, 9);
        assertEquals(List.of("1234\n5678", "90"), parts);
        assertEquals(text, String.join("\n", parts));
    }

    @Test
    void overlongLineIsCut() {
        List<String> parts = InfoBot.chunks("x\n" + "y".repeat(25), 10);
        parts.forEach(p -> assertTrue(p.length() <= 10, p));
        assertEquals("x" + "y".repeat(25), String.join("", parts));
    }
}
