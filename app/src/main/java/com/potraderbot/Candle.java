package com.potraderbot;

/** One completed OHLC candle. Prices must be positive and timestamps ascending. */
public final class Candle {
    public final long time;
    public final double open;
    public final double high;
    public final double low;
    public final double close;

    public Candle(long time, double open, double high, double low, double close) {
        if (time <= 0 || !positiveFinite(open) || !positiveFinite(high)
                || !positiveFinite(low) || !positiveFinite(close)) {
            throw new IllegalArgumentException("Invalid candle values");
        }
        if (high < Math.max(open, close) || low > Math.min(open, close) || high < low) {
            throw new IllegalArgumentException("Invalid OHLC range");
        }
        this.time = time;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
    }

    private static boolean positiveFinite(double value) {
        return value > 0 && !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
