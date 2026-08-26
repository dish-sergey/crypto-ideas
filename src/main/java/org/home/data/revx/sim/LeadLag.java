package org.home.data.revx.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Кто кого ведёт: опорная книга USD или котируемая книга USDC.
 *
 * Зачем. На широком отступе markout положителен с обеих сторон — цена после
 * исполнения идёт в нашу пользу. У этого два несовместимых объяснения. Либо
 * широкая заявка достаётся только на транзиентных отклонениях, а они
 * возвращаются, — тогда это настоящий край. Либо ОПОРНАЯ КНИГА ОТСТАЁТ: в
 * момент исполнения «справедливая цена» уже устарела, и markout просто
 * догоняет то, что случилось раньше. Тогда уровень захвата и порог по комиссии
 * завышены, хотя сам чистый край к дефекту устойчив (он равен «купили ли мы
 * ниже, чем цена оказалась через Δ», и цена в момент исполнения в него не входит).
 *
 * Различает эти объяснения взаимная корреляция приращений на сдвигах. Если
 * опора отстаёт, корреляция максимальна при ПОЛОЖИТЕЛЬНОМ сдвиге: приращение
 * опоры в момент t+k повторяет приращение котируемой книги в момент t.
 */
public final class LeadLag {

    /**
     * @param lagWindows сдвиг в окнах (окно = период снимков, обычно 5 с)
     * @param correlation корреляция приращений опоры на t+lag с приращениями книги USDC на t
     */
    public record Point(int lagWindows, double correlation, int samples) {
    }

    public record Result(List<Point> points, Point peak, double referenceChangeRate,
                         double quotedChangeRate, int windows) {
    }

    private LeadLag() {
    }

    /**
     * @param maxLagWindows максимальный сдвиг в обе стороны
     */
    public static Result compute(List<SimEngine.Window> windows, int maxLagWindows) {
        List<Double> reference = new ArrayList<>();
        List<Double> quoted = new ArrayList<>();
        for (SimEngine.Window window : windows) {
            double fair = window.fair();
            BookView book = window.book();
            if (!(fair > 0) || book == null || book.empty()) {
                continue;
            }
            double mid = (book.bestBid() + book.bestAsk()) / 2;
            if (!(mid > 0)) {
                continue;
            }
            reference.add(fair);
            quoted.add(mid);
        }
        if (reference.size() < 3) {
            return new Result(List.of(), null, Double.NaN, Double.NaN, reference.size());
        }

        double[] refReturns = returns(reference);
        double[] quotedReturns = returns(quoted);

        List<Point> points = new ArrayList<>();
        Point peak = null;
        for (int lag = -maxLagWindows; lag <= maxLagWindows; lag++) {
            Point point = correlationAt(refReturns, quotedReturns, lag);
            points.add(point);
            if (peak == null || Math.abs(point.correlation()) > Math.abs(peak.correlation())) {
                peak = point;
            }
        }
        return new Result(points, peak, changeRate(refReturns), changeRate(quotedReturns),
                reference.size());
    }

    /** Доля окон, в которых ряд вообще изменился — прямая мера «живости» книги. */
    private static double changeRate(double[] returns) {
        long changed = java.util.Arrays.stream(returns).filter(r -> Math.abs(r) > 1e-12).count();
        return returns.length == 0 ? Double.NaN : (double) changed / returns.length;
    }

    private static double[] returns(List<Double> series) {
        double[] out = new double[series.size() - 1];
        for (int i = 1; i < series.size(); i++) {
            double prev = series.get(i - 1);
            out[i - 1] = prev > 0 ? series.get(i) / prev - 1 : 0;
        }
        return out;
    }

    /** corr(reference[t + lag], quoted[t]): lag > 0 означает, что опора отстаёт. */
    private static Point correlationAt(double[] reference, double[] quoted, int lag) {
        int from = Math.max(0, -lag);
        int to = Math.min(quoted.length, reference.length - lag);
        int n = to - from;
        if (n < 2) {
            return new Point(lag, Double.NaN, Math.max(0, n));
        }
        double sumX = 0;
        double sumY = 0;
        for (int i = from; i < to; i++) {
            sumX += reference[i + lag];
            sumY += quoted[i];
        }
        double meanX = sumX / n;
        double meanY = sumY / n;
        double covariance = 0;
        double varX = 0;
        double varY = 0;
        for (int i = from; i < to; i++) {
            double dx = reference[i + lag] - meanX;
            double dy = quoted[i] - meanY;
            covariance += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }
        double denominator = Math.sqrt(varX * varY);
        return new Point(lag, denominator > 0 ? covariance / denominator : Double.NaN, n);
    }
}
