package org.home.data.revx.exec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * БУМАЖНЫЙ ХЕДЖ: знак, размер и откуп — то, что на живом стоило бы денег.
 */
class HedgePaperTest {

    private static final InfoBot.Watched BOT = new InfoBot.Watched("f", "SOL/USDC", "нет");
    private static final double LOT = 0.01888;

    private Connection open;

    @AfterEach
    void close() throws Exception {
        if (open != null) {
            open.close();
        }
    }

    private HedgePaper paper(Path dir) throws Exception {
        HedgePaper p = new HedgePaper(List.of(), dir.resolve("paper.db").toString());
        var f = HedgePaper.class.getDeclaredField("db");
        f.setAccessible(true);
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("paper.db"));
        for (String ddl : HedgePaper.SCHEMA.split(";")) {
            if (!ddl.isBlank()) {
                c.createStatement().execute(ddl);
            }
        }
        f.set(p, c);
        open = c;
        return p;
    }

    private static HedgePaper.Quote q(double bid, double ask, long lastTime) {
        return new HedgePaper.Quote(bid, ask, (bid + ask) / 2, lastTime, (bid + ask) / 2, 0);
    }

    private static double perp(Path dir, String rule) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("paper.db"));
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT perp FROM paper_state WHERE rule = '" + rule + "'")) {
            return rs.next() ? rs.getDouble(1) : Double.NaN;
        }
    }

    /** Запас 3 лота, полоса 1: шортим до −3 лотов (к нулю до шага), потом откупаем. */
    @Test
    void шортПоЗапасуИОткуп(@TempDir Path dir) throws Exception {
        HedgePaper p = paper(dir);
        HedgePaper.Rule band = HedgePaper.RULES.get(0);
        double inv = 3 * LOT;                                         // 0.05664
        p.step(BOT, band, inv, LOT, q(100, 100.02, 0), 1_000, 0);     // решение
        // через 31 с встречная цена не перешла — тейкером
        p.step(BOT, band, inv, LOT, q(100, 100.02, 0), 32_000, 0);
        assertEquals(-0.05, perp(dir, band.name()), 1e-12);          // 0.05664 → вниз до 0.05
        // бот всё продал — откупаем
        p.step(BOT, band, 0, LOT, q(100, 100.02, 0), 40_000, 0);
        p.step(BOT, band, 0, LOT, q(100, 100.02, 0), 80_000, 0);
        assertEquals(0.0, perp(dir, band.name()), 1e-12);
    }

    /** Излишек сверх 2 лотов: при 3 лотах страхуется только один. */
    @Test
    void излишекТолькоСверхПорога(@TempDir Path dir) throws Exception {
        HedgePaper p = paper(dir);
        HedgePaper.Rule excess = HedgePaper.RULES.get(2);
        p.step(BOT, excess, 4 * LOT, LOT, q(100, 100.02, 0), 1_000, 0);
        p.step(BOT, excess, 4 * LOT, LOT, q(100, 100.02, 0), 32_000, 0);
        // 4 лота − 2 = 0.03776 → к нулю до шага 0.01 → 0.03
        assertEquals(-0.03, perp(dir, excess.name()), 1e-12);
    }

    /** Мейкер: встречная цена перешла за нашу — исполнение по нашей цене. */
    @Test
    void мейкерКогдаЦенаПрошлаСквозь(@TempDir Path dir) throws Exception {
        HedgePaper p = paper(dir);
        HedgePaper.Rule band = HedgePaper.RULES.get(0);
        p.step(BOT, band, 3 * LOT, LOT, q(100, 100.02, 0), 1_000, 0);  // продажа по аску 100.02
        p.step(BOT, band, 3 * LOT, LOT, q(100.03, 100.05, 0), 6_000, 0);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("paper.db"));
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT makers, trades FROM paper_state WHERE rule = '" + band.name() + "'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals(1, rs.getInt(2));
        }
    }
}
