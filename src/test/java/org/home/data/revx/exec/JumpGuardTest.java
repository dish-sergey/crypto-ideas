package org.home.data.revx.exec;

import org.home.data.revx.sim.Quoter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Пилот ADA целиком, без площадки: котировщик на быстрой опоре против {@link DryVenue}.
 * Рывок Бинанса против бида обязан СНЯТЬ заявку (отмена, а не замена) и не ставить
 * новую, пока рывок не утих; тик будит сама опора, а не часы.
 */
class JumpGuardTest {

    @TempDir
    Path dir;

    private static void await(BooleanSupplier cond, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
    }

    /** Есть ли стоящая заявка стороны по цене (с допуском на хвост двоичного округления). */
    private static boolean has(java.util.List<String> live, String side, double price) {
        return live.stream().anyMatch(x -> x.startsWith(side + "@")
                && Math.abs(Double.parseDouble(x.substring(side.length() + 1)) - price) <= 1.0001e-5);
        // ↑ один тик ADA (0.00001): цена округляется по шагу В СТОРОНУ ОТ РЫНКА, и хвост
        // двоичной дроби (25050.0000001) поднимает аск на тик — 0.4 б.п., так у всех ботов.
    }

    @Test
    void jumpAgainstBidCancelsAndHolds() throws Exception {
        BnbStream s = new BnbStream("ADAUSDT", "USDCUSDT");
        FastBnbFair fair = new FastBnbFair(s, "ADA/USDC", "ADAUSDT", "USDCUSDT", dir.resolve("нет.db").toString());
        DryVenue venue = new DryVenue("ADA/USDC", 100);
        ExecJournal journal = new ExecJournal(dir.resolve("g.db").toString());
        // Отступ 30 б.п., лот 40 ADA, потолок 120, без скоса; таймер 5 с — тики должна будить опора.
        Quoter.Params params = new Quoter.Params(0.0030, 40, 120, 0.0, 0.0003, 0.00001);
        QuoteLoop loop = new QuoteLoop(venue, Clock.system(), fair, journal, params, "ADA/USDC", 5_000, 0.1,
                new BotTag("g"), null, true, 0, 0.1, 0.10, null, 1, 0.0002, false);
        loop.jumpGuard(20, 1_000, 2_000);
        long now = System.currentTimeMillis();
        s.inject("USDCUSDT", 1.0, 1.0, now);
        s.inject("ADAUSDT", 0.25, 0.25, now);
        Thread t = new Thread(loop, "revx-quote-loop");
        t.start();
        try {
            loop.startQuoting();
            await(() -> venue.stats().startsWith("постановок 1"), 8_000);
            assertTrue(venue.stats().startsWith("постановок 1"), "бид не встал: " + venue.stats());

            // Мелкое движение (+1 б.п.) — меньше порога перестановки 3 б.п.: ни замены, ни отмены.
            s.inject("ADAUSDT", 0.250025, 0.250025, System.currentTimeMillis());
            Thread.sleep(600);
            assertEquals(0, loop.jumpCancels());

            // Рывок −24 б.п. за долю секунды — бид снимается отменой.
            s.inject("ADAUSDT", 0.2494, 0.2494, System.currentTimeMillis());
            await(() -> loop.jumpCancels() > 0, 3_000);
            assertEquals(1, loop.jumpCancels(), "рывок против бида обязан снять заявку");
            assertTrue(venue.stats().contains("отмен 1"), venue.stats());
            assertTrue(venue.stats().endsWith("стоит 0"), "после отмены в книге ничего: " + venue.stats());
            assertTrue(loop.wakeups() > 0, "тик будила опора, а не часы");

            // Пока рывок не утих (2 с) и судьба снятой не выяснена — новой заявки нет.
            Thread.sleep(1_000);
            assertTrue(venue.stats().startsWith("постановок 1"), "во время рывка поставили снова: " + venue.stats());
        } finally {
            loop.shutdown();
            t.join(5_000);
            journal.close();
        }
    }

    /**
     * Аск на своём отступе (опора +20 при биде −30) и бумажный шорт по запасу: при
     * запасе 40 ADA шорт −40, рост опоры на 4 б.п. — минус в хедже, плюс пошлина 6 б.п.
     */
    @Test
    void askOffsetAndPaperHedge() throws Exception {
        BnbStream s = new BnbStream("ADAUSDT", "USDCUSDT");
        FastBnbFair fair = new FastBnbFair(s, "ADA/USDC", "ADAUSDT", "USDCUSDT", dir.resolve("нет.db").toString());
        DryVenue venue = new DryVenue("ADA/USDC", 100, 40);
        ExecJournal journal = new ExecJournal(dir.resolve("g2.db").toString());
        Quoter.Params params = new Quoter.Params(0.0030, 40, 120, 0.0, 0.0003, 0.00001);
        // Запас при первом старте — 40 ADA (position-seed), чтобы было что продавать.
        QuoteLoop loop = new QuoteLoop(venue, Clock.system(), fair, journal, params, "ADA/USDC", 5_000, 0.1,
                new BotTag("g"), null, true, 40, 0.1, 0.10, null, 1, 0.0002, false);
        loop.askOffsetBp(20);
        loop.hedgePaperStep(1);
        long now = System.currentTimeMillis();
        s.inject("USDCUSDT", 1.0, 1.0, now);
        s.inject("ADAUSDT", 0.25, 0.25, now);
        Thread t = new Thread(loop, "revx-quote-loop");
        t.start();
        try {
            loop.startQuoting();
            await(() -> venue.live().size() == 2, 8_000);
            java.util.List<String> live = venue.live();
            assertTrue(has(live, "buy", 0.24925), "бид на опоре −30: " + live);
            assertTrue(has(live, "sell", 0.2505), "аск на опоре +20, а не +30: " + live);
            assertTrue(loop.paperHedgeState().startsWith("шорт -40"), "шорт = −запас: " + loop.paperHedgeState());

            // Опора +4 б.п. (меньше порога пробуждения нет — 2 б.п.): шорт теряет 40 × 0.0001.
            s.inject("ADAUSDT", 0.2501, 0.2501, System.currentTimeMillis());
            await(() -> loop.paperHedgePnl() < -0.0059 - 0.001, 3_000);
            // −0.004 переоценки и −0.006 пошлины на открытие (40 × 0.25 × 6 б.п.).
            assertEquals(-0.010, loop.paperHedgePnl(), 1e-6, loop.paperHedgeState());
        } finally {
            loop.shutdown();
            t.join(5_000);
            journal.close();
        }
    }
}
