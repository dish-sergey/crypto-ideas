package org.home.data.eval.bench;

import org.home.data.detector.RegimeDetectorV3;

import java.util.Arrays;

/**
 * Кандидаты стенда сравнения (док. 15 v4 §5). Кандидат — каузальная функция цены в
 * ряд состояний: состояние дня t зависит только от данных ≤ t (проверяется §7.1).
 * Допустимые состояния: BULL, BEAR, RANGE, TRANSITION (null — до прогрева).
 */
public final class Candidates {

    private Candidates() {
    }

    public interface Candidate {
        String key();

        String[] predict(double[] high, double[] low, double[] close);
    }

    /** baseline_always_range: детектора нет, всегда RANGE. Ноль параметров. */
    public static final class AlwaysRange implements Candidate {
        public String key() {
            return "baseline_always_range";
        }

        public String[] predict(double[] high, double[] low, double[] close) {
            String[] s = new String[close.length];
            Arrays.fill(s, "RANGE");
            return s;
        }
    }

    /** baseline_sma200: close > SMA200 → BULL, иначе BEAR. Ноль параметров, одна строка. */
    public static final class Sma200 implements Candidate {
        private static final int N = 200;

        public String key() {
            return "baseline_sma200";
        }

        public String[] predict(double[] high, double[] low, double[] close) {
            int n = close.length;
            String[] s = new String[n];
            double sum = 0;
            for (int i = 0; i < n; i++) {
                sum += close[i];
                if (i >= N) {
                    sum -= close[i - N];
                }
                if (i >= N - 1) {
                    s[i] = close[i] > sum / N ? "BULL" : "BEAR";
                }
            }
            return s;
        }
    }

    /**
     * cusum: два накопителя лог-доходности с симметричными порогами (§5.2). Накапливает
     * свидетельства, а не усредняет — серия однонаправленных движений срабатывает раньше
     * пересечения медленной средней (t09). Параметры δ, h — из конфига, до прогона.
     */
    public static final class Cusum implements Candidate {
        private final double delta;
        private final double h;

        public Cusum(double delta, double h) {
            this.delta = delta;
            this.h = h;
        }

        public String key() {
            return "cusum";
        }

        public String[] predict(double[] high, double[] low, double[] close) {
            int n = close.length;
            String[] s = new String[n];
            double gUp = 0, gDown = 0;
            String state = "RANGE";
            s[0] = state;
            for (int i = 1; i < n; i++) {
                double l = Math.log(close[i] / close[i - 1]);
                gUp = Math.max(0, gUp + l - delta);
                gDown = Math.max(0, gDown - l - delta);
                if (gUp > h) {
                    state = "BULL";
                    gUp = 0;
                    gDown = 0;
                } else if (gDown > h) {
                    state = "BEAR";
                    gUp = 0;
                    gDown = 0;
                }
                s[i] = state;
            }
            return s;
        }
    }

    /**
     * baseline_sma200 + гейт наклона (doc 21 §4): к пересечению SMA200 добавлено третье
     * состояние RANGE, когда средняя плоская. Параметры ЗАФИКСИРОВАНЫ в doc 21 §4.5,
     * не подбираются: окно наклона N=30, порог 0.5·ATR90.
     * <pre>
     *   slope = (SMA200_t − SMA200_{t−30}) / ATR90
     *   close>SMA200 и slope ≥ +0.5  → BULL
     *   close<SMA200 и slope ≤ −0.5  → BEAR
     *   иначе (плоская средняя или несогласие цены и наклона) → RANGE
     * </pre>
     */
    public static final class Sma200SlopeGate implements Candidate {
        private static final int SMA_N = 200;
        private static final int ATR_N = 90;
        private static final int SLOPE_N = 30;
        private static final double THR = 0.5;

        public String key() {
            return "sma200_slope_gate";
        }

        public String[] predict(double[] high, double[] low, double[] close) {
            int n = close.length;
            String[] s = new String[n];
            double[] sma = new double[n], atr = new double[n];
            java.util.Arrays.fill(sma, Double.NaN);
            java.util.Arrays.fill(atr, Double.NaN);
            double sum = 0;
            for (int i = 0; i < n; i++) {
                sum += close[i];
                if (i >= SMA_N) {
                    sum -= close[i - SMA_N];
                }
                if (i >= SMA_N - 1) {
                    sma[i] = sum / SMA_N;
                }
            }
            double[] tr = new double[n];
            tr[0] = high[0] - low[0];
            for (int i = 1; i < n; i++) {
                double pc = close[i - 1];
                tr[i] = Math.max(high[i] - low[i], Math.max(Math.abs(high[i] - pc), Math.abs(low[i] - pc)));
            }
            double ts = 0;
            for (int i = 0; i < n; i++) {
                ts += tr[i];
                if (i >= ATR_N) {
                    ts -= tr[i - ATR_N];
                }
                if (i >= ATR_N - 1) {
                    atr[i] = ts / ATR_N;
                }
            }
            for (int i = 0; i < n; i++) {
                if (i < SMA_N - 1 + SLOPE_N || Double.isNaN(atr[i]) || atr[i] <= 0) {
                    continue;
                }
                double slope = (sma[i] - sma[i - SLOPE_N]) / atr[i];
                if (close[i] > sma[i] && slope >= THR) {
                    s[i] = "BULL";
                } else if (close[i] < sma[i] && slope <= -THR) {
                    s[i] = "BEAR";
                } else {
                    s[i] = "RANGE";
                }
            }
            return s;
        }
    }

    /** rules2d: оси D/T детектора v3 (doc 01 v4 §2–3), пороги фиксированы, не подбираются. */
    public static final class Rules2d implements Candidate {
        public String key() {
            return "rules2d";
        }

        public String[] predict(double[] high, double[] low, double[] close) {
            return RegimeDetectorV3.statesFromPrice(high, low, close);
        }
    }
}
