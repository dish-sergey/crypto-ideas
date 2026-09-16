package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * СНЯТИЕ ОСИРОТЕВШЕЙ ПРЕТЕНЗИИ: предохранитель важнее самого действия.
 *
 * <h2>Зачем команда</h2>
 *
 * {@code /release} отдаёт только ТЕКУЩУЮ пару бота. Бот {@code d} торговал ADA,
 * его перевели на ETH, а претензия {@code d → 59.7754 ADA} осталась в реестре и
 * не истекает: аренда продлевается по метке бота, а процесс жив. Снять её было
 * нечем.
 *
 * <h2>Что здесь проверяется</h2>
 *
 * Не арифметика — она в {@link AllocRegistry}, — а граница между «сирота» и
 * «живой инвентарь». Ошибка в эту сторону дорогая: снять претензию на монету,
 * которой бот торгует прямо сейчас, значит отдать в общий котёл его позицию, и
 * следующий {@code /claim} соседа заберёт её себе.
 */
class ReleaseStaleGuardTest {

    /** Та же проверка, что в {@code ReleaseStale.run}. */
    private static boolean stale(String coin, String tradedSymbol) {
        String base = tradedSymbol.substring(0, tradedSymbol.indexOf('/'));
        String quote = tradedSymbol.substring(tradedSymbol.indexOf('/') + 1);
        return !coin.equals(base) && !coin.equals(quote);
    }

    @Test
    void coinOfAbandonedPairIsStale() {
        assertTrue(stale("ADA", "ETH/USDC"), "бот ушёл с ADA — претензия осиротела");
    }

    @Test
    void ownBaseIsNotStale() {
        assertTrue(!stale("ETH", "ETH/USDC"), "это живой инвентарь, его отдаёт /release");
    }

    @Test
    void quoteCurrencyIsNotStale() {
        // Касса — тоже живой ресурс: на неё бот покупает. Отдать её мимо /release
        // значит оставить бота без денег посреди работы.
        assertTrue(!stale("USDC", "ETH/USDC"));
    }

    /**
     * Реестр действительно обнуляет претензию и не трогает соседей.
     *
     * Проверяется на настоящем файле, а не на заглушке: цена ошибки — чужой
     * инвентарь, и подменять здесь реализацию значило бы проверять собственную
     * выдумку.
     */
    @Test
    void releaseZeroesOnlyThatClaim(@TempDir Path dir) {
        String path = dir.resolve("alloc.db").toString();
        long now = System.currentTimeMillis();
        try (AllocRegistry registry = new AllocRegistry(path)) {
            registry.claim("d", "ADA", 59.7754, 100.0, 1.0, now);
            registry.claim("d", "ETH", 0.00082648, 1.0, 2400.0, now);
            registry.claim("c", "ADA", 10.0, 100.0, 1.0, now);

            registry.release("d", "ADA", 0, now);

            assertEquals(0.0, registry.own("d", "ADA"), 1e-12, "претензия снята");
            assertEquals(0.00082648, registry.own("d", "ETH"), 1e-12,
                    "живой инвентарь того же бота не тронут");
            assertEquals(10.0, registry.own("c", "ADA"), 1e-12,
                    "претензия соседа на ту же монету не тронута");
        }
    }
}
