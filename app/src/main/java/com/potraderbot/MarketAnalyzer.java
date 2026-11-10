package com.potraderbot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Multi-factor rule-based market analysis.
 * Scenario scores are heuristic confluence scores, NOT calibrated probabilities.
 * Only closed candles should be supplied.
 */
public class MarketAnalyzer {
    public enum Signal { CALL, PUT, WAIT }

    public static class AnalysisReport {
        public final Signal signal;
        public final int confidence;
        public final int bullishScore;
        public final int bearishScore;
        public final String marketRegime;
        public final String scenarios;
        public final String reasons;

        AnalysisReport(Signal signal, int confidence, int bullishScore, int bearishScore,
                       String marketRegime, String scenarios, String reasons) {
            this.signal = signal;
            this.confidence = confidence;
            this.bullishScore = bullishScore;
            this.bearishScore = bearishScore;
            this.marketRegime = marketRegime;
            this.scenarios = scenarios;
            this.reasons = reasons;
        }

        public String toDisplayText() {
            return "РЕЖИМ: " + marketRegime
                    + "\nСогласованность факторов: " + confidence + "/100"
                    + "\nФакторы вверх: " + bullishScore + "/100"
                    + "\nФакторы вниз: " + bearishScore + "/100"
                    + "\nСЦЕНАРИИ (эвристические оценки, не вероятности):\n" + scenarios
                    + "\nПОЧЕМУ: " + reasons;
        }
    }

    public Signal analyze(List<Candle> candles) {
        return analyzeDetailed(candles).signal;
    }

    public AnalysisReport analyzeDetailed(List<Candle> c) {
        if (c == null || c.size() < 60) {
            return new AnalysisReport(Signal.WAIT, 0, 0, 0, "недостаточно данных",
                    "Расчёт невозможен", "Нужно минимум 60 закрытых минутных свечей");
        }

        int n = c.size();
        Candle last = c.get(n - 1);
        Candle prev = c.get(n - 2);
        double e9 = ema(c, 9), e21 = ema(c, 21), e50 = ema(c, 50);
        double rsi = rsi(c, 14);
        double macdHist = macdHistogram(c);
        double atr = atr(c, 14);
        double atrPct = last.close == 0 ? 0 : atr / last.close * 100.0;
        double adx = adxProxy(c, 14);
        double bbPosition = bollingerPosition(c, 20, 2);
        int structure = structureDirection(c, 24);
        int candlePattern = candlePattern(c);
        int breakout = breakoutState(c, 20);
        List<Candle> five = aggregate(c, 5);
        List<Candle> fifteen = aggregate(c, 15);
        int trend5 = trendDirection(five);
        int trend15 = trendDirection(fifteen);

        int bull = 0, bear = 0;
        StringBuilder why = new StringBuilder();

        if (e9 > e21 && e21 > e50) { bull += 14; add(why, "EMA 9/21/50: восходящее выравнивание"); }
        else if (e9 < e21 && e21 < e50) { bear += 14; add(why, "EMA 9/21/50: нисходящее выравнивание"); }
        else add(why, "EMA: смешанная структура");

        if (trend5 > 0) { bull += 8; add(why, "5m: восходящий контекст"); }
        else if (trend5 < 0) { bear += 8; add(why, "5m: нисходящий контекст"); }
        if (trend15 > 0) { bull += 12; add(why, "15m: восходящий контекст"); }
        else if (trend15 < 0) { bear += 12; add(why, "15m: нисходящий контекст"); }

        if (rsi >= 52 && rsi <= 68) { bull += 9; add(why, "RSI поддерживает бычий импульс"); }
        else if (rsi <= 48 && rsi >= 32) { bear += 9; add(why, "RSI поддерживает медвежий импульс"); }
        else if (rsi > 72 || rsi < 28) add(why, "RSI в экстремальной зоне: риск отката");

        if (macdHist > 0) bull += 9;
        else if (macdHist < 0) bear += 9;
        if (Math.abs(macdHist) > atr * 0.03) add(why, "MACD: импульс выражен");

        if (adx >= 22) {
            if (e9 > e21) bull += 8;
            else if (e9 < e21) bear += 8;
            add(why, "ADX proxy: движение имеет направленность");
        } else add(why, "ADX proxy: слабый тренд/диапазон");

        if (structure > 0) bull += 10;
        else if (structure < 0) bear += 10;
        if (structure != 0) add(why, structure > 0 ? "Структура: повышающиеся swing-уровни" : "Структура: понижающиеся swing-уровни");

        if (candlePattern > 0) bull += 8;
        else if (candlePattern < 0) bear += 8;
        if (candlePattern != 0) add(why, candlePattern > 0 ? "Свечная модель: бычье подтверждение" : "Свечная модель: медвежье подтверждение");

        if (breakout > 0) { bull += 10; add(why, "Подтверждённый пробой локального максимума"); }
        else if (breakout < 0) { bear += 10; add(why, "Подтверждённый пробой локального минимума"); }
        else if (breakout == 2) add(why, "Возможный ложный пробой вверх");
        else if (breakout == -2) add(why, "Возможный ложный пробой вниз");

        if (bbPosition > 0.65 && bbPosition < 0.95) bull += 5;
        else if (bbPosition < 0.35 && bbPosition > 0.05) bear += 5;

        boolean abnormalVolatility = atrPct > averageAtrPercent(c, 50) * 1.8;
        String regime = adx < 18 ? "боковой рынок" : (adx >= 25 ? "трендовый рынок" : "переходный режим");
        if (abnormalVolatility) regime += ", повышенная волатильность";
        if (atrPct < 0.015) regime += ", низкая волатильность";

        int max = Math.max(bull, bear);
        int diff = Math.abs(bull - bear);
        int confidence = Math.min(100, max);
        Signal signal = Signal.WAIT;
        if (!abnormalVolatility && adx >= 16 && diff >= 22 && max >= 58) {
            if (bull > bear && trend15 >= 0) signal = Signal.CALL;
            else if (bear > bull && trend15 <= 0) signal = Signal.PUT;
        }
        if (rsi >= 75 || rsi <= 25) signal = Signal.WAIT;
        if (trend5 != 0 && trend15 != 0 && trend5 != trend15) signal = Signal.WAIT;

        String scenarios = scenarioScores(bull, bear, adx, atrPct, abnormalVolatility, breakout, structure);
        if (why.length() == 0) why.append("Недостаточно согласованных факторов");
        why.append(String.format(Locale.US,
                " | RSI %.1f, ADX* %.1f, ATR %.4f%%, BB-position %.2f", rsi, adx, atrPct, bbPosition));

        return new AnalysisReport(signal, confidence, bull, bear, regime, scenarios, why.toString());
    }

    private static void add(StringBuilder b, String s) {
        if (b.length() > 0) b.append("; ");
        b.append(s);
    }

    private static double ema(List<Candle> c, int p) {
        int start = Math.max(0, c.size() - p * 5);
        double k = 2.0 / (p + 1), value = c.get(start).close;
        for (int i = start + 1; i < c.size(); i++) value = c.get(i).close * k + value * (1 - k);
        return value;
    }

    private static double rsi(List<Candle> c, int p) {
        double gain = 0, loss = 0;
        int start = c.size() - p;
        for (int i = start; i < c.size(); i++) {
            double d = c.get(i).close - c.get(i - 1).close;
            if (d > 0) gain += d; else loss -= d;
        }
        if (loss == 0) return 100;
        double rs = gain / Math.max(1e-12, loss);
        return 100 - 100 / (1 + rs);
    }

    private static double macdHistogram(List<Candle> c) {
        return ema(c, 12) - ema(c, 26) - emaMacdSignal(c, 9);
    }

    private static double emaMacdSignal(List<Candle> c, int p) {
        List<Double> m = new ArrayList<>();
        for (int i = 0; i < c.size(); i++) {
            List<Candle> prefix = c.subList(0, i + 1);
            if (i >= 25) m.add(ema(prefix, 12) - ema(prefix, 26));
        }
        if (m.isEmpty()) return 0;
        double k = 2.0 / (p + 1), v = m.get(0);
        for (int i = 1; i < m.size(); i++) v = m.get(i) * k + v * (1 - k);
        return v;
    }

    private static double atr(List<Candle> c, int p) {
        double sum = 0;
        int start = Math.max(1, c.size() - p);
        for (int i = start; i < c.size(); i++) {
            Candle x = c.get(i), prev = c.get(i - 1);
            sum += Math.max(x.high - x.low, Math.max(Math.abs(x.high - prev.close), Math.abs(x.low - prev.close)));
        }
        return sum / Math.max(1, c.size() - start);
    }

    // Directional movement strength proxy; a full Wilder ADX is not claimed here.
    private static double adxProxy(List<Candle> c, int p) {
        double up = 0, down = 0, tr = 0;
        int start = Math.max(1, c.size() - p);
        for (int i = start; i < c.size(); i++) {
            Candle a = c.get(i), b = c.get(i - 1);
            double plus = a.high - b.high, minus = b.low - a.low;
            if (plus > minus && plus > 0) up += plus;
            if (minus > plus && minus > 0) down += minus;
            tr += Math.max(a.high - a.low, Math.max(Math.abs(a.high - b.close), Math.abs(a.low - b.close)));
        }
        if (tr <= 0) return 0;
        return Math.min(100, Math.abs(up - down) / tr * 100);
    }

    private static double bollingerPosition(List<Candle> c, int p, double mult) {
        int start = c.size() - p;
        double mean = 0;
        for (int i = start; i < c.size(); i++) mean += c.get(i).close;
        mean /= p;
        double variance = 0;
        for (int i = start; i < c.size(); i++) variance += Math.pow(c.get(i).close - mean, 2);
        double sd = Math.sqrt(variance / p);
        if (sd == 0) return 0.5;
        double upper = mean + mult * sd, lower = mean - mult * sd;
        return (c.get(c.size() - 1).close - lower) / Math.max(1e-12, upper - lower);
    }

    private static double averageAtrPercent(List<Candle> c, int p) {
        int start = Math.max(20, c.size() - p);
        double total = 0; int count = 0;
        for (int i = start; i < c.size(); i++) {
            List<Candle> prefix = c.subList(0, i + 1);
            double close = c.get(i).close;
            if (close > 0) { total += atr(prefix, 14) / close * 100; count++; }
        }
        return count == 0 ? 0 : total / count;
    }

    private static int structureDirection(List<Candle> c, int lookback) {
        int n = c.size(), half = Math.max(4, lookback / 2);
        double h1 = maxHigh(c, n - half, n), h0 = maxHigh(c, n - lookback, n - half);
        double l1 = minLow(c, n - half, n), l0 = minLow(c, n - lookback, n - half);
        if (h1 > h0 && l1 > l0) return 1;
        if (h1 < h0 && l1 < l0) return -1;
        return 0;
    }

    private static int candlePattern(List<Candle> c) {
        Candle a = c.get(c.size() - 2), b = c.get(c.size() - 1);
        double body = Math.abs(b.close - b.open);
        double range = Math.max(1e-12, b.high - b.low);
        double lowerWick = Math.min(b.open, b.close) - b.low;
        double upperWick = b.high - Math.max(b.open, b.close);
        boolean bullEngulf = a.close < a.open && b.close > b.open
                && b.open <= a.close && b.close >= a.open;
        boolean bearEngulf = a.close > a.open && b.close < b.open
                && b.open >= a.close && b.close <= a.open;
        if (bullEngulf || (lowerWick > body * 2 && upperWick < range * 0.25 && b.close > b.open)) return 1;
        if (bearEngulf || (upperWick > body * 2 && lowerWick < range * 0.25 && b.close < b.open)) return -1;
        return 0;
    }

    // +1/-1 confirmed close beyond prior range; +2/-2 rejection wick beyond range.
    private static int breakoutState(List<Candle> c, int lookback) {
        int n = c.size();
        double high = maxHigh(c, n - lookback - 1, n - 1);
        double low = minLow(c, n - lookback - 1, n - 1);
        Candle x = c.get(n - 1);
        double range = Math.max(1e-12, x.high - x.low);
        if (x.close > high) return 1;
        if (x.close < low) return -1;
        if (x.high > high && x.close < high && (x.high - Math.max(x.open, x.close)) / range > 0.45) return 2;
        if (x.low < low && x.close > low && (Math.min(x.open, x.close) - x.low) / range > 0.45) return -2;
        return 0;
    }

    private static double maxHigh(List<Candle> c, int from, int to) {
        double v = -Double.MAX_VALUE;
        for (int i = Math.max(0, from); i < Math.min(c.size(), to); i++) v = Math.max(v, c.get(i).high);
        return v;
    }
    private static double minLow(List<Candle> c, int from, int to) {
        double v = Double.MAX_VALUE;
        for (int i = Math.max(0, from); i < Math.min(c.size(), to); i++) v = Math.min(v, c.get(i).low);
        return v;
    }

    private static List<Candle> aggregate(List<Candle> source, int minutes) {
        List<Candle> out = new ArrayList<>();
        int complete = source.size() / minutes * minutes;
        for (int i = 0; i < complete; i += minutes) {
            Candle first = source.get(i);
            double high = -Double.MAX_VALUE, low = Double.MAX_VALUE;
            for (int j = i; j < i + minutes; j++) {
                high = Math.max(high, source.get(j).high);
                low = Math.min(low, source.get(j).low);
            }
            Candle last = source.get(i + minutes - 1);
            out.add(new Candle(first.time, first.open, high, low, last.close));
        }
        return out;
    }

    private static int trendDirection(List<Candle> c) {
        if (c.size() < 8) return 0;
        double fast = ema(c, 5), slow = ema(c, 8);
        if (fast > slow && c.get(c.size() - 1).close > slow) return 1;
        if (fast < slow && c.get(c.size() - 1).close < slow) return -1;
        return 0;
    }

    private static String scenarioScores(int bull, int bear, double adx, double atrPct,
                                         boolean highVol, int breakout, int structure) {
        int total = Math.max(1, bull + bear);
        int continuation = Math.min(100, Math.max(bull, bear));
        int pullback = (int) Math.max(0, 55 - Math.abs(bull - bear) * 0.35 + (adx > 25 ? 8 : 0));
        int reversal = (int) Math.min(90, (structure == 0 ? 35 : 22) + (breakout == 2 || breakout == -2 ? 30 : 0));
        int range = (int) Math.max(0, 75 - adx * 1.5);
        int breakoutScore = breakout == 1 || breakout == -1 ? 78 : 20;
        int volatility = highVol ? 85 : (atrPct < 0.015 ? 55 : 25);
        return "Продолжение текущего направления: " + continuation + "/100"
                + "\nКоррекция: " + pullback + "/100"
                + "\nРазворот/ложный пробой: " + reversal + "/100"
                + "\nБоковое движение: " + range + "/100"
                + "\nСценарий пробоя: " + breakoutScore + "/100"
                + "\nРиск повышенной волатильности: " + volatility + "/100";
    }
}
