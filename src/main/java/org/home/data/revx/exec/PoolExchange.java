package org.home.data.revx.exec;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Обмен общего буфера между прогонами стенда (19.10.2026).
 *
 * Шесть участников пула — шесть отдельных процессов стенда, у каждого свои часы.
 * Сходятся они раз в шаг пула через общий каталог: участник, дойдя модельным
 * временем до границы шага {@code h}, кладёт файл {@code h<h>/<имя>} со своим
 * реализованным за шаг и стартовым капиталом и ждёт, пока файлы положат все.
 * Дальше каждый сам считает пул по одним и тем же числам — копии совпадают.
 *
 * Закончивший прогон пишет {@code done-<имя>} (из перехватчика выхода процесса), и
 * остальные после этого считают его вклад нулём, а не ждут вечно. ⚠️ Упавший без
 * выхода процесс (kill -9) файла не оставит — тогда срабатывает предел ожидания
 * {@code revx.exec.pool-wait-min} (по умолчанию 360 мин настоящего времени), и прогон
 * падает с сообщением, а не висит.
 */
public final class PoolExchange {

    public record Entry(double delta, double capital0) {
    }

    private static final java.util.Set<String> USED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final Path dir;
    private final String me;
    private final List<String> members;
    private final long waitMs;

    /**
     * @param firstHour первый шаг, о котором участник отчитается (шаги раньше него
     *                  остальные засчитывают ему нулём)
     * @param capital0  стартовый капитал участника
     */
    public PoolExchange(String dir, String me, List<String> members, long firstHour, double capital0) {
        this.dir = Path.of(dir);
        this.me = me;
        this.members = List.copyOf(members);
        this.waitMs = (long) (Double.parseDouble(System.getProperty("revx.exec.pool-wait-min", "360")) * 60_000);
        // ⚠️ Второй прогон в том же процессе (вторая модель исполнения — «касание»)
        // прочитал бы файлы ПЕРВОГО прогона соседей и молча смешал две траектории.
        if (!USED.add(dir + "|" + me)) {
            throw new IllegalStateException("пул " + dir + ": участник " + me + " уже отработал в этом процессе — "
                    + "общий буфер допускает ОДИН прогон на процесс (-Drevx.forecast.models=market)");
        }
        if (!this.members.contains(me)) {
            throw new IllegalArgumentException("участник " + me + " не в списке пула " + members);
        }
        try {
            Files.createDirectories(this.dir);
        } catch (Exception e) {
            throw new IllegalStateException("не создать каталог пула " + dir, e);
        }
        if (Files.exists(this.dir.resolve("start-" + me))) {
            throw new IllegalStateException("пул: в " + dir + " уже лежит старт " + me
                    + " от прежнего прогона — каталог пула на каждый набор свой и пустой");
        }
        try {
            write(this.dir.resolve("start-" + me),
                    String.format(java.util.Locale.ROOT, "%d %.17g", firstHour, capital0));
        } catch (Exception e) {
            throw new IllegalStateException("пул: не записать старт " + me, e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                write(this.dir.resolve("done-" + me), "done");
            } catch (Exception ignored) {
            }
        }));
    }

    public List<String> members() {
        return members;
    }

    /** Положить своё за шаг {@code hour} и дождаться остальных. */
    public Map<String, Entry> exchange(long hour, double delta, double capital0) {
        Path h = dir.resolve("h" + hour);
        try {
            Files.createDirectories(h);
            write(h.resolve(me), String.format(java.util.Locale.ROOT, "%.17g %.17g", delta, capital0));
            long t0 = System.currentTimeMillis();
            while (true) {
                Map<String, Entry> out = new TreeMap<>();
                boolean all = true;
                for (String m : members) {
                    Path f = h.resolve(m);
                    Path st = dir.resolve("start-" + m);
                    if (!Files.exists(st)) {
                        all = false;                 // участник ещё не запустился
                        break;
                    }
                    String[] sp = Files.readString(st, StandardCharsets.UTF_8).trim().split(" ");
                    if (Files.exists(f)) {
                        String[] p = Files.readString(f, StandardCharsets.UTF_8).trim().split(" ");
                        out.put(m, new Entry(Double.parseDouble(p[0]), Double.parseDouble(p[1])));
                    } else if (Long.parseLong(sp[0]) > hour || Files.exists(dir.resolve("done-" + m))) {
                        // ещё не начал отчитываться или уже закончил — вклад ноль
                        out.put(m, new Entry(0, Double.parseDouble(sp[1])));
                    } else {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    return out;
                }
                if (System.currentTimeMillis() - t0 > waitMs) {
                    throw new IllegalStateException("пул: за " + waitMs / 60_000 + " мин не дождался участников шага "
                            + hour + " в " + h);
                }
                Thread.sleep(200);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("пул: ожидание прервано", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("пул: обмен через " + h, e);
        }
    }

    private static void write(Path f, String s) throws java.io.IOException {
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, s, StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
