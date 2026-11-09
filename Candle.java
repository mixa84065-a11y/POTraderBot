package com.potraderbot;

public class Candle {
    public final long time;
    public final double open, high, low, close;

    public Candle(long time, double open, double high, double low, double close) {
        this.time = time;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
    }
}
