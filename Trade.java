package com.potraderbot;

public class Trade {
    public final String direction;
    public final long entryTime;
    public final int expiryMinutes;
    public final double entryPrice;
    public final String result;

    public Trade(String direction, long entryTime, int expiryMinutes, double entryPrice, String result) {
        this.direction = direction;
        this.entryTime = entryTime;
        this.expiryMinutes = expiryMinutes;
        this.entryPrice = entryPrice;
        this.result = result;
    }
}
