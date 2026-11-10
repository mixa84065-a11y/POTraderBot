package com.potraderbot;

public class EconomicEvent {
    public final long timeUtcMillis;
    public final String name;
    public final String currency;
    public final String country;
    public final String importance;
    public final String actual;
    public final String forecast;
    public final String previous;
    public final String sourceUrl;

    public EconomicEvent(long timeUtcMillis, String name, String currency, String country,
                         String importance, String actual, String forecast, String previous,
                         String sourceUrl) {
        this.timeUtcMillis = timeUtcMillis;
        this.name = name == null ? "" : name;
        this.currency = currency == null ? "" : currency;
        this.country = country == null ? "" : country;
        this.importance = importance == null ? "low" : importance.toLowerCase();
        this.actual = actual == null ? "" : actual;
        this.forecast = forecast == null ? "" : forecast;
        this.previous = previous == null ? "" : previous;
        this.sourceUrl = sourceUrl == null ? "" : sourceUrl;
    }
}
