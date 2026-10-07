package org.home.data.revx.replay;

import org.home.data.revx.exec.AllocRegistry;
import org.home.data.revx.exec.BotTag;
import org.home.data.revx.exec.ExecJournal;
import org.home.data.revx.exec.Executor;
import org.home.data.revx.exec.FifoLedger;
import org.home.data.revx.exec.QuoteLoop;
import org.home.data.revx.exec.PlacementBudget;
import org.home.data.revx.sim.Quoter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Прогон НЕСКОЛЬКИХ котировщиков по одной книге.
 *
 * <h2>Зачем не один</h2>
 *
 * На счёте их и так несколько: A и C котируют одну BTC/USDC с отступами 10 и
 * 14 б.п. Поток на площадке конечен — за 17 часов на паре прошло ВСЕГО
 * 314 сделок, — и заявки ботов стоят в книге одновременно, деля его между
 * собой. Стенд с одним котировщиком отдаёт ему весь поток и завышает
 * исполнения каждому. Отсюда правило: сравнивать варианты настройки можно
 * только прогоном ВСЕХ ботов разом.
 *
 * <h2>Чем это отличается от сверки</h2>
 *
 * Сверка ({@link ReplayRunner}) доказывает, что стенд воспроизводит живого:
 * тот же код, те же входы, 99.92% совпавших котировок. Прогноз отвечает на
 * другой вопрос — «что будет, если поменять настройки», — и проверить его
 * записью нельзя по определению: такого бота не было.
 *
 * ⚠️ Поэтому результат отдаётся ВИЛКОЙ по двум моделям исполнения: рабочей и
 * заведомо завышенной. Одно число здесь обманывает.
 */
public final class Forecast {

    private static final Logger log = LoggerFactory.getLogger(Forecast.class);

    /**
     * Предохранитель от зависания на барьере, а не ожидаемое время прогона.
     *
     * ⚠️ Если он сработал — прогон НЕ ДОСЧИТАН, и результат печатать нельзя
     * (см. проверку живых потоков ниже).
     */
    static final long JOIN_TIMEOUT_MS = 30 * 60_000L;

    /** Один котировщик в прогоне: чем отличается от базового. */
    /**
     * @param inventoryCap потолок инвентаря ЭТОГО уровня. Отдельным полем не для
     *                     красоты: сетка из N уровней с полным потолком у каждого
     *                     занимает в N раз больше капитала, чем одиночная
     *                     котировка, и сравнивать их «в лоб» нельзя. Нормировка
     *                     на капитал — деление общего потолка между уровнями.
     */
    /**
     * @param dynOffsetK доля отступа, отдаваемая неопределённости цены при
     *                   ДИНАМИЧЕСКОМ отступе (0 — бинарный гейт, как на живых
     *                   ботах). Поле здесь, а не в конфиге, потому что это ось
     *                   сравнения: в одном прогоне нужны оба режима рядом.
     */
    /**
     * @param sweepCoef    доля ожидаемого остатка пути после свипа, на которую
     *                     сдвигается опора (0 — реакции нет). Поле здесь по той
     *                     же причине, что и {@code dynOffsetK}: это ось
     *                     сравнения, а не настройка среды.
     * @param sweepDelayMs задержка узнавания ленты, мс. ⚠️ Ноль моделирует бота
     *                     с МГНОВЕННОЙ лентой, которого не бывает: после
     *                     ускорения опроса (A83) живая задержка 2.0 с.
     * @param sweepSide    какую сторону двигать. Сдвиг ОПОРЫ двигает обе разом,
     *                     а действуют они по-разному: на свипе вниз бид уходит
     *                     от рынка (не лови нож), а аск приближается (успей
     *                     продать). Ось нужна, чтобы мерить их порознь.
     */
    public record BotSpec(String botId, double offset, double skewTarget,
                          double inventoryCap, int levels, double levelStep,
                          double size, boolean innerFirst, double dynOffsetK,
                          double sweepCoef, long sweepDelayMs,
                          org.home.data.revx.exec.QuoteLoop.SweepSide sweepSide,
                          double flowCoef, long flowDelayMs) {

        public BotSpec(String botId, double offset, double skewTarget,
                       double inventoryCap, int levels, double levelStep,
                       double size, boolean innerFirst) {
            this(botId, offset, skewTarget, inventoryCap, levels, levelStep,
                    size, innerFirst, 0);
        }

        public BotSpec(String botId, double offset, double skewTarget,
                       double inventoryCap, int levels, double levelStep,
                       double size, boolean innerFirst, double dynOffsetK,
                       double sweepCoef, long sweepDelayMs,
                       org.home.data.revx.exec.QuoteLoop.SweepSide sweepSide) {
            this(botId, offset, skewTarget, inventoryCap, levels, levelStep,
                    size, innerFirst, dynOffsetK, sweepCoef, sweepDelayMs,
                    sweepSide, 0, 30_000);
        }

        public BotSpec(String botId, double offset, double skewTarget,
                       double inventoryCap, int levels, double levelStep,
                       double size, boolean innerFirst, double dynOffsetK) {
            this(botId, offset, skewTarget, inventoryCap, levels, levelStep,
                    size, innerFirst, dynOffsetK, 0, 2_000);
        }

        public BotSpec(String botId, double offset, double skewTarget,
                       double inventoryCap, int levels, double levelStep,
                       double size, boolean innerFirst, double dynOffsetK,
                       double sweepCoef, long sweepDelayMs) {
            this(botId, offset, skewTarget, inventoryCap, levels, levelStep,
                    size, innerFirst, dynOffsetK, sweepCoef, sweepDelayMs,
                    org.home.data.revx.exec.QuoteLoop.SweepSide.BOTH);
        }
    }

    /** Что получилось у одного котировщика. */
    public record BotResult(String botId, double offsetBp, int fills, double realised,
                            double inventoryLots, long placements, long replaces,
                            long placementCap, double days, String state, long lossStops,
                            double atCapShare, double lotNotional, int buys, int sells,
                            java.util.List<Day> days_, double emptyShare, long[] lotHist,
                            double holdMedMin, double holdP90Min, double volBpPerMin,
                            double roundSumBp, double roundSumSqBp, double roundCount,
                            double marketSumBp, double unrealisedBpPerDay) {

        /**
         * 🔑 СУТОЧНОЕ ОТНОШЕНИЕ — мерка сравнения настроек (док. 154 §I).
         *
         * {@code средний круг / СКО круга × √(кругов в сутки)}, где средний круг
         * посчитан ЗА ВЫЧЕТОМ РЫНКА: из каждого круга вычтен средний ход опоры
         * за то же время, взятый от каждой минуты окна (безусловный контроль).
         * Именно этот вычет убирает траекторию — единственное слагаемое
         * тождества «круг = вход + снос + выход», которое зависит от того, какое
         * окно попалось.
         *
         * ⚠️ Отличие от {@link #payPerRisk()}: там в числителе стоит {@code 2δ},
         * положительный по построению и не видящий сноса против позиции. Здесь
         * числитель — ФАКТИЧЕСКИЙ средний круг, поэтому знак у отношения тот же,
         * что у результата.
         */
        public double ratioPerDay() {
            double sd = roundSdBp();
            return sd > 0 && roundCount > 1 && days > 0
                    ? roundSumBp / roundCount / sd * Math.sqrt(roundCount / days) : 0;
        }

        public double roundMeanBp() {
            return roundCount > 0 ? roundSumBp / roundCount : 0;
        }

        public double roundSdBp() {
            if (!(roundCount > 1)) {
                return 0;
            }
            double mean = roundSumBp / roundCount;
            double var = (roundSumSqBp - roundCount * mean * mean) / (roundCount - 1);
            return var > 0 ? Math.sqrt(var) : 0;
        }

        /**
         * РИСК ЗА ВРЕМЯ УДЕРЖАНИЯ, б.п.: σ√T.
         *
         * Пока лот висит T минут, цена уходит на σ√T — это и есть цена опциона,
         * который мы выписываем, ставя заявку. Захват равен 2δ и от времени не
         * зависит.
         */
        public double riskBp() {
            return volBpPerMin * Math.sqrt(Math.max(0, holdMedMin));
        }

        /**
         * Платят ли нам за риск: захват, делённый на σ√T.
         *
         * ⚠️ ЭТО И ЕСТЬ ПРАВИЛЬНАЯ МЕРКА ДЛЯ СРАВНЕНИЯ НАСТРОЕК. Доход за окно
         * зависит от того, какая ценовая траектория попалась, и на августовском
         * окне он советовал широкие ступени, которые на другой траектории дают
         * хвост (у бота A шесть худших кругов из 62 отняли втрое больше, чем
         * заработали остальные 56). Отношение захвата к риску от траектории не
         * зависит.
         *
         * Меньше единицы — мы продаём опцион дешевле его стоимости. Живьём
         * 11.09.2026 так было у пяти ботов из шести.
         */
        public double payPerRisk() {
            double r = riskBp();
            return r > 0 ? 2 * offsetBp / r : 0;
        }
    }

    /**
     * Результат за одни сутки.
     *
     * Средний доход за окно скрывает главное: конструкция может выигрывать на
     * росте и проваливаться на падении. «Универсальность» иначе не проверить.
     */
    public record Day(String label, double movePct, double realised, int fills) {
    }

    /**
     * Волатильность справедливой цены, б.п. в минуту — СКО минутных приращений.
     *
     * ⚠️ Именно СКО, а не медиана модуля. 11.09.2026 выяснилось, что колонка
     * «ход середины» в docs/pairs считалась медианой, и у ликвидных пар она ниже
     * СКО в 6–15 раз: книга стоит бо́льшую часть минут и изредка прыгает. Риск
     * живёт в прыжках, поэтому в σ√T должно входить СКО.
     */
    static double volBpPerMin(List<ReplayFair.Tick> ticks) {
        TreeMap<Long, Double> byMin = new TreeMap<>();
        for (ReplayFair.Tick t : ticks) {
            if (Double.isFinite(t.fair()) && t.fair() > 0) {
                byMin.putIfAbsent(t.tsMs() / 60_000, t.fair());
            }
        }
        if (byMin.size() < 10) {
            return 0;
        }
        List<Double> d = new ArrayList<>();
        Long prevKey = null;
        double prevVal = 0;
        for (Map.Entry<Long, Double> e : byMin.entrySet()) {
            if (prevKey != null && e.getKey() - prevKey == 1 && prevVal > 0) {
                d.add(1e4 * (e.getValue() - prevVal) / prevVal);
            }
            prevKey = e.getKey();
            prevVal = e.getValue();
        }
        if (d.size() < 10) {
            return 0;
        }
        double m = d.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double v = d.stream().mapToDouble(x -> (x - m) * (x - m)).sum() / d.size();
        return Math.sqrt(v);
    }

    private Forecast() {
    }

    public static List<BotResult> run(List<ReplayFair.Tick> ticks, FillModel model,
                                      BootParams base, List<BotSpec> bots,
                                      org.home.data.revx.RevxConfig cfg) throws Exception {
        return run(ticks, model, base, bots, cfg, null);
    }

    /**
     * То же, но с лентой для РЕАКЦИИ НА СВИП: {@code standDbPath} — база стенда.
     *
     * ⚠️ Сторож заводится только тем ботам, у кого {@link BotSpec#sweepCoef()}
     * положителен. Остальные работают ровно как раньше — это и делает прогон
     * парным.
     */
    public static List<BotResult> run(List<ReplayFair.Tick> ticks, FillModel model,
                                      BootParams base, List<BotSpec> bots,
                                      org.home.data.revx.RevxConfig cfg,
                                      String standDbPath) throws Exception {
        return run(ticks, model, base, bots, cfg, standDbPath, null);
    }

    /**
     * То же с ВЫГРУЗКОЙ ЖУРНАЛОВ: {@code journalOut} — каталог, куда лечь
     * {@code bot-<id>.db} каждого котировщика вместе с котировками.
     *
     * <h2>Зачем</h2>
     *
     * Чтобы считать по стенду то же, что по живым, ТЕМ ЖЕ прибором. Живой
     * часовой разрез ({@code --revx-carry --hours-out}) смещён: бот выключается
     * по суточному пределу постановок, а выбирает его быстрее в бурные часы, и
     * стоимость запаса во время простоя в замер не попадает. У стендового бота
     * потолок постановок снят, значит простоя по этой причине нет вовсе — и
     * сравнение классов волатильности выходит чистым.
     *
     * ⚠️ Котировки обычно НЕ пишутся ({@code journal.quotesOff()}): в обходе их
     * десятки миллионов, а считается всё в памяти. Здесь они нужны, поэтому
     * выгрузка включается только по явной просьбе.
     */
    public static List<BotResult> run(List<ReplayFair.Tick> ticks, FillModel model,
                                      BootParams base, List<BotSpec> bots,
                                      org.home.data.revx.RevxConfig cfg,
                                      String standDbPath, String journalOut) throws Exception {
        List<org.home.data.revx.exec.SweepWatch> watches = new ArrayList<>();
        List<org.home.data.revx.exec.FlowWatch> flows = new ArrayList<>();
        try {
            return runInner(ticks, model, base, bots, cfg, standDbPath, watches,
                    journalOut, flows);
        } finally {
            for (var w : watches) {
                w.close();
            }
            for (var w : flows) {
                w.close();
            }
        }
    }

    private static List<BotResult> runInner(List<ReplayFair.Tick> ticks, FillModel model,
                                            BootParams base, List<BotSpec> bots,
                                            org.home.data.revx.RevxConfig cfg,
                                            String standDbPath,
                                            List<org.home.data.revx.exec.SweepWatch> watches,
                                            String journalOut,
                                            List<org.home.data.revx.exec.FlowWatch> flows)
            throws Exception {
        if (HybridFair.enabled() && model instanceof MarketFillModel mfm) {
            // ⚠️ fresh(): у книги свой курсор, и пробег до конца дня по общему объекту
            // оставил бы модель исполнения с книгой конца суток — все заявки «невидимы».
            ticks = HybridFair.apply(ticks, base.symbol(), mfm.market().fresh(), base.size());   // уровень Revolut, движение Бинанс
        }
        long start = ticks.get(0).tsMs();
        long end = ticks.get(ticks.size() - 1).tsMs();

        SimClock clock = new SimClock(start);
        clock.followSchedule(ticks.stream().mapToLong(ReplayFair.Tick::tsMs).toArray());
        ReplayFair fair = new ReplayFair(ticks, clock);

        // Счёт ОБЩИЙ: боты делят и книгу, и деньги. Денег даём столько, чтобы
        // каждому хватило на полный потолок, иначе меряли бы не настройку, а
        // нехватку средств.
        // ⚠️ Первый тик может НЕ ИМЕТЬ справедливой цены — гейты на нём ещё не
        // пропустили котирование, и в тике лежит NaN. Умножение на него делало
        // NaN из стартовой кассы, а дальше падало в форматировании остатков
        // (09.09.2026, PEPE: прогон умирал на первом же обращении к остаткам,
        // а отчёт показывал ноль сделок как честный результат).
        double refPrice = Double.NaN;
        for (ReplayFair.Tick t : ticks) {
            if (Double.isFinite(t.fair()) && t.fair() > 0) {
                refPrice = t.fair();
                break;
            }
        }
        if (!(refPrice > 0)) {
            throw new IllegalStateException("нет ни одного тика со справедливой ценой для "
                    + base.symbol());
        }
        double quoteStart = bots.stream().mapToDouble(BotSpec::inventoryCap).sum()
                * refPrice * 1.2;
        double startBase = startInventory(ticks, bots);
        // Ничейный буфер на счёте (лотов первого бота): монета, которую никто не
        // затравил и не купил, — ею бот покрывает своё запертое призраком (§3.6).
        double extraBase = Double.parseDouble(System.getProperty("revx.sim.extra-base-lots", "0"))
                * bots.getFirst().size();
        SimVenue venue = new SimVenue(clock, model, base.symbol(),
                startBase + extraBase, quoteStart, base.minNotional());

        Path dir = Files.createTempDirectory("revx-forecast");
        List<ExecJournal> journals = new ArrayList<>();
        List<QuoteLoop> loops = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        AllocRegistry alloc = new AllocRegistry(dir.resolve("alloc.db").toString());
        // Ведро живёт в том же файле, что и реестр, — как на живом аккаунте.
        PlacementBudget sharedBudget =
                Boolean.getBoolean("revx.forecast.budget") && bots.size() > 1
                        ? new PlacementBudget(dir.resolve("alloc.db").toString(), bots.size())
                        : null;
        // В прогоне процесс один: терять претензию некому, а продление стоило
        // 23 тысяч записей на бота (модельная минута пролетает мгновенно).
        alloc.heartbeatOff();
        try {
            for (BotSpec spec : bots) {
                ExecJournal journal = new ExecJournal(
                        dir.resolve("bot-" + spec.botId() + ".db").toString());
                journal.clock(clock);
                // Котировки в базу не пишем: единственное, ради чего они писались,
                // теперь считается в памяти. Живому боту так делать НЕЛЬЗЯ —
                // у него по ним восстанавливаются захват и markout.
                // Котировки нужны только выгрузке: по ним считается часовой разрез

                // цены запаса тем же прибором, что и на живых ботах.

                if (journalOut == null || journalOut.isBlank()) {

                    journal.quotesOff();

                }
                journals.add(journal);

                // ⚠️ СТАРТОВЫЙ ЗАПАС НАДО И ЗАХВАТИТЬ, И ЗАСЕЯТЬ.
                //
                // Здесь стоял ноль, и это сводило на нет любой стартовый
                // инвентарь: на площадке монета есть, а бот с `ownPosition`
                // считает своим только ЗАХВАЧЕННОЕ, то есть ничего. Поймано
                // 11.09.2026: прогон с `-Drevx.sim.start-inventory=0.3` дал
                // числа, совпавшие с нулевым запасом до десятых процента.
                double baseSeed = startBase / bots.size();
                alloc.claim(spec.botId(), base.symbol().substring(0, base.symbol().indexOf('/')),
                        baseSeed, startBase, refPrice, start);
                alloc.claim(spec.botId(), base.symbol().substring(base.symbol().indexOf('/') + 1),
                        quoteStart / bots.size(), quoteStart, refPrice, start);

                Quoter.Params params = new Quoter.Params(spec.offset(), spec.size(),
                        spec.inventoryCap(), base.skewK(), spec.skewTarget(), cfg.simDriftBeta(),
                        cfg.simBuySizeRatio(), cfg.simDriftWindowMs(), cfg.simSizeShapeEta(),
                        cfg.simDriftGateEr(), cfg.simErWindowMs(), cfg.simErSampleMs(),
                        cfg.simStopDrawdownPct(), Quoter.Sticky.OFF, Quoter.Frozen.OFF,
                        Quoter.Hedge.OFF, cfg.simStopCoolOffMs(), cfg.simRequoteThreshold(),
                        base.quoteStep());
                QuoteLoop loop = new QuoteLoop(venue, clock, fair, journal, params,
                        base.symbol(), base.periodMs(), base.minNotional(),
                        new BotTag(spec.botId()),
                        Executor.buildPolicy(params, base.costFloorMargin(), base.anchorLeash(),
                                base.anchorWidening(), base.widening(), base.wideningMaxStep(),
                                spec.size(), spec.inventoryCap(), base.quoteStep()),
                        true, baseSeed, base.baseStep(), base.parkDistance(), alloc,
                        spec.levels(), spec.levelStep(), spec.innerFirst());
                // ⚠️ ОБЩЕЕ ВЕДРО ПОСТАНОВОК — по ключу, а не всегда.
                //
                // По умолчанию лимит поднят до заведомо недостижимого: иначе
                // многоуровневый режим упирается в него и глохнет, и меряется не
                // экономика пары, а скорость выгорания бюджета.
                //
                // Но когда ботов несколько, ведро и есть предмет измерения:
                // 1000 постановок в сутки — на ВЕСЬ аккаунт, и три бота их
                // делят. С `-Drevx.forecast.budget=true` прогон получает то же
                // ведро, что и живые боты (`PlacementBudget` на общей базе), и
                // тогда видно главное: во сколько обходится соседство.
                if (sharedBudget != null) {
                    loop.placementBudget(sharedBudget);
                } else {
                    // -Drevx.sim.placement-cap — живой предел бота (100/250) вместо
                    // снятого: для прогонов поведения у предела постановок.
                    loop.placementCap(Integer.getInteger("revx.sim.placement-cap", 100_000));
                }
                // ⚠️ И денежные пределы — тоже в масштабе лота. Они записаны в
                // абсолютных долларах под лот $1 (заявка ≤ $10, экспозиция ≤ $40),
                // и прогон с лотом $10 упирался в них раньше, чем в рынок: 15.5
                // млн отказов «экспозиция превысила предел» и ровные нули дохода
                // при живом рынке (06.09.2026). Меряли предохранитель, не пару.
                loop.scaleLimitsForLot(spec.size() * refPrice);
                if (spec.dynOffsetK() > 0) {
                    // Потолок 1% — выше опора считается сломанной (замер
                    // 19.08.2026: 1.18% у ETH на движении 18%).
                    loop.dynamicOffset(spec.dynOffsetK(), 1.0);
                }
                // Потолок нужен счётчику «доля времени в потолке» — раньше его
                // считали, перечитывая журнал.
                // Сменные модули: расстановка и приведение книги. Пусто — встроенный
                // путь, то есть поведение до появления модулей.
                var mods = org.home.data.revx.layout.Modules.of(
                        System.getProperty("revx.modules", ""), params,
                        Executor.buildPolicy(params, base.costFloorMargin(), base.anchorLeash(),
                                base.anchorWidening(), base.widening(), base.wideningMaxStep(),
                                spec.size(), spec.inventoryCap(), base.quoteStep()),
                        spec.levels(), spec.levelStep(), spec.innerFirst(),
                        spec.dynOffsetK(), 1.0, 1.0);
                if (!mods.builtIn()) {
                    loop.modules(mods.layout(), mods.placer());
                }
                // РЕАКЦИЯ НА СВИП — тот же сторож, что в бою. Заводится только
                // тем ботам, у кого доля положительна: так в одном прогоне стоят
                // рядом «с реакцией» и «без», и различаются они ровно этим.
                //
                // ⚠️ Задержка ленты обязана быть НЕНУЛЕВОЙ. Живой бот узнаёт о
                // принте не в момент сделки, а когда доедет опрос (2.0 с после
                // A83); стенд с нулём смоделировал бы бота, которого не бывает,
                // и весь выигрыш был бы в этой разнице.
                // ⚠️ Сравнение с нулём, а не «больше нуля»: ОТРИЦАТЕЛЬНАЯ доля —
                // это плацебо с перевёрнутым знаком, и оно обязано доходить до
                // сторожа. Калитка `> 0` молча возвращала базовый прогон, то есть
                // плацебо показало бы ровно ноль разницы и выглядело бы как
                // «эффект несимметричен».
                // Сдвиг по перевесу тейкеров Бинанса — второй источник, складывается

                // со свипом (корреляция между ними 0.012-0.053, задача A81).

                if (spec.flowCoef() != 0) {
                    var fw = new org.home.data.revx.exec.FlowWatch(cfg.cryptoDb(),
                            base.symbol(), spec.flowCoef(), cfg.execSweepMaxBp(),
                            spec.flowDelayMs(), start, end);
                    flows.add(fw);
                    loop.flowWatch(fw);
                }

                if (spec.sweepCoef() != 0) {
                    if (standDbPath == null) {
                        throw new IllegalStateException("реакция на свип просит базу стенда: "
                                + "ленту брать неоткуда");
                    }
                    // Лента грузится в память на всё окно: у revx_trade нет ни
                    // одного индекса, и запрос на каждом тике превращался в
                    // 2.4 млрд чтений — прогон переставал укладываться в
                    // предохранитель и печатал обрезанный результат.
                    var watch = org.home.data.revx.exec.SweepWatch.preloaded(standDbPath,
                            base.symbol(), cfg.execSweepMinNotional(), cfg.execSweepChainMs(),
                            spec.sweepCoef(), cfg.execSweepMaxBp(), spec.sweepDelayMs(),
                            start, end);
                    watches.add(watch);
                    loop.sweepWatch(watch, spec.sweepSide());
                }
                if (model instanceof MarketFillModel mfm) {
                    loop.bookSource(mfm.market().fresh()::bookAt);
                }
                loop.statsInventoryCap(spec.inventoryCap());
                loops.add(loop);
            }
            for (int i = 1; i < loops.size(); i++) {
                clock.join();
            }
            // Конец записи останавливает ВСЕХ: иначе оставшиеся ждали бы у барьера.
            clock.stopAt(end, () -> loops.forEach(QuoteLoop::shutdown));

            for (QuoteLoop loop : loops) {
                loop.startQuoting();
            }
            // ⚠️ УПАВШИЙ КОТИРОВЩИК НЕ ИМЕЕТ ПРАВА ВЫГЛЯДЕТЬ КАК НОЛЬ СДЕЛОК.
            // Раньше исключение оставалось в потоке, поток тихо умирал, а отчёт
            // печатал «0 покупок, 0 продаж, доход +0.0000» — то есть ошибку,
            // неотличимую от честного результата «настройка не торгует».
            // Поймано 09.09.2026 на PEPE вне выборки.
            List<Throwable> crashes = java.util.Collections.synchronizedList(new ArrayList<>());
            for (QuoteLoop loop : loops) {
                final int slotIndex = threads.size();
                Thread t = new Thread(() -> {
                    clock.assignSlot(slotIndex);
                    try {
                        loop.run();
                    } finally {
                        clock.leave();
                    }
                }, "forecast-" + loop.botId());
                t.setUncaughtExceptionHandler((thread, e) -> {
                    log.error("котировщик {} упал: {}", thread.getName(), e.toString(), e);
                    crashes.add(e);
                });
                threads.add(t);
                t.start();
            }
            for (Thread t : threads) {
                t.join(JOIN_TIMEOUT_MS);
            }
            // 🔑 ⚠️ НЕДОСЧИТАННЫЙ ПРОГОН ВЫГЛЯДЕЛ КАК ЧЕСТНЫЙ РЕЗУЛЬТАТ.
            //
            // `join` с таймаутом возвращает управление и тогда, когда поток ЖИВ.
            // Главный поток шёл печатать отчёт, пока котировщик ещё торговал, и
            // числа получались обрезанные ровно настолько, насколько загружена
            // машина. Поймано 21.09.2026: один и тот же прогон (BTC, окно
            // 14–16.09, доля свипа 1) дал 36 сделок и −0.0905 на свободной
            // машине и 17 сделок и −0.0504 на занятой, при том что контрольная
            // ветка совпала до знака — она быстрая и успевала обе.
            //
            // Это та же болезнь, что и «упавший котировщик выглядел как ноль
            // сделок»: ошибка, неотличимая от результата. Таймаут оставлен как
            // предохранитель от зависания на барьере, но молчать он больше не
            // имеет права.
            List<String> stuck = threads.stream().filter(Thread::isAlive)
                    .map(Thread::getName).toList();
            if (!stuck.isEmpty()) {
                throw new IllegalStateException("прогон НЕ ДОСЧИТАН за "
                        + JOIN_TIMEOUT_MS / 60_000 + " мин: ещё торгуют " + stuck
                        + ". Числа такого прогона зависят от скорости машины, "
                        + "а не от настройки — печатать их нельзя");
            }
            if (!crashes.isEmpty()) {
                throw new IllegalStateException("прогон не состоялся: упало котировщиков "
                        + crashes.size() + ", первый — " + crashes.get(0), crashes.get(0));
            }

            log.warn("площадка исполнила заявок: {} (это НЕ то же, что заметил бот)",
                    venue.appliedFills());
            log.warn("объём: {}", venue.fillDiag());
            log.warn("затыки площадки: {}", venue.stallDiag());
            log.warn("ТЕЙКЕР: {}", venue.takerDiag());
            log.warn("присутствие в книге: {}", venue.presence());
            // ⚠️ Почему исполнений мало — вопрос, на который до 08.09.2026 нечем
            // было ответить: модель считала пропуски, но никуда их не выводила.
            // Без этих трёх чисел «модель занижает втрое» не разложить на
            // причины, и калибровать приходится вслепую.
            if (model instanceof MarketFillModel m) {
                log.warn("модель исполнения: очередью {}, перехватом {}, "
                                + "ПРОПУЩЕНО из-за невидимости {}",
                        m.queueFills(), m.interceptFills(), m.invisibleSkips());
                log.warn("{}", m.queueBlockStats());
                log.warn("{}", m.gates().render());
            }
            for (int i = 0; i < loops.size(); i++) {
                QuoteLoop l = loops.get(i);
                log.warn("ДОХОД ПО УРОВНЯМ, бот {}:{}", l.botId(),
                        levelBreakdown(journals.get(i), bots.get(i).size()));
                log.warn("по уровням, бот {}:%n{}", l.botId(), l.levelPresence());
                log.warn("бот {}: {}", l.botId(), l.effectiveOffset());
                log.warn("бот {}: не стоять первым — сдвигов {}", l.botId(), l.behindShifts());
            }
            if (sharedBudget != null) {
                // Ради чего всё и затевалось: сколько ведра осталось и кто
                // сколько взял. Без этой строки соседство ботов не видно.
                long endMs = ticks.get(ticks.size() - 1).tsMs();
                for (BotSpec s : bots) {
                    PlacementBudget.State st = sharedBudget.state(s.botId(), endMs);
                    log.warn("ВЕДРО, бот {}: своих постановок за сутки {}, всего на аккаунте {}, "
                                    + "токенов осталось {}, давление {}%",
                            s.botId(), st.ownSpendDay(), st.totalSpendDay(),
                            Math.round(st.tokens()), Math.round(st.pressure() * 100));
                }
            }
            List<BotResult> out = new ArrayList<>();
            for (int i = 0; i < bots.size(); i++) {
                out.add(measure(bots.get(i), journals.get(i), loops.get(i), base,
                        Math.max(1e-9, (end - start) / 86_400_000.0), ticks, startBase / bots.size()));
            }
            return out;
        } finally {
            journals.forEach(ExecJournal::close);
            if (sharedBudget != null) {
                sharedBudget.close();
            }
            // ⚠️ Реестр закрывать ОБЯЗАТЕЛЬНО, иначе на Windows файл alloc.db
            // остаётся заблокированным, удаление каталога молча не проходит, и
            // временные каталоги копятся: к 07.09.2026 их набралось 4861 штука
            // примерно на 19 ГБ. Уборка была написана, но её нечем было
            // выполнить — открытое соединение держало файл.
            alloc.close();
            // ⚠️ Соединения закрываются ДО копирования: SQLite держит файл, и
            // копия открытой базы приезжает без последних записей.
            if (journalOut != null && !journalOut.isBlank()) {
                Path outDir = Path.of(journalOut);
                Files.createDirectories(outDir);
                for (ExecJournal j : journals) {
                    j.close();
                }
                String base0 = base.symbol().substring(0, base.symbol().indexOf('/'));
                for (BotSpec spec : bots) {
                    Path src = dir.resolve("bot-" + spec.botId() + ".db");
                    if (Files.exists(src)) {
                        Files.copy(src, outDir.resolve(base0 + "-"
                                        + spec.botId().replace("/", "_") + ".db"),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                log.warn("журналы прогона выгружены в {}", outDir.toAbsolutePath());
            }
            delete(dir);
        }
    }


    /**
     * РАЗРЕЗ ДОХОДА ПО УРОВНЯМ СЕТКИ.
     *
     * Отвечает на вопрос, который до 11.09.2026 задать было нечем: дальние
     * уровни зарабатывают или числятся? Доход был известен только целиком по
     * боту, а {@code levelPresence} показывает лишь, сколько тиков уровень
     * простоял в книге, — присутствие, а не вклад.
     *
     * Считается ЗАХВАТ: насколько выгоднее справедливой цены прошла сделка.
     * Это та же величина, которой раскладывался живой доход по дням, и она
     * складывается по уровням честно — в отличие от реализованного P&L, где
     * покупка одного уровня закрывается продажей другого и разнести нельзя.
     */
    private static String levelBreakdown(ExecJournal journal, double lot) {
        List<ExecJournal.LevelFill> fills = journal.levelFills();
        if (fills.isEmpty()) {
            return "исполнений нет";
        }
        Map<Integer, List<Double>> edges = new TreeMap<>();
        Map<Integer, Double> gross = new TreeMap<>();
        Map<Integer, Integer> count = new TreeMap<>();
        Map<Integer, Integer> buys = new TreeMap<>();
        Map<Integer, Double> bought = new TreeMap<>();
        Map<Integer, Double> sold = new TreeMap<>();
        for (ExecJournal.LevelFill f : fills) {
            if (!(f.fair() > 0)) {
                continue;
            }
            double edgeBp = (f.buy() ? (f.fair() - f.price()) : (f.price() - f.fair()))
                    / f.fair() * 1e4;
            edges.computeIfAbsent(f.level(), k -> new ArrayList<>()).add(edgeBp);
            gross.merge(f.level(), edgeBp / 1e4 * f.qty() * f.price(), Double::sum);
            count.merge(f.level(), 1, Integer::sum);
            if (f.buy()) {
                buys.merge(f.level(), 1, Integer::sum);
                bought.merge(f.level(), f.qty(), Double::sum);
            } else {
                sold.merge(f.level(), f.qty(), Double::sum);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%n  %-8s %7s %7s %12s %12s %11s%n",
                "уровень", "покуп", "прод", "захват,б.п.", "валовое,$", "нетто,лот"));
        for (Integer level : edges.keySet()) {
            List<Double> v = new ArrayList<>(edges.get(level));
            Collections.sort(v);
            int nb = buys.getOrDefault(level, 0);
            // ⚠️ КРУГ МОЖЕТ ИДТИ ЧЕРЕЗ РАЗНЫЕ УРОВНИ: купили на дальнем, продали
            // на ближнем. Поэтому кредитуется КАЖДАЯ НОГА отдельно, а не круг, и
            // «нетто» показывает перекос уровня: сильно положительное значит,
            // что уровень в основном НАБИРАЕТ, а разгружают его соседи.
            double net = bought.getOrDefault(level, 0.0) - sold.getOrDefault(level, 0.0);
            sb.append(String.format(Locale.ROOT, "  %-8s %7d %7d %12.2f %12.4f %11.2f%n",
                    level < 0 ? "неизв." : String.valueOf(level),
                    nb, count.get(level) - nb, v.get(v.size() / 2), gross.get(level), net / lot));
        }
        return sb.toString();
    }
    static BotResult measure(BotSpec spec, ExecJournal journal,
                                     QuoteLoop loop, BootParams base, double days,
                                     List<ReplayFair.Tick> ticks, double seedQty) {
        FifoLedger ledger = new FifoLedger();
        // ⚠️ СТАРТОВЫЙ ЗАПАС ОБЯЗАН ВОЙТИ В КНИГУ ПАРТИЙ, И ПО ЦЕНЕ ОТКРЫТИЯ.
        //
        // Без этого продажа запаса не находит встречной партии, книга открывает
        // КОРОТКУЮ позицию по цене продажи, а следующая покупка её закрывает —
        // и на падающем рынке это записывается в прибыль, пропорциональную
        // падению. Поймано 11.09.2026: прогон с полным запасом дал у SOL +577%
        // годовых на ПАДАЮЩЕМ окне против +12% с пустым. Подаренный лот
        // засчитывался как заработанный.
        if (seedQty > 1e-15 && !ticks.isEmpty() && ticks.get(0).fair() > 0) {
            ledger.add(ticks.get(0).tsMs(), true, seedQty, ticks.get(0).fair(), 0);
        }
        int fills = 0;
        int buys = 0;
        int sells = 0;
        for (ExecJournal.FillRow f : journal.fills()) {
            if (f.handover()) {
                continue;
            }
            fills++;
            if (f.buy()) {
                buys++;
            } else {
                sells++;
            }
            ledger.add(f.tsMs(), f.buy(), f.qty(), f.price(), f.fee());
        }
        // Доля времени с ПОЛНЫМ инвентарём. Пока бот упёрт в потолок, он только
        // продаёт: покупать нечем, и половина конструкции простаивает. Без этого
        // числа «доход за окно» скрывает, какой ценой он получен.
        // Считаем ПО СЧЁТЧИКАМ ЦИКЛА, а не перечитыванием журнала: ради этого
        // одного числа на каждый тик писалась строка в SQLite. Замер 07.09.2026
        // дал 39 МБ/с записи и 666 операций в секунду при чтении 0.4 МБ/с —
        // обход упирался в собственный журнал, а не в данные.
        QuoteLoop.Stats st = loop.stats();
        if (QuoteLoop.LEVEL_COOLDOWN_MS > 0) {
            log.warn("пауза уровня после покупки {} с: включалась {} раз",
                    QuoteLoop.LEVEL_COOLDOWN_MS / 1000, loop.levelCoolStarts());
        }
        long tickCount = st.ticks();
        double atCap = st.ticksAtCap();
        // ⚠️ РЕАЛИЗОВАННОЕ ПЛЮС ПЕРЕОЦЕНКА ОСТАТКА, А НЕ ОДНО РЕАЛИЗОВАННОЕ.
        //
        // Поймано 12.09.2026 на поле по себестоимости. Правило «не опускать аск
        // ниже цены входа» показало +111% годовых там, где без него было −15, и
        // это оказалось артефактом учёта: закрытые круги при таком правиле ВСЕ
        // прибыльные, а убыточные позиции просто не закрываются и висят в
        // инвентаре. Занятость это выдала — 5 лотов медианы против 1 и 12%
        // времени у потолка против нуля.
        //
        // ⚠️ Дыра не только у пола: ЛЮБОЕ сравнение настроек с разным носимым
        // инвентарём по одному лишь реализованному нечестно. Лестница, гейт и
        // придвижение аска сравнивались до этой правки, и их числа надо читать с
        // поправкой на то, сколько лотов осталось на руках.
        double unrealised = ledger.position().unrealised(st.lastFair());
        double[] rounds = roundsNetOfMarket(ledger, ticks, spec.size());
        return new BotResult(spec.botId(), spec.offset() * 10_000, fills,
                ledger.tradingRealisedSince(0) + unrealised,
                spec.size() > 0 ? st.inventory() / spec.size() : 0,
                st.placements(), st.replaces(),
                org.home.data.revx.exec.ExecLimits.maxPlacementsPerDay(spec.botId()), days,
                st.state(), journal.countEvents("loss_stop"),
                tickCount > 0 ? atCap / tickCount : 0,
                // ⚠️ Номинал лота в валюте котировки, а НЕ размер в базовой.
                // Зашитая цена биткойна здесь врала на SOL втрое: лот $1
                // печатался как 788.
                spec.size() * st.lastFair(), buys, sells, byDay(ledger, ticks),
                tickCount > 0 ? (double) st.ticksEmpty() / tickCount : 0, st.lotHist(),
                ledger.holdMinutes(0)[0], ledger.holdMinutes(0)[1], volBpPerMin(ticks),
                rounds[0], rounds[1], rounds[2], rounds[3],
                // 🔑 ПЕРЕОЦЕНКА ОСТАТКА рядом с кругами. Суточное отношение
                // считается по ЗАКРЫТЫМ кругам, а закрытый круг — выживший:
                // позиция, в которой цена ушла и не вернулась, в него не
                // попадает и сидит в остатке. Без этой колонки «настройка
                // хорошая, а денег нет» остаётся необъяснённым.
                days > 0 && spec.size() * st.lastFair() > 0
                        ? unrealised / (spec.size() * st.lastFair()) * 10_000 / days : 0);
    }

    /**
     * КРУГИ ЗА ВЫЧЕТОМ РЫНКА: {@code {средний, СКО, сколько их, средний рынок}}.
     *
     * Из каждого закрытого круга вычитается средний ход опоры за ТО ЖЕ время,
     * посчитанный от КАЖДОЙ минуты окна. Это безусловный контроль: без него
     * отрицательный результат ничего не значит — спот-бот всегда начинает с
     * покупки, и падающее окно даёт минус любой настройке.
     *
     * ⚠️ Круги с передачами выброшены: подаренный лот заработком не является.
     * ⚠️ Открытые к концу окна партии сюда не входят, и это смещение в лучшую
     * сторону — незакрытый круг и есть тот, где цена ушла и не вернулась.
     * Величина этого смещения видна в «занятости инвентаря».
     */
    private static double[] roundsNetOfMarket(FifoLedger ledger, List<ReplayFair.Tick> ticks,
                                              double lotSize) {
        java.util.TreeMap<Long, Double> byMinute = new java.util.TreeMap<>();
        for (ReplayFair.Tick t : ticks) {
            if (t.fair() > 0) {
                byMinute.putIfAbsent(t.tsMs() / 60_000, t.fair());
            }
        }
        List<Double> net = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double marketSum = 0;
        int shorts = 0;
        for (FifoLedger.Realisation r : ledger.realisations()) {
            if (r.handover() || !(r.entry() > 0) || !(r.exit() > 0) || !(r.qty() > 0)) {
                continue;
            }
            // 🔑 РЕЗУЛЬТАТ БЕРЁТСЯ ИЗ `pnl`, А НЕ ВЫЧИСЛЯЕТСЯ ИЗ ЦЕН.
            //
            // Первая версия считала `(выход − вход)/вход` и тем самым объявляла
            // ЛОНГОМ каждую пару. А книга партий открывает и КОРОТКИЕ: продажа
            // сверх инвентаря (расхождение с площадкой, затравка, обнуление на
            // границе суток) кладёт отрицательную партию, и у такой пары знак
            // обратный. На окне 10–14.09 это дало BTC @6 отношение +2.84 при
            // фактических годовых −65%: половина «прибыльных кругов» была
            // короткими парами с перевёрнутым знаком. Это ровно та ошибка,
            // которая уже описана в javadoc FifoLedger и стоила восьми дней
            // (медиана 209 минут вместо 8.5).
            double bp = r.pnl() / (r.qty() * r.entry()) * 10_000;
            boolean isLong = Math.abs(r.pnl() - (r.exit() - r.entry()) * r.qty())
                    <= 1e-9 * Math.max(1, Math.abs(r.pnl()));
            if (!isLong) {
                shorts++;
                continue;              // короткие пары — артефакт учёта, не наша торговля
            }
            int mins = (int) Math.max(1, Math.round(r.heldMs() / 60_000.0));
            double market = marketDrift(byMinute, mins);
            // ⚠️ ВЕС — ДОЛЯ ЛОТА, А НЕ ЕДИНИЦА.
            //
            // Одна продажа закрывает несколько частично набранных партий, и
            // тогда у одного круга появляется две-три записи. Считать их
            // поштучно значит дать надкусанному кругу тот же вес, что целому:
            // на сутках 11.09 записей 176 при 92 продажах. Вес в долях лота
            // возвращает мерке денежный смысл.
            double w = lotSize > 0 ? r.qty() / lotSize : 1;
            marketSum += market * w;
            weights.add(w);
            net.add(bp - market);
        }
        if (shorts > 0) {
            log.warn("мерка настройки: коротких пар {} из {} — выброшены (спот-бот в шорт "
                    + "не ходит, значит это расхождение учёта)", shorts,
                    shorts + net.size());
        }
        // 🔑 ВОЗВРАЩАЮТСЯ СУММЫ, А НЕ СРЕДНИЕ.
        //
        // Обход считает каждые сутки отдельным прогоном и складывает клетки.
        // Если складывать средние и делить на число суток, получится среднее
        // средних: сутки с тремя кругами весят столько же, сколько сутки с
        // тремястами. Так и вышло расхождение «отношение +2.84 при годовых
        // −65%» на окне 10–14.09 — знак решали редкие сутки.
        double sum = 0;
        double sumSq = 0;
        double count = 0;
        for (int i = 0; i < net.size(); i++) {
            double x = net.get(i);
            double w = weights.get(i);
            sum += x * w;
            sumSq += x * x * w;
            count += w;
        }
        return new double[]{sum, sumSq, count, marketSum};
    }

    /** Средний ход опоры за {@code h} минут от каждой минуты окна, б.п. */
    private static double marketDrift(java.util.TreeMap<Long, Double> byMinute, int h) {
        double sum = 0;
        int n = 0;
        for (java.util.Map.Entry<Long, Double> e : byMinute.entrySet()) {
            Double later = byMinute.get(e.getKey() + h);
            if (later == null || e.getValue() <= 0) {
                continue;
            }
            sum += 10_000 * (later - e.getValue()) / e.getValue();
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    /**
     * Разбивка результата по суткам, рядом с движением цены за эти сутки.
     *
     * Среднее за окно скрывает главное: конструкция может выигрывать на росте и
     * проваливаться на падении, и тогда «универсальность» у неё только на бумаге.
     * Реализация датируется моментом ЗАКРЫТИЯ пары, поэтому доход за сутки —
     * разность накопленного на границах.
     *
     * ⚠️ Здесь ТОЛЬКО реализованное, без переоценки остатка — в отличие от
     * итогового результата, куда переоценка входит с 12.09.2026. Суточная
     * разбивка потребовала бы марки на каждую границу суток, а её нет. Значит
     * суммы по суткам не сходятся с итогом ровно на изменение переоценки, и
     * сравнивать настройки по суточной таблице нельзя — только по итогу.
     */
    private static List<Day> byDay(FifoLedger ledger, List<ReplayFair.Tick> ticks) {
        List<Day> out = new ArrayList<>();
        if (ticks.isEmpty()) {
            return out;
        }
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter
                .ofPattern("dd.MM").withZone(java.time.ZoneOffset.UTC);
        long dayMs = 86_400_000L;
        long first = ticks.get(0).tsMs() / dayMs * dayMs;
        long last = ticks.get(ticks.size() - 1).tsMs();
        for (long d = first; d <= last; d += dayMs) {
            final long from = d;
            final long to = d + dayMs;
            double realised = ledger.tradingRealisedSince(from) - ledger.tradingRealisedSince(to);
            int fills = ledger.tradingClosedSince(from) - ledger.tradingClosedSince(to);
            Double open = null;
            Double close = null;
            for (ReplayFair.Tick t : ticks) {
                if (t.tsMs() >= from && t.tsMs() < to && t.fair() > 0) {
                    if (open == null) {
                        open = t.fair();
                    }
                    close = t.fair();
                }
            }
            if (open == null) {
                continue;
            }
            out.add(new Day(fmt.format(java.time.Instant.ofEpochMilli(d)),
                    (close / open - 1) * 100, realised, fills));
        }
        return out;
    }

    /** Разбивка по суткам: кто на каком рынке хорош. */
    public static String renderDays(String modelName, List<BotResult> results) {
        if (results.isEmpty() || results.get(0).days_().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\nпо суткам (" + modelName + "), реализовано:\n");
        sb.append(String.format("%-8s | %8s", "сутки", "цена"));
        for (BotResult r : results) {
            sb.append(String.format(" | %10s", "бот " + r.botId()));
        }
        sb.append('\n');
        List<Day> ref = results.get(0).days_();
        for (int i = 0; i < ref.size(); i++) {
            sb.append(String.format(Locale.ROOT, "%-8s | %+7.2f%%", ref.get(i).label(),
                    ref.get(i).movePct()));
            for (BotResult r : results) {
                sb.append(String.format(Locale.ROOT, " | %+10.4f",
                        i < r.days_().size() ? r.days_().get(i).realised() : 0));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public static String render(String modelName, List<BotResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("модель: ").append(modelName).append('\n');
        // ⚠️ Постановки и замены приводятся К СУТКАМ, а лимиты у площадки тоже
        // суточные. Прежде печаталось «634/300» — общее число за 5.4 суток
        // против СУТОЧНОГО потолка, и это читалось как «пробил лимит», хотя на
        // деле было 117 в сутки.
        // ⚠️ Покупки и продажи РАЗДЕЛЬНО. Одно общее число исполнений скрывает
        // главное: не набирает бот инвентарь или не может его сбыть. Это разные
        // болезни с разными причинами, а выглядят они одинаково.
        // ⚠️ «Пусто» стоит рядом с «в потолке» НАМЕРЕННО. Порознь каждая доля
        // обманчива: бот у потолка не покупает, пустой не продаёт, и обе
        // выглядят в отчёте как обычная работа. Живьём чаще вторая (10.09.2026:
        // без аска 15% времени у BTC, 24% у SOL, 44% у ETH), а лечатся они
        // ПРОТИВОПОЛОЖНЫМИ движениями потолка.
        sb.append("бот | отступ |  лот | покупок | продаж | реализовано | инвентарь"
                + " | пусто | в потолке | держ,мин | риск,б.п. | ЗАХВ/РИСК"
                + " | постановок/сут | на постановку | замен/с\n");
        // ⚠️ ЗАХВАТ/РИСК — ГЛАВНАЯ КОЛОНКА, а не доход. Доход зависит от того,
        // какая ценовая траектория попалась в окно: на августовском окне он
        // советовал широкие ступени, а живьём они дали хвост — у бота A шесть
        // худших кругов из 62 отняли втрое больше, чем заработали остальные 56.
        // Отношение 2δ к σ√T от траектории не зависит. Меньше единицы означает,
        // что мы продаём опцион дешевле его стоимости; живьём 11.09.2026 так
        // было у пяти ботов из шести (задача A15).
        for (BotResult r : results) {
            // ⚠️ Состояние на конец прогона печатается не для полноты. Бот
            // встаёт сам, когда торговый убыток против buy & hold превышает
            // MAX_TRADING_LOSS_USDC = 1.0, а на многодневном окне с $20
            // инвентаря и движением в 5% это обычное дело. Прогон, где бот
            // простоял три четверти окна, внешне неотличим от честного, и
            // сравнивать их между собой нельзя.
            String state = r.lossStops() > 0 ? "  СТОП по убытку ×" + r.lossStops() : "";
            // ⚠️ Доход НА ПОСТАНОВКУ — главная величина для сравнения алгоритмов.
            // Суточная тысяча постановок на весь аккаунт это единственный жёсткий
            // ресурс площадки: перевыставление идёт через PUT без потолка, а
            // POST конечен. Значит выбирать надо не по доходу за окно, а по
            // доходу на единицу этого ресурса — иначе выиграет тот, кто просто
            // потратил больше бюджета.
            double perPlacement = r.placements() > 0 ? r.realised() / r.placements() : 0;
            sb.append(String.format(Locale.ROOT,
                    "%-3s | %5.1f  | %4.2f | %7d | %6d | %+11.4f | %8.1f  | %4.1f%% | %8.1f%% "
                            + "| %8.0f | %9.1f | %9.2f | %6.0f/%-5d | %+13.6f | %6.2f%s%n",
                    r.botId(), r.offsetBp(), r.lotNotional(), r.buys(), r.sells(), r.realised(),
                    r.inventoryLots(), 100 * r.emptyShare(), 100 * r.atCapShare(),
                    r.holdMedMin(), r.riskBp(), r.payPerRisk(),
                    r.placements() / r.days(), r.placementCap(), perPlacement,
                    r.replaces() / (r.days() * 86_400), state));
        }
        sb.append(lotHistogram(results));
        return sb.toString();
    }

    /**
     * 🔑 С ЧЕГО НАЧИНАТЬ ОКНО, И ПОЧЕМУ ЭТО НЕ МЕЛОЧЬ.
     *
     * По умолчанию берётся ФАКТИЧЕСКИЙ инвентарь живого бота на первом тике —
     * так прогон продолжает запись с того места, где она началась. Это верно,
     * когда окно вырезано из жизни одного бота с одной настройкой.
     *
     * ⚠️ Но когда в одном прогоне сравниваются РАЗНЫЕ отступы, фактический
     * инвентарь принадлежит только одному из них: бот, весь предыдущий день
     * работавший на четырёх базисных пунктах, пришёл бы к полуночи с другим
     * запасом, чем боевой с двенадцатью. Смещение одинаково у всех ступеней и
     * потому почти не трогает их РАЗНОСТЬ, но уровни между ступенями сравнивать
     * из-за него хуже.
     *
     * {@code revx.forecast.start-inventory}:
     * <ul>
     *   <li>пусто — как было, инвентарь из записи;</li>
     *   <li>{@code target} — цель скоса, то есть та позиция, к которой бот и так
     *       стремится. Снимает переходный процесс: иначе первые часы короткого
     *       окна бот просто набирает запас, и этот разгон заслоняет всё
     *       остальное;</li>
     *   <li>число — доля суммарного потолка.</li>
     * </ul>
     */
    static double startInventory(List<ReplayFair.Tick> ticks, List<BotSpec> bots) {
        String mode = System.getProperty("revx.forecast.start-inventory", "").trim();
        if (mode.isEmpty()) {
            return ticks.get(0).inventory();
        }
        if ("target".equalsIgnoreCase(mode)) {
            // Цель у ступеней может различаться — складываем их собственные цели.
            double sum = 0;
            for (BotSpec b : bots) {
                sum += b.inventoryCap() * b.skewTarget();
            }
            return sum;
        }
        double capAll = bots.stream().mapToDouble(BotSpec::inventoryCap).sum();
        return capAll * Double.parseDouble(mode);
    }

    /**
     * Сколько дал бы ПРОСТОЙ ДЕРЖАТЕЛЬ: стартовый запас и ни одной сделки.
     *
     * Предложение владельца 16.09.2026, и оно закрывает дыру в чтении прогона.
     * Доход за окно складывается из двух разных вещей: из торговли и из того,
     * что инвентарь подорожал или подешевел сам. На падающем окне вторая
     * величина заслоняет первую целиком — все ступени в минусе, и без этой
     * строки непонятно, торговля ли виновата.
     *
     * Считается как {@code запас × (цена в конце − цена в начале)}. Разность
     * «бот минус держатель» и есть вклад САМОЙ торговли.
     *
     * ⚠️ Это не то же самое, что «рынок» в мерке настройки (задача A46): там
     * вычитается средний ход опоры за то же время от КАЖДОЙ минуты окна, здесь —
     * ровно один путь от начала до конца. Первое — безусловный контроль,
     * второе — фактическая альтернатива «не торговать».
     */
    public static String renderHold(List<ReplayFair.Tick> ticks, List<BotSpec> bots) {
        double first = 0;
        for (ReplayFair.Tick t : ticks) {
            if (t.fair() > 0) {
                first = t.fair();
                break;
            }
        }
        double last = 0;
        for (int i = ticks.size() - 1; i >= 0; i--) {
            if (ticks.get(i).fair() > 0) {
                last = ticks.get(i).fair();
                break;
            }
        }
        double startBase = startInventory(ticks, bots);
        double hold = (first > 0 && last > 0) ? startBase * (last - first) : 0;
        return String.format(Locale.ROOT,
                "%nБЕЗ ТОРГОВЛИ: запас %.8f, цена %.2f → %.2f (%+.2f%%), итог %+.4f USDC.%n"
                        + "Разность «бот минус эта строка» и есть вклад самой торговли.%n",
                startBase, first, last,
                first > 0 ? 100 * (last - first) / first : 0, hold);
    }

    /**
     * Гистограмма времени по числу лотов в руках.
     *
     * Отвечает на вопрос, на который не отвечают ни средний инвентарь, ни две
     * доли на краях: сколько лотов боту РЕАЛЬНО нужно. Если хвост за седьмым
     * лотом пустой, потолок в двадцать лотов — это замороженные деньги, а не
     * запас; если горб упёрт в последнюю графу, потолок мал.
     */
    private static String lotHistogram(List<BotResult> results) {
        int width = 0;
        for (BotResult r : results) {
            if (r.lotHist() != null) {
                width = Math.max(width, r.lotHist().length);
            }
        }
        if (width == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\nДОЛЯ ВРЕМЕНИ ПО ЧИСЛУ ЛОТОВ В РУКАХ, %\n");
        sb.append("бот | отступ");
        for (int i = 0; i < width; i++) {
            sb.append(String.format(Locale.ROOT, " |%5d", i));
        }
        sb.append(" | медиана\n");
        for (BotResult r : results) {
            long[] h = r.lotHist();
            if (h == null || h.length == 0) {
                continue;
            }
            long total = 0;
            for (long v : h) {
                total += v;
            }
            if (total == 0) {
                continue;
            }
            sb.append(String.format(Locale.ROOT, "%-3s | %5.1f  ", r.botId(), r.offsetBp()));
            long running = 0;
            int median = 0;
            boolean found = false;
            for (int i = 0; i < width; i++) {
                long v = i < h.length ? h[i] : 0;
                sb.append(String.format(Locale.ROOT, " |%5.1f", 100.0 * v / total));
                running += v;
                if (!found && running * 2 >= total) {
                    median = i;
                    found = true;
                }
            }
            sb.append(String.format(Locale.ROOT, " | %7d%n", median));
        }
        return sb.toString();
    }

    static void delete(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // временный каталог; остаток уберёт система
                }
            });
        } catch (Exception ignored) {
            // то же самое
        }
    }
}
