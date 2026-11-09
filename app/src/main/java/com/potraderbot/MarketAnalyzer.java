package com.potraderbot;

import java.util.List;

/** EMA(9/21) + Wilder RSI(14) signal filter. Signals are informational, not predictions. */
public final class MarketAnalyzer {
    public enum Signal { CALL, PUT, WAIT }

    public static final class Analysis {
        public final Signal signal;
        public final double ema9;
        public final double ema21;
        public final double rsi14;
        public final String explanation;

        Analysis(Signal signal, double ema9, double ema21, double rsi14, String explanation) {
            this.signal = signal;
            this.ema9 = ema9;
            this.ema21 = ema21;
            this.rsi14 = rsi14;
            this.explanation = explanation;
        }
    }

    public Analysis analyze(List<Candle> candles) {
        if (candles == null || candles.size() < 30) {
            return new Analysis(Signal.WAIT, 0, 0, 0, "Недостаточно закрытых свечей (нужно минимум 30).");
        }
        for (int i = 1; i < candles.size(); i++) {
            if (candles.get(i).time <= candles.get(i - 1).time) {
                return new Analysis(Signal.WAIT, 0, 0, 0, "Свечи пришли не в хронологическом порядке.");
            }
        }

        double ema9 = ema(candles, 9);
        double ema21 = ema(candles, 21);
        double rsi = rsiWilder(candles, 14);
        Candle last = candles.get(candles.size() - 1);
        Candle previous = candles.get(candles.size() - 2);
        boolean bullish = last.close > last.open && last.close > previous.close;
        boolean bearish = last.close < last.open && last.close < previous.close;

        if (ema9 > ema21 && rsi >= 50 && rsi <= 68 && bullish) {
            return new Analysis(Signal.CALL, ema9, ema21, rsi,
                    "EMA 9 выше EMA 21, RSI в допустимой зоне, последняя свеча бычья.");
        }
        if (ema9 < ema21 && rsi >= 32 && rsi <= 50 && bearish) {
            return new Analysis(Signal.PUT, ema9, ema21, rsi,
                    "EMA 9 ниже EMA 21, RSI в допустимой зоне, последняя свеча медвежья.");
        }
        return new Analysis(Signal.WAIT, ema9, ema21, rsi,
                "Условия стратегии не совпали. Лучше пропустить вход.");
    }

    private double ema(List<Candle> candles, int period) {
        double multiplier = 2.0 / (period + 1.0);
        double value = candles.get(0).close;
        for (int i = 1; i < candles.size(); i++) {
            value = candles.get(i).close * multiplier + value * (1.0 - multiplier);
        }
        return value;
    }

    private double rsiWilder(List<Candle> candles, int period) {
        double gains = 0;
        double losses = 0;
        for (int i = 1; i <= period; i++) {
            double change = candles.get(i).close - candles.get(i - 1).close;
            if (change > 0) gains += change;
            else losses -= change;
        }
        double averageGain = gains / period;
        double averageLoss = losses / period;
        for (int i = period + 1; i < candles.size(); i++) {
            double change = candles.get(i).close - candles.get(i - 1).close;
            double gain = Math.max(change, 0);
            double loss = Math.max(-change, 0);
            averageGain = (averageGain * (period - 1) + gain) / period;
            averageLoss = (averageLoss * (period - 1) + loss) / period;
        }
        if (averageLoss == 0) return averageGain == 0 ? 50 : 100;
        double rs = averageGain / averageLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }
}
