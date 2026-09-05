package org.home.data.revx.replay;

import java.util.ArrayList;
import java.util.List;

/**
 * Исполнения из записи живого бота. Контрольная модель, не прогнозная.
 *
 * ⚠️ При ДРУГИХ параметрах бота бессмысленна. Поменяй отступ с 10 на 14 б.п. —
 * бот встанет в другое место книги, а записанные исполнения останутся теми же:
 * заявка, которой рынок бы не коснулся, «исполнится», потому что так было у
 * другого бота. Годится ровно для одного — доказать, что всё, кроме
 * предсказания исполнений, работает точно.
 */
public final class RecordedFillModel implements FillModel {

    /** Записанное исполнение: то, что на самом деле произошло у живого бота. */
    public record RecordedFill(long tsMs, boolean buy, double qty, double price) {
    }

    /**
     * Насколько сдвинуть записанные исполнения НАЗАД.
     *
     * ⚠️ {@code exec_fill.ts_ms} — момент ОБНАРУЖЕНИЯ, а не сделки. Бот не
     * считает заявку исчезнувшей, пока ей меньше {@code ADOPT_GRACE_MS} = 5 с:
     * список активных отстаёт от постановки, и поспешный вывод даёт дубль.
     * Значит в записанную отметку уже вложены чужие пять секунд, и стенд,
     * убирающий заявку в этот момент, добавляет к ним свои — измерено 5.82,
     * 5.80, 4.37, 10.86, 5.69 с по первым исполнениям бота A. Сдвиг назад
     * возвращает отметку к рынку, и собственное правило бота приводит
     * обнаружение туда, где оно было у живого: стало −0.18…+1.8 с, а доля
     * совпавших котировок выросла с 50.63% до 99.92%.
     */
    public static final long DETECTION_LAG_MS = 5_000;

    private final List<RecordedFill> fills;
    private final long lagMs;
    private int cursor;
    private long unattributed;

    public RecordedFillModel(List<RecordedFill> fills, long lagMs) {
        this.fills = fills;
        this.lagMs = lagMs;
    }

    /** Записанные исполнения, которые не на что было положить. */
    public long unattributed() {
        return unattributed;
    }

    @Override
    public List<Filled> advance(long nowMs, List<Resting> resting) {
        List<Filled> out = new ArrayList<>();
        while (cursor < fills.size() && fills.get(cursor).tsMs() - lagMs <= nowMs) {
            RecordedFill f = fills.get(cursor++);
            Resting hit = null;
            for (Resting r : resting) {
                if (r.buy() == f.buy()) {
                    hit = r;
                    break;
                }
            }
            if (hit == null) {
                // Молча пропускать нельзя: это и есть измеряемая ошибка
                // воспроизведения — у бота в этот момент стояла заявка, а у нас нет.
                unattributed++;
                continue;
            }
            out.add(new Filled(hit.id(), f.qty(), f.price()));
        }
        return out;
    }

    @Override
    public String describe() {
        return "исполнения из записи (контроль, не прогноз)";
    }
}
