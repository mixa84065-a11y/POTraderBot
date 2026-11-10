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

    public String surpriseText() {
        if (actual.isEmpty() || forecast.isEmpty()) return "";
        Double actualValue = comparableNumber(actual);
        Double forecastValue = comparableNumber(forecast);
        if (actualValue == null || forecastValue == null) return "Факт опубликован; сравнение с прогнозом недоступно";
        int comparison = Double.compare(actualValue, forecastValue);
        if (comparison > 0) return "Факт выше прогноза";
        if (comparison < 0) return "Факт ниже прогноза";
        return "Факт совпал с прогнозом";
    }

    private Double comparableNumber(String raw) {
        String value = raw.trim().replace(",", "").replace("%", "")
                .replace("$", "").replace("€", "").replace("£", "").replace("¥", "");
        double multiplier = 1.0;
        if (value.endsWith("K") || value.endsWith("k")) {
            multiplier = 1_000.0;
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("M") || value.endsWith("m")) {
            multiplier = 1_000_000.0;
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("B") || value.endsWith("b")) {
            multiplier = 1_000_000_000.0;
            value = value.substring(0, value.length() - 1);
        }
        try {
            return Double.parseDouble(value.trim()) * multiplier;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
