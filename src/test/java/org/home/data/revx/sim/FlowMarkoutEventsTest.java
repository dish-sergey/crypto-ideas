package org.home.data.revx.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Схлопывание пачки принтов в одно СОБЫТИЕ.
 *
 * Это не украшение отчёта, а условие правильности всей кривой отбора. Одна
 * рыночная заявка приходит на ленту несколькими принтами с одной отметкой
 * времени, а наша заявка исполняется при этом ОДИН раз. Без схлопывания дальние
 * уровни свипа попадают в ведро по нескольку раз, и поскольку markout у них
 * самый большой, кривая {@code c(δ)} задирается вверх ровно там, где по ней
 * выбирают отступ.
 *
 * Цена ошибки измерена: в первом прогоне 11.09.2026 край {@code δ − c(δ)}
 * выходил отрицательным на ВСЕХ ступенях и всех трёх парах, что противоречило
 * живому замеру края (+3.9 у BTC). После схлопывания BTC на 8 б.п. дал
 * {@code c = 6.56} против живых 5.60 — два прибора сошлись.
 */
class FlowMarkoutEventsTest {

    private static FlowMarkout.Print print(long ts, int aggressor, double distBp) {
        return new FlowMarkout.Print(ts, 100, 1, aggressor, distBp, 100, 0, 1, 0.5);
    }

    @Test
    @DisplayName("пачка по одной отметке времени считается одним событием")
    void burstCollapsesToOne() {
        // Свип: четыре принта одной миллисекунды, всё дальше от справедливой цены.
        List<FlowMarkout.Print> prints = List.of(
                print(1000, 1, 8), print(1000, 1, 12), print(1000, 1, 16), print(1000, 1, 20));

        assertThat(FlowMarkout.events(prints, 6)).hasSize(1);
        assertThat(FlowMarkout.events(prints, 8)).hasSize(1);
    }

    @Test
    @DisplayName("событием считается ПЕРВЫЙ принт, дотянувшийся до порога")
    void firstReachingPrintWins() {
        List<FlowMarkout.Print> prints = List.of(
                print(1000, 1, 8), print(1000, 1, 20));

        // На пороге 6 наша заявка исполнилась бы первым принтом — им же и меряем.
        assertThat(FlowMarkout.events(prints, 6).get(0).distBp()).isEqualTo(8);
        // На пороге 16 первый принт не дотянулся, событие открывает второй.
        assertThat(FlowMarkout.events(prints, 16).get(0).distBp()).isEqualTo(20);
    }

    @Test
    @DisplayName("принты врозь во времени — разные события")
    void separatedPrintsAreSeparateEvents() {
        List<FlowMarkout.Print> prints = List.of(
                print(1000, 1, 10), print(1000 + 51, 1, 10), print(1000 + 500, 1, 10));

        assertThat(FlowMarkout.events(prints, 6)).hasSize(3);
    }

    @Test
    @DisplayName("разворот стороны внутри пачки — уже другое событие")
    void oppositeSideStartsNewEvent() {
        // Иначе продажа, пришедшая через миллисекунду после покупки, потерялась бы,
        // а это две разные заявки, и исполнили бы нас обе — с разных сторон.
        List<FlowMarkout.Print> prints = List.of(
                print(1000, 1, 10), print(1001, -1, 10));

        assertThat(FlowMarkout.events(prints, 6)).hasSize(2);
    }

    @Test
    @DisplayName("микроцена тянется к стороне, которую сметут раньше")
    void microPriceLeansToThinSide() {
        // На биде втрое больше, чем на аске: аск сметут раньше, значит цена
        // ближе к аску. Перепутанный вес дал бы ровно обратное, а замер
        // 11.09.2026 показал, что знак верен: при аск-тяжёлой книге отбор у
        // продавцов +50.40 б.п. против −1.13 у покупателей (ETH), то есть цена
        // после принта идёт вниз — как микроцена и предсказывает.
        FlowMarkout.Top bidHeavy = new FlowMarkout.Top(100, 102, 30, 10);
        assertThat(bidHeavy.micro()).isGreaterThan(bidHeavy.mid());

        FlowMarkout.Top askHeavy = new FlowMarkout.Top(100, 102, 10, 30);
        assertThat(askHeavy.micro()).isLessThan(askHeavy.mid());

        FlowMarkout.Top balanced = new FlowMarkout.Top(100, 102, 20, 20);
        assertThat(balanced.micro()).isEqualTo(balanced.mid());
    }

    @Test
    @DisplayName("поправка микроцены не больше полуспреда")
    void microStaysInsideSpread() {
        // Это и есть причина, по которой полная микроцена проиграла простой
        // середине: её поправка масштаба полуспреда (у BTC ±6.9 б.п.), а сама
        // середина за минуту проходит 1.3 б.п.
        FlowMarkout.Top extreme = new FlowMarkout.Top(100, 102, 1e9, 1);
        assertThat(extreme.micro()).isBetween(100.0, 102.0);
    }

    @Test
    @DisplayName("принты ближе порога не открывают событие вовсе")
    void tooCloseIsNotAnEvent() {
        List<FlowMarkout.Print> prints = List.of(print(1000, 1, 3), print(2000, 1, 4));

        assertThat(FlowMarkout.events(prints, 6)).isEmpty();
    }
}
