package org.home.data.theory.band;

import java.util.OptionalDouble;
import java.util.Random;

/**
 * Целевая доля и полоса бездействия (ТЗ 68 §3–§4).
 *
 * <p>Библиотека с чистым API: никакого глобального или статического изменяемого
 * состояния, все параметры — аргументы методов. Вызывается из стендов ТЗ 63
 * (маркет-мейкинг), ТЗ 66 (сайзинг S5) и будущих.
 *
 * <p><b>Главное:</b> формула ширины полосы выведена в пределе малых издержек, а
 * у проекта издержки не малы (0.1–0.2%). Поэтому центральный результат — не
 * формула, а сравнение асимптотической ширины с численно-оптимальной.
 */
public final class BandMath {

    private BandMath() {
    }

    /**
     * Целевая доля риска.
     *
     * @param piStar          {@code μ/(γσ²)}; при {@code γ=1} это Kelly {@code μ/σ²}
     * @param piStarShrunk    усаженная версия (если передана SE оценки μ)
     * @param ciLow           нижняя граница интервала π* по SE(μ)
     * @param ciHigh          верхняя граница
     * @param shrinkageApplied применялась ли усадка — обязательное поле отчёта
     */
    public record TargetWeight(double piStar, double piStarShrunk, double ciLow, double ciHigh,
                               boolean shrinkageApplied) {
    }

    /**
     * Целевая доля (§3.1). <b>Функция отказывается работать без измеренной μ</b>
     * (§3.2): никаких значений по умолчанию и никакого «предположим 20% годовых» —
     * отсутствие измеренной μ означает, что целевой доли нет, и всё остальное
     * задание к этому случаю неприменимо.
     *
     * @param mu    измеренная избыточная доходность (годовая, в долях)
     * @param sigma годовая волатильность
     * @param gamma коэффициент неприятия риска
     * @param muSe  стандартная ошибка оценки μ; если передана — считается усадка
     */
    public static TargetWeight targetWeight(double mu, double sigma, double gamma, OptionalDouble muSe) {
        if (!Double.isFinite(mu)) {
            throw new IllegalArgumentException("μ не измерена: целевой доли нет, задание неприменимо (§3.2)");
        }
        if (!(sigma > 0) || !(gamma > 0)) {
            throw new IllegalArgumentException("σ и γ должны быть положительны");
        }
        double piStar = mu / (gamma * sigma * sigma);
        if (muSe.isEmpty()) {
            return new TargetWeight(piStar, piStar, Double.NaN, Double.NaN, false);
        }
        double se = muSe.getAsDouble();
        // усадка к нулю по той же схеме, что в ТЗ 66 §4.3: апостериорное среднее
        // μ̂·σ²_prior/(σ²_prior + σ²_μ̂); при неинформативном приоре σ²_prior = μ̂²
        double shrinkFactor = mu * mu / (mu * mu + se * se);
        double shrunk = piStar * shrinkFactor;
        double low = (mu - 1.645 * se) / (gamma * sigma * sigma);
        double high = (mu + 1.645 * se) / (gamma * sigma * sigma);
        return new TargetWeight(piStar, shrunk, low, high, true);
    }

    /**
     * Полоса бездействия.
     *
     * @param lower            нижняя граница доли
     * @param upper            верхняя граница
     * @param width            ширина
     * @param welfareLossOrder порядок потери полезности {@code ε^{2/3}}
     */
    public record Band(double lower, double upper, double width, double welfareLossOrder) {
    }

    /**
     * Асимптотика, <b>выведенная для целевой функции этого модуля</b> (П5 док. 71).
     *
     * <p>Вывод занимает пять строк и снимает вопрос о множителе. Для узкой полосы
     * полушириной Δ вокруг π* стационарная плотность доли риска почти равномерна,
     * поэтому потеря темпа роста от отклонения равна
     * {@code (γσ²/2)·E[(π−π*)²] = (γσ²/6)·Δ²}. Интенсивность местного времени на
     * каждой из двух границ равна {@code s²/(4Δ)} при {@code s = σπ*(1−π*)},
     * значит издержки равны {@code ε·s²/(2Δ)}. Минимум суммы:
     * <pre>(γσ²/3)·Δ = ε·s²/(2Δ²)  ⇒  Δ³ = (3/(2γ))·π*²(1−π*)²·ε</pre>
     *
     * <p>Отсюда константа {@code 3/(2γ)}, а не {@code 3/(4γ)} из формулировки ТЗ:
     * они отличаются ровно в {@code 2^{1/3} ≈ 1.26} раза — то самое «стабильное
     * отношение», которое первый прогон списал на «систематический коэффициент
     * реализации». Численное решение совпадает <b>с этой</b> константой, что и
     * проверяется positive control-ом.
     *
     * <p><b>σ на результат не влияет</b> (ТЗ 72 §10): она сокращается через
     * {@code π* = μ/(γσ²)}, в формуле Δ её нет. Параметр присутствует только ради
     * единообразия сигнатур.
     */
    public static Band bandAsymptoticDerived(double piStar, double gamma, double sigma, double epsilon) {
        double halfWidth = Math.cbrt(3.0 / (2 * gamma) * piStar * piStar
                * (1 - piStar) * (1 - piStar) * epsilon);
        return new Band(piStar - halfWidth, piStar + halfWidth, 2 * halfWidth,
                Math.pow(epsilon, 2.0 / 3.0));
    }

    /** Отношение констант ТЗ и вывода: {@code (3/2)/(3/4)} под кубическим корнем. */
    public static final double TZ_CONSTANT_RATIO = Math.cbrt(2);

    /**
     * Асимптотическая полоса в формулировке ТЗ (правило кубического корня, §4.1):
     * <pre>Δ = (3/(4γ)·π*²·(1−π*)²)^{1/3}·ε^{1/3}</pre>
     *
     * <p><b>Асимптотика при малых ε.</b> У проекта ε порядка 0.1–0.2% — это не
     * режим малых издержек, и применимость формулы проверяется численно
     * ({@link #bandNumeric}), а не предполагается.
     *
     * <p><b>Конвенция ε</b> (ТЗ 72 §10): константа {@code 3/(4γ)} отвечает полному
     * спреду, {@code 3/(2γ)} — односторонней стоимости сделки. Корпус задаёт
     * издержки односторонне, поэтому операционная константа проекта —
     * {@link #bandAsymptoticDerived}, а этот метод остаётся для сверки.
     *
     * <p><b>σ на результат не влияет.</b> Она сокращается через {@code π* = μ/(γσ²)}
     * и в выражение для Δ не входит вовсе; параметр оставлен ради единой сигнатуры
     * с {@link #bandNumeric}, где σ действительно нужна (задаёт диффузию доли).
     */
    public static Band bandAsymptotic(double piStar, double gamma, double sigma, double epsilon) {
        double halfWidth = Math.cbrt(3.0 / (4 * gamma) * piStar * piStar
                * (1 - piStar) * (1 - piStar) * epsilon);
        return new Band(piStar - halfWidth, piStar + halfWidth, 2 * halfWidth,
                Math.pow(epsilon, 2.0 / 3.0));
    }

    /**
     * Численно-оптимальная полоса (§4.2) — <b>основной результат</b>.
     *
     * <p>Метод: стационарное распределение доли риска внутри полосы с отражением
     * на границах (ТЗ прямо разрешает «численное решение уравнения на
     * стационарное распределение доли» вместо симуляции). Целевая функция —
     * <b>геометрический</b> темп роста по траектории:
     * <pre>g(a,b) = ∫ p(π)·(πμ − γ/2·π²σ²) dπ − ε·(L_a + L_b)</pre>
     * где {@code p} — стационарная плотность, {@code L} — интенсивность местного
     * времени на границе (то есть оборот, который приходится торговать).
     * Ансамблевое среднее не используется сознательно: оператор живёт на одной
     * траектории (док. 60 §0.3).
     *
     * @param gridPoints число узлов по π — параметр сходимости (§7.2)
     * @param bandSteps  число шагов сетки по каждой границе полосы
     */
    public static Band bandNumeric(double mu, double sigma, double gamma, double epsilon,
                                   int gridPoints, int bandSteps) {
        double piStar = mu / (gamma * sigma * sigma);
        double reference = bandAsymptotic(piStar, gamma, sigma, epsilon).width() / 2;
        // Сетка смещений — ГЕОМЕТРИЧЕСКАЯ вокруг асимптотической полуширины: при
        // равномерной сетке минимальная различимая ширина упирается в шаг, и при
        // малых ε численное решение «застревает» на нём, изображая расхождение с
        // формулой (поймано positive control §4.4).
        double[] offsets = geometricOffsets(reference, piStar, bandSteps);
        double[] best = search(mu, sigma, gamma, epsilon, piStar, offsets, offsets, gridPoints);
        // локальное уточнение вокруг найденного оптимума
        double[] fineLower = refineAround(best[0], piStar, bandSteps);
        double[] fineUpper = refineAround(best[1], 1 - piStar, bandSteps);
        double[] refined = search(mu, sigma, gamma, epsilon, piStar, fineLower, fineUpper, gridPoints);
        double lower = piStar - refined[0];
        double upper = piStar + refined[1];
        return new Band(lower, upper, upper - lower, Math.pow(epsilon, 2.0 / 3.0));
    }

    /** Геометрическая сетка полуширин вокруг асимптотического ориентира. */
    private static double[] geometricOffsets(double reference, double limit, int steps) {
        double[] out = new double[steps + 1];
        double from = Math.max(reference / 16, 1e-5);
        double to = Math.min(Math.max(reference * 16, from * 2), Math.max(limit * 0.95, from * 2));
        for (int i = 0; i <= steps; i++) {
            out[i] = from * Math.pow(to / from, (double) i / steps);
        }
        return out;
    }

    private static double[] refineAround(double centre, double limit, int steps) {
        double[] out = new double[steps + 1];
        double from = Math.max(centre * 0.6, 1e-6);
        double to = Math.min(centre * 1.6, Math.max(limit * 0.95, from * 2));
        for (int i = 0; i <= steps; i++) {
            out[i] = from + (to - from) * i / steps;
        }
        return out;
    }

    /** @return {@code {лучшее смещение вниз, лучшее смещение вверх}} */
    private static double[] search(double mu, double sigma, double gamma, double epsilon, double piStar,
                                   double[] lowerOffsets, double[] upperOffsets, int gridPoints) {
        double best = Double.NEGATIVE_INFINITY;
        double bestLower = lowerOffsets[0];
        double bestUpper = upperOffsets[0];
        for (double down : lowerOffsets) {
            double lower = Math.max(piStar - down, 1e-4);
            for (double up : upperOffsets) {
                double upper = Math.min(piStar + up, 1 - 1e-4);
                if (upper - lower < 1e-9) {
                    continue;
                }
                double growth = growthRate(mu, sigma, gamma, epsilon, lower, upper, gridPoints);
                if (growth > best) {
                    best = growth;
                    bestLower = piStar - lower;
                    bestUpper = upper - piStar;
                }
            }
        }
        return new double[]{bestLower, bestUpper};
    }

    /**
     * Геометрический темп роста при политике «внутри [a,b] не торгуем, на границе
     * торгуем до границы».
     *
     * <p>Доля риска без торговли: {@code dπ = π(1−π)[(μ − σ²π)dt + σ dW]};
     * стационарная плотность отражённой диффузии
     * {@code p(π) ∝ (1/s²)·exp(∫ 2m/s²)}; интенсивность местного времени на
     * границе {@code L = s²/2·p}.
     */
    public static double growthRate(double mu, double sigma, double gamma, double epsilon,
                                    double lower, double upper, int gridPoints) {
        if (upper <= lower) {
            return Double.NEGATIVE_INFINITY;
        }
        double step = (upper - lower) / gridPoints;
        double[] density = new double[gridPoints + 1];
        double integral = 0;
        double previousRatio = ratio(mu, sigma, lower);
        for (int i = 0; i <= gridPoints; i++) {
            double pi = lower + i * step;
            double s2 = Math.pow(sigma * pi * (1 - pi), 2);
            if (s2 <= 1e-18) {
                density[i] = 0;
                continue;
            }
            if (i > 0) {
                double currentRatio = ratio(mu, sigma, pi);
                integral += 0.5 * (previousRatio + currentRatio) * step;   // трапеции
                previousRatio = currentRatio;
            }
            density[i] = Math.exp(integral) / s2;
        }
        double norm = 0;
        for (int i = 0; i <= gridPoints; i++) {
            norm += density[i] * step * (i == 0 || i == gridPoints ? 0.5 : 1);
        }
        if (!(norm > 0) || !Double.isFinite(norm)) {
            return Double.NEGATIVE_INFINITY;
        }
        double drift = 0;
        for (int i = 0; i <= gridPoints; i++) {
            double pi = lower + i * step;
            double weight = density[i] / norm * step * (i == 0 || i == gridPoints ? 0.5 : 1);
            drift += weight * (pi * mu - gamma / 2 * pi * pi * sigma * sigma);
        }
        double localTimeLower = Math.pow(sigma * lower * (1 - lower), 2) / 2 * density[0] / norm;
        double localTimeUpper = Math.pow(sigma * upper * (1 - upper), 2) / 2 * density[gridPoints] / norm;
        return drift - epsilon * (localTimeLower + localTimeUpper);
    }

    private static double ratio(double mu, double sigma, double pi) {
        double s2 = Math.pow(sigma * pi * (1 - pi), 2);
        if (s2 <= 1e-18) {
            return 0;
        }
        double m = pi * (1 - pi) * (mu - sigma * sigma * pi);
        return 2 * m / s2;
    }

    /**
     * Импульсный вариант (§4.5): при фиксированной части издержек оптимальная
     * политика — зона бездействия <b>плюс прыжок внутрь зоны</b>, а не к границе.
     *
     * @param triggerLow  нижний порог срабатывания
     * @param triggerHigh верхний порог
     * @param targetLow   куда прыгаем снизу
     * @param targetHigh  куда прыгаем сверху
     * @param growth      достигнутый темп роста
     */
    public record ImpulseBand(double triggerLow, double triggerHigh, double targetLow, double targetHigh,
                              double growth) {
    }

    /**
     * Импульсная политика, численно (§4.5). Считается симуляцией с общими
     * случайными числами по сетке порогов и целевых точек.
     *
     * <p>Требование к проверке: при {@code cFixed = 0} целевые точки обязаны
     * совпасть с границами срабатывания, то есть решение вырождается в полосу.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static ImpulseBand bandImpulse(double mu, double sigma, double gamma, double epsilon,
                                          double cFixed, double wealth, int steps, int paths,
                                          double dt, int gridSteps, long seed) {
        double piStar = mu / (gamma * sigma * sigma);
        double fixedShare = wealth > 0 ? cFixed / wealth : 0;
        double maxHalfWidth = Math.min(Math.max(piStar, 1 - piStar), 1.0);
        double best = Double.NEGATIVE_INFINITY;
        ImpulseBand bestBand = new ImpulseBand(piStar, piStar, piStar, piStar, Double.NEGATIVE_INFINITY);
        double[][] shocks = shocks(paths, steps, seed);

        for (int i = 1; i <= gridSteps; i++) {
            double low = piStar - maxHalfWidth * i / gridSteps;
            for (int j = 1; j <= gridSteps; j++) {
                double high = piStar + maxHalfWidth * j / gridSteps;
                for (int k = 0; k <= gridSteps; k++) {
                    double targetLow = low + (piStar - low) * k / gridSteps;
                    for (int l = 0; l <= gridSteps; l++) {
                        double targetHigh = high - (high - piStar) * l / gridSteps;
                        if (targetLow > targetHigh) {
                            continue;
                        }
                        double growth = simulateImpulse(mu, sigma, gamma, epsilon, fixedShare,
                                low, high, targetLow, targetHigh, shocks, dt);
                        if (growth > best) {
                            best = growth;
                            bestBand = new ImpulseBand(low, high, targetLow, targetHigh, growth);
                        }
                    }
                }
            }
        }
        return bestBand;
    }

    /**
     * Темп роста конкретной импульсной политики на общих случайных числах —
     * для <b>парного</b> сравнения политик (§7.2). Парное сравнение на одних и
     * тех же шоках различает политики, чья разница второго порядка мала: при
     * сравнении по отдельности её съедает шум симуляции.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static double impulseGrowth(double mu, double sigma, double gamma, double epsilon,
                                       double cFixedShare, double triggerLow, double triggerHigh,
                                       double targetLow, double targetHigh, int steps, int paths,
                                       double dt, long seed) {
        return simulateImpulse(mu, sigma, gamma, epsilon, cFixedShare, triggerLow, triggerHigh,
                targetLow, targetHigh, shocks(paths, steps, seed), dt);
    }

    private static double[][] shocks(int paths, int steps, long seed) {
        Random rnd = new Random(seed);
        double[][] out = new double[paths][steps];
        for (int p = 0; p < paths; p++) {
            for (int t = 0; t < steps; t++) {
                out[p][t] = rnd.nextGaussian();
            }
        }
        return out;
    }

    /** Геометрический темп роста импульсной политики на общих случайных числах. */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private static double simulateImpulse(double mu, double sigma, double gamma, double epsilon,
                                          double fixedShare, double triggerLow, double triggerHigh,
                                          double targetLow, double targetHigh, double[][] shocks,
                                          double dt) {
        double totalLog = 0;
        double sqrtDt = Math.sqrt(dt);
        for (double[] path : shocks) {
            double pi = (triggerLow + triggerHigh) / 2;
            double logWealth = 0;
            for (double z : path) {
                double drift = pi * mu - gamma / 2 * pi * pi * sigma * sigma;
                logWealth += drift * dt + pi * sigma * sqrtDt * z;
                // снос доли риска без торговли
                pi += pi * (1 - pi) * ((mu - sigma * sigma * pi) * dt + sigma * sqrtDt * z);
                pi = Math.max(1e-6, Math.min(1 - 1e-6, pi));
                if (pi <= triggerLow || pi >= triggerHigh) {
                    double target = pi <= triggerLow ? targetLow : targetHigh;
                    double traded = Math.abs(target - pi);
                    logWealth += Math.log(Math.max(1 - epsilon * traded - fixedShare, 1e-12));
                    pi = target;
                }
            }
            totalLog += logWealth / (path.length * dt);
        }
        return totalLog / shocks.length;
    }
}
