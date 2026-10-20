package org.home.data.revx.exec;

import java.util.Map;
import java.util.TreeMap;

/**
 * ОБЩИЙ БУФЕР РЕИНВЕСТИРОВАНИЯ семейства ботов (владелец, 19.10.2026).
 *
 * <h2>Правило</h2>
 *
 * При старте семейство резервирует буфер — {@code pct}% суммы капиталов участников.
 * Дальше весь реализованный доход и убыток КАЖДОГО участника пишется в общий буфер.
 * Раз в шаг (час) буфер сверяется с целью {@code pct}% текущей суммы капиталов:
 * <ul>
 *   <li>излишек сверх цели делится ПОРОВНУ между участниками и идёт им в капитал
 *       (лот = капитал / лотов в потолке);</li>
 *   <li>буфер ниже нуля — недостача тоже делится поровну и уменьшает капиталы
 *       ({@code shrink}); иначе буфер просто остаётся отрицательным и ждёт дохода.</li>
 * </ul>
 * Капитал участника держится в [{@code floor}·C0, {@code ceil}·C0]; то, что упёрлось
 * в границу, остаётся в буфере (деньги не исчезают и не появляются).
 *
 * <h2>Детерминизм</h2>
 *
 * Класс — чистый учёт без часов и ввода-вывода. На стенде каждый участник держит
 * СВОЮ копию и кормит её одними и теми же данными в одном порядке (участники
 * отсортированы по имени), поэтому все копии совпадают до бита. Живьём копия одна —
 * в общем реестре.
 */
public final class ReinvestPool {

    /** Состояние участника. */
    public static final class Member {
        public final double capital0;
        public double capital;
        /** Сколько участник всего принёс в буфер (реализованное) — для отчёта. */
        public double contributed;

        Member(double capital0) {
            this.capital0 = capital0;
            this.capital = capital0;
        }
    }

    private final double pct;
    private final double floor;
    private final double ceil;
    private final boolean shrink;
    private final TreeMap<String, Member> members = new TreeMap<>();
    private double buffer;
    private long steps;
    private double reinvested;
    private double cut;

    public ReinvestPool(Map<String, Double> capital0, double pct, double floor, double ceil,
                        boolean shrink) {
        if (capital0.isEmpty()) {
            throw new IllegalArgumentException("пул без участников");
        }
        this.pct = pct;
        this.floor = floor;
        this.ceil = ceil;
        this.shrink = shrink;
        new TreeMap<>(capital0).forEach((k, v) -> members.put(k, new Member(v)));
        buffer = target();
    }

    private ReinvestPool(double pct, double floor, double ceil, boolean shrink, double buffer) {
        this.pct = pct;
        this.floor = floor;
        this.ceil = ceil;
        this.shrink = shrink;
        this.buffer = buffer;
    }

    /**
     * Живой пул: состояние лежит в реестре и поднимается на каждый шаг заново.
     * @param state участник → {стартовый капитал, текущий капитал, принесено}
     */
    public static ReinvestPool restore(double pct, double floor, double ceil, boolean shrink,
                                       double buffer, Map<String, double[]> state) {
        if (state.isEmpty()) {
            throw new IllegalArgumentException("пул без участников");
        }
        ReinvestPool p = new ReinvestPool(pct, floor, ceil, shrink, buffer);
        new TreeMap<>(state).forEach((k, v) -> {
            Member m = new Member(v[0]);
            m.capital = v[1];
            m.contributed = v[2];
            p.members.put(k, m);
        });
        return p;
    }

    /** Цель буфера: pct% текущей суммы капиталов. */
    public double target() {
        double sum = 0;
        for (Member m : members.values()) {
            sum += m.capital;
        }
        return sum * pct / 100;
    }

    /**
     * Один шаг: принять реализованное за шаг по каждому участнику и перераспределить.
     * Участник, которого нет в {@code deltas}, принёс ноль.
     */
    public void step(Map<String, Double> deltas) {
        steps++;
        for (var e : new TreeMap<>(deltas).entrySet()) {
            Member m = members.get(e.getKey());
            if (m == null) {
                throw new IllegalArgumentException("не участник пула: " + e.getKey());
            }
            double d = e.getValue();
            if (Double.isFinite(d)) {
                buffer += d;
                m.contributed += d;
            }
        }
        // Излишек считается так, чтобы ПОСЛЕ раздачи буфер встал ровно в новую цель:
        // раздача сама поднимает сумму капиталов, а с ней и цель.
        double excess = (buffer - target()) / (1 + pct / 100);
        if (excess > 1e-12) {
            buffer -= share(excess);
        } else if (buffer < -1e-12 && shrink) {
            buffer -= share(buffer);
        }
    }

    /**
     * Раздать {@code amount} поровну (знак — направление). Кто упёрся в границу, берёт
     * меньше, остаток делится между остальными, пока есть кому брать.
     * @return сколько на самом деле ушло из буфера (со знаком amount)
     */
    private double share(double amount) {
        double left = amount;
        java.util.List<Member> open = new java.util.ArrayList<>(members.values());
        while (Math.abs(left) > 1e-12 && !open.isEmpty()) {
            double each = left / open.size();
            java.util.List<Member> next = new java.util.ArrayList<>();
            for (Member m : open) {
                double lim = each > 0 ? ceil * m.capital0 - m.capital : floor * m.capital0 - m.capital;
                double take = each > 0 ? Math.min(each, Math.max(0, lim)) : Math.max(each, Math.min(0, lim));
                m.capital += take;
                left -= take;
                if (Math.abs(take - each) <= 1e-12) {
                    next.add(m);
                }
            }
            if (next.size() == open.size()) {
                break;
            }
            open = next;
        }
        double moved = amount - left;
        if (moved > 0) {
            reinvested += moved;
        } else {
            cut -= moved;
        }
        return moved;
    }

    public double capital(String id) {
        Member m = members.get(id);
        return m == null ? Double.NaN : m.capital;
    }

    public double buffer() {
        return buffer;
    }

    public long steps() {
        return steps;
    }

    /** Всего ушло из буфера в капиталы. */
    public double reinvested() {
        return reinvested;
    }

    /** Всего срезано с капиталов на покрытие недостачи. */
    public double cut() {
        return cut;
    }

    public Map<String, Member> members() {
        return java.util.Collections.unmodifiableMap(members);
    }

    /** Одна строка для журнала. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(java.util.Locale.ROOT, "буфер %.4f (цель %.4f), в капитал %.4f, срезано %.4f;",
                buffer, target(), reinvested, cut));
        members.forEach((k, m) -> sb.append(String.format(java.util.Locale.ROOT,
                " %s %.2f→%.2f (%+.4f)", k, m.capital0, m.capital, m.contributed)));
        return sb.toString();
    }
}
