package com.potraderbot;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Conservative event-window filter. It never predicts the direction of a news reaction. */
public final class NewsRiskEngine {
    public static final long HIGH_BEFORE_MS = 30L * 60L * 1000L;
    public static final long HIGH_AFTER_MS = 30L * 60L * 1000L;
    public static final long MEDIUM_BEFORE_MS = 15L * 60L * 1000L;
    public static final long MEDIUM_AFTER_MS = 10L * 60L * 1000L;

    public static final class Decision {
        public final boolean blocked;
        public final boolean warning;
        public final EconomicEvent event;
        public final String message;

        private Decision(boolean blocked, boolean warning, EconomicEvent event, String message) {
            this.blocked = blocked;
            this.warning = warning;
            this.event = event;
            this.message = message;
        }
    }

    public Decision evaluate(List<EconomicEvent> events, String symbol, long nowMillis) {
        String[] currencies = relevantCurrencies(symbol);
        EconomicEvent nearestHigh = null;
        long nearestHighDistance = Long.MAX_VALUE;
        EconomicEvent nearestMedium = null;
        long nearestMediumDistance = Long.MAX_VALUE;

        if (events == null) events = java.util.Collections.emptyList();
        for (EconomicEvent event : events) {
            if (event == null || !isRelevant(event.currency, currencies)) continue;
            long delta = event.timestampMillis - nowMillis;
            long distance = Math.abs(delta);
            if ("high".equals(event.impact)) {
                if (delta >= -HIGH_AFTER_MS && delta <= HIGH_BEFORE_MS && distance < nearestHighDistance) {
                    nearestHigh = event;
                    nearestHighDistance = distance;
                }
            } else if ("medium".equals(event.impact)) {
                if (delta >= -MEDIUM_AFTER_MS && delta <= MEDIUM_BEFORE_MS && distance < nearestMediumDistance) {
                    nearestMedium = event;
                    nearestMediumDistance = distance;
                }
            }
        }

        if (nearestHigh != null) {
            return new Decision(true, true, nearestHigh,
                    "НЕ ВХОДИТЬ: важная новость " + nearestHigh.currency + " — "
                            + nearestHigh.title + " (" + timeUtc(nearestHigh.timestampMillis)
                            + " UTC). Фильтр блокирует новые сигналы за 30 минут до и после события.");
        }
        if (nearestMedium != null) {
            return new Decision(false, true, nearestMedium,
                    "ОСТОРОЖНО: среднее влияние — " + nearestMedium.currency + " / "
                            + nearestMedium.title + " (" + timeUtc(nearestMedium.timestampMillis)
                            + " UTC). Подтвердите движение и учитывайте повышенную волатильность.");
        }
        return new Decision(false, false, null,
                "В выбранном окне нет событий высокого или среднего влияния по валютам этого инструмента.");
    }

    private String[] relevantCurrencies(String symbol) {
        String clean = symbol == null ? "" : symbol.trim().toUpperCase(Locale.US);
        if (clean.matches("[A-Z]{3}/[A-Z]{3}")) {
            String[] parts = clean.split("/");
            // The calendar publishes CNY events; CNH is the offshore renminbi quote.
            for (int i = 0; i < parts.length; i++) {
                if ("CNH".equals(parts[i])) parts[i] = "CNY";
            }
            return parts;
        }
        return new String[]{"USD"};
    }

    private boolean isRelevant(String currency, String[] currencies) {
        for (String code : currencies) if (code.equalsIgnoreCase(currency)) return true;
        return false;
    }

    private String timeUtc(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("HH:mm dd.MM", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }
}
