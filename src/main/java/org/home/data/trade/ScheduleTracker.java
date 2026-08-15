package org.home.data.trade;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Отслеживание пересмотра расписаний по ОТКРЫТЫМ позициям (doc 55 §2.3, §2.5). Расписания
 * пересматриваются (именно поэтому премия — верхняя граница). При переносе/отмене разлока после входа
 * позиция должна закрываться немедленно. Требует ежедневного перечитывания расписаний, не разовой загрузки.
 *
 * <p>Побочно измеряет частоту пересмотров (§2.4) — величину дисконта премии, которая сейчас неизвестна.
 */
public class ScheduleTracker {

    private final Map<String, Long> entryUnlockDay = new LinkedHashMap<>();
    private final Map<String, String> entryCategory = new LinkedHashMap<>();
    private final List<Revision> revisions = new ArrayList<>();

    /** category — категория получателя (investors/team/ecosystem/staking) для разбивки пересмотров (doc 59 §6). */
    public record Revision(String symbol, String category, long expectedDay, Long newDay, long daysToDateWhenSeen) {}

    /** Зафиксировать дату разлока и категорию на момент входа. */
    public void onEntry(String symbol, long unlockDay, String category) {
        entryUnlockDay.put(symbol, unlockDay);
        entryCategory.put(symbol, category);
    }

    /** Позиция закрыта — снять с отслеживания. */
    public void onExit(String symbol) { entryUnlockDay.remove(symbol); entryCategory.remove(symbol); }

    /**
     * Сверить текущую дату разлока с зафиксированной при входе. currentUnlockDay=null → разлок исчез
     * (отменён). Возвращает true, если расписание изменилось (перенос/отмена) → закрывать немедленно.
     */
    public boolean scheduleChanged(String symbol, Long currentUnlockDay, long todayEpochDay) {
        Long expected = entryUnlockDay.get(symbol);
        if (expected == null) return false;                  // не отслеживаем
        boolean changed = currentUnlockDay == null || !currentUnlockDay.equals(expected);
        if (changed) revisions.add(new Revision(symbol, entryCategory.getOrDefault(symbol, "unknown"),
                expected, currentUnlockDay, expected - todayEpochDay));
        return changed;
    }

    /** Зафиксированная при входе дата разлока, или null если не отслеживается. */
    public Long expectedDay(String symbol) { return entryUnlockDay.get(symbol); }

    public List<Revision> revisions() { return List.copyOf(revisions); }
    public int tracked() { return entryUnlockDay.size(); }

    /** Число пересмотров по категории получателя (doc 59 §6: у инсайдеров ожидается больше). */
    public Map<String, Integer> revisionsByCategory() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Revision r : revisions) m.merge(r.category(), 1, Integer::sum);
        return m;
    }
}
