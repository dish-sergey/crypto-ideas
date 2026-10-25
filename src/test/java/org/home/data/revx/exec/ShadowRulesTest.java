package org.home.data.revx.exec;

import org.home.data.revx.sim.Quoter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Теневые правила (док. 193 шаг 7): часовой тренд и «нож» по истории опоры, без площадки. */
class ShadowRulesTest {

    @TempDir
    Path dir;

    @Test
    void trendAndKnifeFollowFairHistory() throws Exception {
        BnbStream s = new BnbStream("ADAUSDT", "USDCUSDT");
        FastBnbFair fair = new FastBnbFair(s, "ADA/USDC", "ADAUSDT", "USDCUSDT", dir.resolve("нет.db").toString());
        ExecJournal journal = new ExecJournal(dir.resolve("s.db").toString());
        Quoter.Params params = new Quoter.Params(0.0030, 40, 120, 0.0, 0.0003, 0.00001);
        QuoteLoop loop = new QuoteLoop(new DryVenue("ADA/USDC", 100), Clock.system(), fair, journal, params, "ADA/USDC",
                5_000, 0.1, new BotTag("g"), null, true, 0, 0.1, 0.10, null, 1, 0.0002, false);
        try {
            long t = 1_000_000_000L;
            for (int i = 0; i < 3600; i++, t += 1000) {
                loop.rememberFair(t, 100);
                assertEquals("", loop.shadowRules(t, 100, false));
            }
            // −40 б.п. за минуту: часовой тренд 20 и 30 сработал, 50 нет; нож включился
            String st = "";
            for (int i = 1; i <= 60; i++, t += 1000) {
                double f = 100 * (1 - 0.0040 * i / 60);
                loop.rememberFair(t, f);
                st = loop.shadowRules(t, f, false);
            }
            assertEquals("tn20 tn30 knife", st);
            // стоит на минимуме: нож держится, пока нет нового минимума 5 мин
            for (int i = 0; i < 290; i++, t += 1000) {
                loop.rememberFair(t, 99.6);
                st = loop.shadowRules(t, 99.6, false);
            }
            assertEquals("tn20 tn30 knife", st);
            for (int i = 0; i < 20; i++, t += 1000) {
                loop.rememberFair(t, 99.6);
                st = loop.shadowRules(t, 99.6, false);
            }
            assertEquals("tn20 tn30", st, "5 мин без нового минимума — нож снят");
            // новый обвал, затем отскок на 15 б.п. от минимума снимает нож сразу
            for (int i = 1; i <= 30; i++, t += 1000) {
                double f = 99.6 * (1 - 0.0035 * i / 30);
                loop.rememberFair(t, f);
                st = loop.shadowRules(t, f, false);
            }
            assertEquals("tn20 tn30 tn50 knife", st);
            double low = 99.6 * (1 - 0.0035);
            loop.rememberFair(t, low * 1.0016);
            assertEquals("tn20 tn30 tn50", loop.shadowRules(t, low * 1.0016, false));
        } finally {
            journal.close();
        }
    }
}
