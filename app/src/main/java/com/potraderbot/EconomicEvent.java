package com.potraderbot;

/** One scheduled macroeconomic event returned by the public calendar feed. */
public final class EconomicEvent {
    public final long timestampMillis;
    public final String currency;
    public final String impact;
    public final String title;
    public final String forecast;
    public final String previous;
    public final String actual;
    public final String status;
    public final String confidence;

    public EconomicEvent(long timestampMillis, String currency, String impact, String title,
                         String forecast, String previous, String actual,
                         String status, String confidence) {
        this.timestampMillis = timestampMillis;
        this.currency = safe(currency);
        this.impact = safe(impact).toLowerCase(java.util.Locale.US);
        this.title = safe(title);
        this.forecast = safe(forecast);
        this.previous = safe(previous);
        this.actual = safe(actual);
        this.status = safe(status).toLowerCase(java.util.Locale.US);
        this.confidence = safe(confidence).toLowerCase(java.util.Locale.US);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
