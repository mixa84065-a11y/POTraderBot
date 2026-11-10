package com.potraderbot;

import java.util.List;

/** Rejects incomplete, malformed or stale one-minute market data before analysis. */
public class CandleSeriesValidator {
    public String validate(List<Candle> candles, long nowUtcMillis) {
        if (candles == null || candles.size() < 30) {
            return "Недостаточно реальных свечей: требуется минимум 30";
        }
        long previousTime = -1L;
        for (Candle candle : candles) {
            if (candle == null || candle.time <= previousTime) {
                return "Нарушен порядок или время свечей";
            }
            if (!Double.isFinite(candle.open) || !Double.isFinite(candle.high)
                    || !Double.isFinite(candle.low) || !Double.isFinite(candle.close)
                    || candle.low <= 0 || candle.high < candle.low
                    || candle.high < Math.max(candle.open, candle.close)
                    || candle.low > Math.min(candle.open, candle.close)) {
                return "Обнаружена некорректная OHLC-свеча";
            }
            previousTime = candle.time;
        }
        long age = nowUtcMillis - candles.get(candles.size() - 1).time;
        if (age < -60000L || age > 180000L) {
            return "Котировки устарели или время источника не совпадает";
        }
        return null;
    }
}
