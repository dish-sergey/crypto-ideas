package org.home.data.revx.exec;

/**
 * Часы котировщика. Второй — после {@link Venue} — канал связи с внешним миром.
 *
 * Живой бот живёт в настоящем времени и честно спит между тиками. Стенд обязан
 * прогонять сутки за секунды и попадать в записанные отметки времени тик в тик,
 * иначе сверка с живым журналом бессмысленна: справедливая цена берётся ПО
 * ВРЕМЕНИ, и сдвиг на секунду даёт другую цену, другую котировку и другое
 * исполнение.
 *
 * ⚠️ Прямой вызов {@code System.currentTimeMillis()} внутри {@link QuoteLoop}
 * запрещён по этой же причине: одна забытая строка возвращает в цикл настоящее
 * время и молча ломает воспроизводимость — прогон перестаёт совпадать сам с
 * собой, а найти это почти нечем.
 */
public interface Clock {

    long now();

    /**
     * Подождать. В живом режиме — настоящий сон, на стенде — прыжок времени.
     *
     * @throws InterruptedException только живая реализация; стендовая не бросает
     */
    void sleep(long ms) throws InterruptedException;

    /** Настоящие часы. */
    static Clock system() {
        return new Clock() {
            @Override
            public long now() {
                return System.currentTimeMillis();
            }

            @Override
            public void sleep(long ms) throws InterruptedException {
                Thread.sleep(ms);
            }
        };
    }
}
