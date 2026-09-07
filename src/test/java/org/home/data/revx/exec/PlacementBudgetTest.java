package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Общее ведро постановок: то, что делит суточный лимит между шестью ботами.
 *
 * Тест сторожит три свойства, каждое из которых уже стоило простоя или могло
 * стоить денег:
 *
 * <ol>
 *   <li>ведро не выдаёт больше своей ёмкости — иначе шестеро пробьют лимит
 *       аккаунта, и торговля встанет у всех разом;</li>
 *   <li>прожорливый сосед не может съесть чужой пол — ровно этого не хватало
 *       07.09.2026, когда ADA простояла 11 часов при 240 свободных постановках
 *       у SOL и ETH;</li>
 *   <li>пополнение идёт по времени, а не скачком в полночь — потому что у
 *       площадки ведро токенов, и это установлено замером, а не догадкой.</li>
 * </ol>
 */
class PlacementBudgetTest {

    private static final long T0 = 1_700_000_000_000L;

    private PlacementBudget open(Path dir, int bots) {
        return new PlacementBudget(dir.resolve("alloc.db").toString(), bots);
    }

    @Test
    void ведроНеВыдаётБольшеЁмкости(@TempDir Path dir) {
        try (PlacementBudget budget = open(dir, 1)) {
            int granted = 0;
            for (int i = 0; i < (int) PlacementBudget.CAPACITY + 50; i++) {
                if (budget.tryAcquire("a", T0)) {
                    granted++;
                }
            }
            assertEquals((int) PlacementBudget.CAPACITY, granted,
                    "выдано больше, чем есть в ведре");
            assertFalse(budget.tryAcquire("a", T0), "пустое ведро всё ещё выдаёт");
        }
    }

    @Test
    void пополнениеИдётПоВремени(@TempDir Path dir) {
        try (PlacementBudget budget = open(dir, 1)) {
            while (budget.tryAcquire("a", T0)) {
                // вычерпываем досуха
            }
            assertFalse(budget.tryAcquire("a", T0));

            // Час — это 1/24 ёмкости, то есть около 35 токенов при 850.
            long hour = 3_600_000L;
            assertTrue(budget.tryAcquire("a", T0 + hour), "за час не набежало ни токена");
            double expected = PlacementBudget.CAPACITY / 24.0;
            PlacementBudget.State state = budget.state("a", T0 + hour);
            assertTrue(Math.abs(state.tokens() - (expected - 1)) < 2.0,
                    "за час набежало " + state.tokens() + ", ждали около " + (expected - 1));
        }
    }

    @Test
    void часыНазадНеНачисляют(@TempDir Path dir) {
        try (PlacementBudget budget = open(dir, 1)) {
            budget.tryAcquire("a", T0);
            double before = budget.state("a", T0).tokens();
            double after = budget.state("a", T0 - 3_600_000L).tokens();
            assertTrue(after <= before + 0.001,
                    "ход часов назад начислил токены: было " + before + ", стало " + after);
        }
    }

    @Test
    void прожорливыйНеСъедаетЧужойПол(@TempDir Path dir) {
        try (PlacementBudget budget = open(dir, 6)) {
            // Бот "a" берёт всё, что может. Остальные пятеро ещё не ставили ни
            // разу, значит за ними числится по полу каждому.
            int granted = 0;
            while (budget.tryAcquire("a", T0)) {
                granted++;
            }
            int reserved = 5 * PlacementBudget.FLOOR_PER_DAY;
            int room = (int) PlacementBudget.CAPACITY - reserved;
            assertTrue(granted <= room + 1,
                    "жадный бот взял " + granted + " при потолке " + room);
            assertTrue(granted >= room - 1,
                    "жадный бот взял всего " + granted + ", а мог " + room);

            // И теперь каждый из пятерых обязан получить свой пол целиком.
            for (String other : new String[]{"b", "c", "d", "e", "f"}) {
                int mine = 0;
                for (int i = 0; i < PlacementBudget.FLOOR_PER_DAY; i++) {
                    if (budget.tryAcquire(other, T0)) {
                        mine++;
                    }
                }
                assertEquals(PlacementBudget.FLOOR_PER_DAY, mine,
                        "бот " + other + " не получил свой гарантированный пол");
            }
        }
    }

    @Test
    void давлениеРастётПоМереРасхода(@TempDir Path dir) {
        try (PlacementBudget budget = open(dir, 6)) {
            assertEquals(0.0, budget.state("a", T0).pressure(), 0.01,
                    "полное ведро не должно давить");
            while (budget.tryAcquire("a", T0)) {
                // вычерпываем всё, что доступно сверх чужих полов
            }
            assertTrue(budget.state("a", T0).pressure() > 0.99,
                    "выбранный котёл обязан давить в полную силу");
        }
    }

    @Test
    void расходВидятВсеПроцессы(@TempDir Path dir) {
        // Два соединения к одному файлу — это и есть два бота на машине.
        try (PlacementBudget first = open(dir, 6);
             PlacementBudget second = open(dir, 6)) {
            for (int i = 0; i < 50; i++) {
                assertTrue(first.tryAcquire("a", T0));
            }
            assertEquals(50, second.state("a", T0).ownSpendDay(),
                    "второй процесс не видит расхода первого");
            assertEquals(50, second.state("b", T0).totalSpendDay(),
                    "общий расход по аккаунту считается неверно");
        }
    }

    /**
     * Пока свой пол не выбран, давления быть не должно НИ ПРИ КАКОЙ броне.
     *
     * Замечено на живых ботах 07.09.2026: через минуту после включения все
     * шестеро раздвинули отступ на 8% при ведре, полном на 96%, — давление
     * считалось от чужих невыбранных полов. Отказать боту под своим полом
     * нельзя, значит и тормозить его нечем.
     */
    @Test
    void подСвоимПоломДавленияНет(@TempDir Path dir) {
        try (PlacementBudget budget = open(dir, 6)) {
            // Броня максимальна: пятеро соседей не потратили ни токена.
            for (int i = 0; i < 20; i++) {
                assertTrue(budget.tryAcquire("a", T0));
            }
            assertEquals(0.0, budget.state("a", T0).pressure(), 1e-9,
                    "бот под своим полом обязан котировать без раздвижения");
        }
    }

    @Test
    void сломанноеВедроЗапрещаетПостановку(@TempDir Path dir) {
        PlacementBudget budget = open(dir, 6);
        budget.close();
        // Закрытое соединение — это модель недоступной базы. Неизвестность
        // обязана останавливать торговлю, а не разрешать её.
        assertFalse(budget.tryAcquire("a", T0), "сломанное ведро разрешило постановку");
        assertEquals(0.0, budget.state("a", T0).tokens(), 0.001);
        assertEquals(1.0, budget.state("a", T0).pressure(), 0.001,
                "сломанное ведро обязано выглядеть как полный дефицит");
    }
}
