package org.home.data.revx.replay;

import org.home.data.revx.sim.BookView;
import org.home.data.revx.sim.MarketTrade;
import org.home.data.revx.sim.Side;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Правила дележа потока между НАШИМИ заявками.
 *
 * <h2>Зачем это закреплено тестом</h2>
 *
 * На сетке уровней (7 / 9 / 11 б.п.) заявки стоят в книге одновременно и делят
 * один и тот же поток сделок — за 17 часов на BTC/USDC его всего 314 принтов.
 * Измеренное преимущество сетки (примерно вдвое против одиночной котировки)
 * держится ровно на этих правилах, и тихо сломать их рефакторингом легко:
 * числа останутся правдоподобными, а вывод изменится.
 *
 * ⚠️ Обе ошибки, найденные 05.09.2026, были именно здесь:
 * <ul>
 *   <li>обход шёл ПО ЗАЯВКАМ, и каждая независимо съедала объём принта — два
 *       бота получали исполнение об один и тот же принт;</li>
 *   <li>очередь каждой заявки вычиталась из объёма ПОСЛЕДОВАТЕЛЬНО, хотя
 *       очереди перед нашими заявками в книге перекрываются: это одни и те же
 *       чужие лоты.</li>
 * </ul>
 */
class FillModelTest {

    private static final double LOT = 1.0;

    /** Книга: биды 100/99/98, аски 102/103/104, по 1000 лотов на уровень. */
    private static BookView book() {
        return new BookView(
                List.of(new BookView.Level(100, 1000), new BookView.Level(99, 1000),
                        new BookView.Level(98, 1000)),
                List.of(new BookView.Level(102, 1000), new BookView.Level(103, 1000),
                        new BookView.Level(104, 1000)));
    }

    private static MarketData market(List<MarketTrade> trades) {
        return MarketData.of(trades, new long[]{0}, List.of(book()));
    }

    /** Две наши покупки внутри спреда: 101 (лучше) и 100.5. */
    private static List<FillModel.Resting> twoBids() {
        return List.of(new FillModel.Resting("inner", true, 101, LOT, 0),
                new FillModel.Resting("outer", true, 100.5, LOT, 0));
    }

    @Test
    void tradeVolumeIsSpentOnceAndByPricePriority() {
        // Принт на 1.5 лота проходит через обе наши цены. Лучшая заявка должна
        // взять свой лот целиком, второй достаётся только остаток в 0.5.
        TouchFillModel m = new TouchFillModel(market(List.of(
                new MarketTrade(1000, 100.0, 1.5, Side.SELL))));
        m.advance(0, twoBids());                       // первый вызов задаёт отсчёт
        List<FillModel.Filled> got = m.advance(2000, twoBids());

        double total = got.stream().mapToDouble(FillModel.Filled::qty).sum();
        assertEquals(1.5, total, 1e-9, "объём принта потрачен не один раз: " + got);
        assertEquals("inner", got.get(0).orderId(), "первым обязан исполниться лучший бид");
        assertEquals(1.0, got.get(0).qty(), 1e-9);
        assertEquals(0.5, got.get(1).qty(), 1e-9, "второму достаётся только остаток");
    }

    @Test
    void smallTradeReachesOnlyTheBestPrice() {
        // 27% принтов на BTC/USDC мельче двух лотов — вот на них конкуренция
        // между уровнями и настоящая.
        TouchFillModel m = new TouchFillModel(market(List.of(
                new MarketTrade(1000, 100.0, 0.4, Side.SELL))));
        m.advance(0, twoBids());
        List<FillModel.Filled> got = m.advance(2000, twoBids());

        assertEquals(1, got.size(), "мелкий принт не может исполнить обе заявки");
        assertEquals("inner", got.get(0).orderId());
        assertEquals(0.4, got.get(0).qty(), 1e-9);
    }

    @Test
    void tradeWithUnknownAggressorFillsNothing() {
        // ТЗ §4.3: неизвестное трактуется ПРОТИВ нас.
        TouchFillModel m = new TouchFillModel(market(List.of(
                MarketTrade.unknown(1000, 100.0, 100))));
        m.advance(0, twoBids());
        assertTrue(m.advance(2000, twoBids()).isEmpty(),
                "сделка без агрессора исполнения вызывать не должна");
    }

    @Test
    void buyIsNotFilledByABuyerLiftingTheAsk() {
        // Покупку исполняет только продавец, ударивший в бид.
        TouchFillModel m = new TouchFillModel(market(List.of(
                new MarketTrade(1000, 100.0, 100, Side.BUY))));
        m.advance(0, twoBids());
        assertTrue(m.advance(2000, twoBids()).isEmpty(),
                "покупатель не может исполнить нашу покупку");
    }

    @Test
    void queuesOfTwoOrdersAreNotCountedTwice() {
        // Обе заявки стоят ВНУТРИ спреда (лучше лучшего бида 100), значит очередь
        // перед каждой нулевая, и обе обязаны исполниться об один крупный принт.
        // Прежний код вычитал очередь каждой из общего объёма последовательно.
        MarketFillModel m = new MarketFillModel(market(List.of(
                new MarketTrade(1000, 100.0, 50, Side.SELL))));
        for (FillModel.Resting r : twoBids()) {
            m.placed(r);
        }
        m.advance(0, twoBids());
        List<FillModel.Filled> got = m.advance(2000, twoBids());

        assertEquals(2, got.size(), "обе заявки внутри спреда обязаны исполниться: " + got);
        assertEquals(2.0, got.stream().mapToDouble(FillModel.Filled::qty).sum(), 1e-9);
    }

    @Test
    void orderBehindTheQueueWaitsForTheVolumeToClearIt() {
        // Заявка НА уровне книги (99), где уже стоит 1000 чужих лотов. Принт на
        // 10 лотов до нас не доходит; принт на 1100 — доходит.
        FillModel.Resting deep = new FillModel.Resting("deep", true, 99, LOT, 0);

        MarketFillModel small = new MarketFillModel(market(List.of(
                new MarketTrade(1000, 99.0, 10, Side.SELL))));
        small.placed(deep);
        small.advance(0, List.of(deep));
        assertTrue(small.advance(2000, List.of(deep)).isEmpty(),
                "очередь в 1000 лотов не выбирается принтом на 10");

        MarketFillModel big = new MarketFillModel(market(List.of(
                new MarketTrade(1000, 99.0, 2100, Side.SELL))));
        big.placed(deep);
        big.advance(0, List.of(deep));
        assertEquals(1, big.advance(2000, List.of(deep)).size(),
                "принт, выбравший очередь, обязан дойти до нас");
    }

    @Test
    void orderOutsideTheVisibleBookNeverFills() {
        // Ниже пятого видимого уровня: ни объёма перед нами, ни факта торговли
        // мы не знаем (ТЗ §4.6 п.7).
        FillModel.Resting blind = new FillModel.Resting("blind", true, 50, LOT, 0);
        MarketFillModel m = new MarketFillModel(market(List.of(
                new MarketTrade(1000, 49.0, 10_000, Side.SELL))));
        m.placed(blind);
        m.advance(0, List.of(blind));

        assertTrue(m.advance(2000, List.of(blind)).isEmpty(),
                "заявка вне видимой книги исполняться не может");
        assertTrue(m.invisibleSkips() > 0, "и это должно быть посчитано, а не молча пропущено");
    }

    @Test
    void recordedModelBackdatesByTheDetectionLag() {
        // exec_fill.ts_ms — момент ОБНАРУЖЕНИЯ, в него уже вложены пять секунд
        // ADOPT_GRACE_MS. Модель обязана сдвинуть исполнение назад, иначе стенд
        // добавляет своё запаздывание поверх чужого (было 50.63% совпадения
        // котировок вместо 99.92%).
        RecordedFillModel m = new RecordedFillModel(
                List.of(new RecordedFillModel.RecordedFill(10_000, true, LOT, 100)),
                RecordedFillModel.DETECTION_LAG_MS);

        assertTrue(m.advance(4_000, twoBids()).isEmpty(), "до сдвинутого момента — рано");
        assertEquals(1, m.advance(5_100, twoBids()).size(),
                "на 10 000 минус 5 000 исполнение обязано состояться");
    }
}
