package org.home.data.revx.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Этап 3б: списочные GET из снимка читателя — только свежего и начатого после нашей записи. */
class VenueReadsTest {

    /** Площадка-заглушка: считает свои GET. */
    private static final class Fake implements Venue {
        final AtomicInteger gets = new AtomicInteger();

        public Response activeOrders() {
            gets.incrementAndGet();
            return new Response(200, "{\"data\":[\"сама\"]}", 100);
        }

        public Response balances() {
            gets.incrementAndGet();
            return new Response(200, "[\"сама\"]", 100);
        }

        public Response order(String id) {
            return new Response(200, "{}", 100);
        }

        public Response place(String json) {
            return new Response(200, "{}", 100);
        }

        public Response replace(String id, String json) {
            return new Response(200, "{}", 100);
        }

        public Response cancel(String id) {
            return new Response(204, null, 100);
        }
    }

    @Test
    void правилоСвежести() {
        assertTrue(VenueReads.usable(1_000, 900, 2_000, 3_000));
        assertFalse(VenueReads.usable(1_000, 1_000, 2_000, 3_000));   // снят не позже нашей записи
        assertFalse(VenueReads.usable(1_000, 900, 5_000, 3_000));     // слишком стар
    }

    @Test
    void снимокПослеЗаписиБерётсяИначеСпрашиваемСами(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("venue.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            for (String ddl : VenueReader.SCHEMA.split(";")) {
                if (!ddl.isBlank()) {
                    st.execute(ddl);
                }
            }
            st.execute("INSERT INTO raw_response VALUES('orders/active', 10000, '{\"data\":[\"снимок\"]}')");
        }
        AtomicLong now = new AtomicLong(9_000);
        Fake fake = new Fake();
        VenueReads reads = new VenueReads(fake, db.toString(), now::get);

        reads.replace("x", "{}");                     // запись в 9000
        now.set(11_000);
        assertEquals("{\"data\":[\"снимок\"]}", reads.activeOrders().body());   // снимок в 10000 — после
        assertEquals(0, fake.gets.get());

        reads.replace("y", "{}");                     // запись в 11000 — снимок уже старее
        assertEquals("{\"data\":[\"сама\"]}", reads.activeOrders().body());
        assertEquals(1, fake.gets.get());

        assertEquals("[\"сама\"]", reads.balances().body());   // остатков в снимке нет — сами
        assertEquals(2, fake.gets.get());
    }
}
