package org.home.data.trade;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Эскалация напоминаний о подтверждении входа: пока кандидат ждёт подтверждения, шлём напоминания
 * за 24 / 12 / 3 / 1 ч до момента входа (каждое — один раз). Подтвердил/отклонил/открыл → снимаем из
 * учёта ({@link #clear}), напоминания прекращаются. Даёт оператору время «прочекать» и не спамит после
 * подтверждения (запрос постановщика).
 *
 * <p>Если между опросами перепрыгнули несколько порогов (демон стоял), шлём один — самый срочный из
 * наступивших — и остальные (менее срочные) считаем отправленными.
 */
public class ApprovalReminders {

    private static final int[] DEFAULT_HOURS = {24, 12, 3, 1};

    private final int[] offsetsHours;                 // по возрастанию
    private final Map<String, Set<Integer>> sent = new HashMap<>();

    public ApprovalReminders() { this(DEFAULT_HOURS); }

    public ApprovalReminders(int[] offsetsHours) {
        int[] c = offsetsHours.clone();
        Arrays.sort(c);
        this.offsetsHours = c;
    }

    /**
     * Какое напоминание (часов до входа) пора отправить сейчас, или null. Помечает отправленным его и
     * все менее срочные пороги.
     */
    public Integer due(String id, long entryInstantSec, long nowSec) {
        double hoursLeft = (entryInstantSec - nowSec) / 3600.0;
        Set<Integer> s = sent.computeIfAbsent(id, k -> new HashSet<>());
        Integer fire = null;
        for (int o : offsetsHours) if (hoursLeft <= o && !s.contains(o)) { fire = o; break; } // самый срочный неотправленный
        if (fire == null) return null;
        for (int o : offsetsHours) if (o >= fire) s.add(o);
        return fire;
    }

    public void clear(String id) { sent.remove(id); }
    public boolean isTracked(String id) { return sent.containsKey(id); }
}
