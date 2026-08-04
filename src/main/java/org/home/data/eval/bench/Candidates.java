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
