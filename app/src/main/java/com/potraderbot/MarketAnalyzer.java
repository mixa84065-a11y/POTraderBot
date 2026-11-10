package com.potraderbot;

import java.util.List;

public class MarketAnalyzer {
    public enum Signal { CALL, PUT, WAIT }

    public Signal analyze(List<Candle> candles) {
        if (candles == null || candles.size() < 30) return Signal.WAIT;

        double ema9 = ema(candles, 9);
        double ema21 = ema(candles, 21);
        double rsi = rsi(candles, 14);
        Candle last = candles.get(candles.size() - 1);
        Candle prev = candles.get(candles.size() - 2);

        boolean bullishCandle = last.close > last.open && last.close > prev.close;
        boolean bearishCandle = last.close < last.open && last.close < prev.close;

        if (ema9 > ema21 && rsi >= 50 && rsi <= 68 && bullishCandle) return Signal.CALL;
        if (ema9 < ema21 && rsi <= 50 && rsi >= 32 && bearishCandle) return Signal.PUT;
        return Signal.WAIT;
    }

    private double ema(List<Candle> candles, int period) {
        double k = 2.0 / (period + 1);
        int start = Math.max(0, candles.size() - period * 4);
        double value = candles.get(start).close;
        for (int i = start + 1; i < candles.size(); i++)
            value = candles.get(i).close * k + value * (1 - k);
        return value;
    }

    private double rsi(List<Candle> candles, int period) {
        int start = candles.size() - period;
        double gains = 0, losses = 0;
        for (int i = start; i < candles.size(); i++) {
            double change = candles.get(i).close - candles.get(i - 1).close;
            if (change > 0) gains += change;
            else losses -= change;
        }
        if (losses == 0) return 100;
        double rs = (gains / period) / (losses / period);
        return 100 - (100 / (1 + rs));
    }
}
