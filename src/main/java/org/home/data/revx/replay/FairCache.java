package org.home.data.revx.replay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * КЭШ СПРАВЕДЛИВОЙ ЦЕНЫ ОКНА (17.10.2026). Прогон w24 (SOL, один уровень) шёл 71 минуту, из них
 * 32 — {@link StandFair}: книги всех 23 пар за 24 дня ради курса, 54 ГБ чтения с диска. Эта работа
 * одинакова у всех прогонов одного окна и пары, а делал её каждый. Здесь ряд строится один раз и
 * кладётся в файл; следующие прогоны читают его за секунды.
 *
 * Ключ — всё, от чего ряд зависит: база (путь, размер, время изменения), пара, окно, пределы
 * {@code FairPrice}, мемкоины, допуск перекоса ног и ВСЕ свойства {@code revx.fair.*}, кроме
 * {@code revx.fair.hybrid-*} (гибрид накладывается на ряд позже и в ключ не входит, иначе каждый
 * вариант поводка строил бы ряд заново). Включается {@code -Drevx.forecast.cache-dir=<каталог>};
 * без него всё как раньше.
 */
public final class FairCache {
    private static final Logger log = LoggerFactory.getLogger(FairCache.class);
    private static final int MAGIC = 0x46414952;   // "FAIR"
    private static final int VERSION = 1;

    /** Ряд и число пар в расчёте курса. */
    public record Result(List<ReplayFair.Tick> ticks, int pairs) {
    }

    private FairCache() {
    }

    public static Result ticks(String dbPath, String base, long fromMs, long toMs, String keyExtra,
                               Supplier<StandFair> build) {
        String dir = System.getProperty("revx.forecast.cache-dir", "");
        if (dir.isBlank()) {
            return fresh(build);
        }
        Path file;
        try {
            file = Path.of(dir, "fair-" + base + "-" + key(dbPath, base, fromMs, toMs, keyExtra) + ".bin");
        } catch (Exception e) {
            log.warn("кэш опоры: ключ не посчитан ({}) — строю без кэша", e.getMessage());
            return fresh(build);
        }
        if (Files.exists(file)) {
            try {
                Result r = read(file);
                log.warn("кэш опоры: {} тиков из {}", r.ticks().size(), file.getFileName());
                return r;
            } catch (Exception e) {
                log.warn("кэш опоры {} не прочитан ({}) — строю заново", file.getFileName(), e.getMessage());
            }
        }
        StandFair f = build.get();
        Result r = new Result(f.toTicks(base), f.pairs());
        store(file, r);
        // ОДИН ПРОХОД НА ВСЕ ТОРГУЕМЫЕ (18.10.2026): срез корзины курса — книги всех 23 пар — один
        // и тот же для любой монеты, а читать его из базы стоит минут и десятков ГБ. Раз уж он
        // построен, ряды остальных монет из revx.forecast.fair-also считаются тут же и кладутся в
        // кэш: первый прогон другой монеты окна строить опору уже не будет. Ряд от этого не
        // меняется: toTicks(b) — то же, что StandFair, построенный для b.
        for (String other : System.getProperty("revx.forecast.fair-also", "BTC,ETH,SOL").split(",")) {
            String b = other.trim();
            if (b.isEmpty() || b.equals(base) || !f.bases().contains(b)) {
                continue;
            }
            try {
                Path of = Path.of(dir, "fair-" + b + "-" + key(dbPath, b, fromMs, toMs, keyExtra) + ".bin");
                if (!Files.exists(of)) {
                    store(of, new Result(f.toTicks(b), f.pairs()));
                }
            } catch (Exception e) {
                log.warn("кэш опоры {}: попутный ряд не построен ({})", b, e.getMessage());
            }
        }
        return r;
    }

    private static void store(Path file, Result r) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            write(tmp, r);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.warn("кэш опоры записан: {}", file.getFileName());
        } catch (Exception e) {
            log.warn("кэш опоры не записан: {}", e.getMessage());
        }
    }

    private static Result fresh(Supplier<StandFair> build) {
        StandFair f = build.get();
        return new Result(f.toTicks(), f.pairs());
    }

    static String key(String dbPath, String base, long fromMs, long toMs, String keyExtra) throws Exception {
        Path db = Path.of(dbPath);
        StringBuilder sb = new StringBuilder("v").append(VERSION)
                .append('|').append(db.toAbsolutePath().normalize())
                .append('|').append(Files.size(db)).append('|').append(Files.getLastModifiedTime(db).toMillis())
                .append('|').append(base).append('|').append(fromMs).append('|').append(toMs)
                .append('|').append(keyExtra);
        Map<String, String> props = new TreeMap<>();
        for (String k : System.getProperties().stringPropertyNames()) {
            if (k.startsWith("revx.fair.") && !k.startsWith("revx.fair.hybrid")) {
                props.put(k, System.getProperty(k));
            }
        }
        sb.append('|').append(props);
        byte[] h = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(h, 0, 8);
    }

    static void write(Path file, Result r) throws IOException {
        Map<String, Integer> reasons = new HashMap<>();
        List<String> table = new ArrayList<>();
        for (ReplayFair.Tick t : r.ticks()) {
            if (t.reason() != null && !reasons.containsKey(t.reason())) {
                reasons.put(t.reason(), table.size());
                table.add(t.reason());
            }
        }
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 20))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(r.pairs());
            out.writeInt(table.size());
            for (String s : table) {
                out.writeUTF(s);
            }
            out.writeInt(r.ticks().size());
            for (ReplayFair.Tick t : r.ticks()) {
                int flags = (t.bid() != null ? 1 : 0) | (t.ask() != null ? 2 : 0) | (t.quotable() ? 4 : 0)
                        | (t.reason() != null ? 8 : 0);
                out.writeLong(t.tsMs());
                out.writeDouble(t.fair());
                out.writeByte(flags);
                if (t.bid() != null) {
                    out.writeDouble(t.bid());
                }
                if (t.ask() != null) {
                    out.writeDouble(t.ask());
                }
                out.writeDouble(t.inventory());
                out.writeDouble(t.pressure());
                if (t.reason() != null) {
                    out.writeInt(reasons.get(t.reason()));
                }
            }
        }
    }

    static Result read(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 20))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                throw new IOException("не тот формат");
            }
            int pairs = in.readInt();
            String[] table = new String[in.readInt()];
            for (int i = 0; i < table.length; i++) {
                table[i] = in.readUTF();
            }
            int n = in.readInt();
            List<ReplayFair.Tick> ticks = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                long ts = in.readLong();
                double fair = in.readDouble();
                int flags = in.readByte();
                Double bid = (flags & 1) != 0 ? in.readDouble() : null;
                Double ask = (flags & 2) != 0 ? in.readDouble() : null;
                double inv = in.readDouble();
                double pressure = in.readDouble();
                String reason = (flags & 8) != 0 ? table[in.readInt()] : null;
                ticks.add(new ReplayFair.Tick(ts, fair, bid, ask, inv, (flags & 4) != 0, reason, pressure));
            }
            return new Result(ticks, pairs);
        }
    }
}
