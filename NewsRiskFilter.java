package com.potraderbot;

import java.util.List;
import java.util.Locale;

/**
 * Conservative event-risk gate for short-expiry Binarium analysis.
 * High-impact events block entries 30 minutes before and after.
 * Medium-impact events block entries 15 minutes before and after.
 */
public class NewsRiskFilter {
    public static class Decision {
        public final boolean blocked;
        public final String reason;
        public final EconomicEvent event;

        Decision(boolean blocked, String reason, EconomicEvent event) {
            this.blocked = blocked;
            this.reason = reason;
            this.event = event;
        }
    }

    public Decision evaluate(String pair, List<EconomicEvent> events, long nowUtcMillis) {
        if (events == null) {
            return new Decision(true, "Нет подтверждённых данных экономического календаря", null);
        }
        String normalizedPair = pair == null ? "" : pair.toUpperCase(Locale.US).replace(" ", "");
        for (EconomicEvent event : events) {
            if (event == null || !affectsPair(normalizedPair, event.currency)) continue;
            long window;
            if ("high".equals(event.importance)) {
                window = 30L * 60L * 1000L;
            } else if ("medium".equals(event.importance)) {
                window = 15L * 60L * 1000L;
            } else {
                continue;
            }
            long delta = Math.abs(nowUtcMillis - event.timeUtcMillis);
            if (delta <= window) {
                String reason = "Пауза из-за новости " + event.importance.toUpperCase(Locale.US)
                        + ": " + event.name + " (" + event.currency + ")";
                return new Decision(true, reason, event);
            }
        }
        return new Decision(false, "Нет значимых событий в защитном временном окне", null);
    }

    private boolean affectsPair(String pair, String currency) {
        if (currency == null || currency.trim().isEmpty()) return false;
        String code = currency.trim().toUpperCase(Locale.US);
        return pair.contains(code);
    }
}
