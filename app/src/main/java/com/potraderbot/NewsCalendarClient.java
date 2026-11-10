package com.potraderbot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** Public economic-calendar adapter. Refresh no more often than the provider's 15-minute cache. */
public final class NewsCalendarClient {
    private static final String ENDPOINT = "https://showmytrades.com/api/public/calendar/v1";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 12_000;

    public List<EconomicEvent> fetchAll() throws Exception {
        // Download all supported currencies once; the risk engine filters by the selected asset.
        String query = "?impact=high,medium&days=2&format=json";
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT + query).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "GoD-of-Trading-Android");
        try {
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = readAll(stream);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("Экономический календарь: HTTP " + code);
            }
            JSONObject root = new JSONObject(body);
            JSONArray values = root.optJSONArray("events");
            if (values == null) {
                throw new IllegalStateException("Источник календаря не вернул список событий.");
            }
            List<EconomicEvent> events = new ArrayList<>();
            for (int i = 0; i < values.length(); i++) {
                JSONObject item = values.optJSONObject(i);
                if (item == null) continue;
                long timestamp = item.optLong("ts", 0L) * 1000L;
                if (timestamp <= 0L) continue;
                String title = item.optString("title", "").trim();
                if (title.isEmpty()) continue;
                events.add(new EconomicEvent(timestamp,
                        item.optString("currency", ""),
                        item.optString("impact", ""),
                        title,
                        item.optString("forecast", ""),
                        item.optString("previous", ""),
                        item.optString("actual", ""),
                        item.optString("status", ""),
                        item.optString("confidence", "")));
            }
            Collections.sort(events, (a, b) -> Long.compare(a.timestampMillis, b.timestampMillis));
            return events;
        } finally {
            connection.disconnect();
        }
    }

    private String currenciesForSymbol(String symbol) {
        String clean = symbol == null ? "" : symbol.trim().toUpperCase(Locale.US);
        TreeSet<String> codes = new TreeSet<>();
        if (clean.matches("[A-Z]{3}/[A-Z]{3}")) {
            String[] parts = clean.split("/");
            addSupported(codes, parts[0]);
            addSupported(codes, parts[1]);
        } else {
            // Most listed stocks, indices, metals and crypto instruments are USD-quoted.
            codes.add("USD");
        }
        StringBuilder result = new StringBuilder();
        for (String code : codes) {
            if (result.length() > 0) result.append(',');
            result.append(code);
        }
        return result.toString();
    }

    private void addSupported(TreeSet<String> codes, String code) {
        if ("USD".equals(code) || "EUR".equals(code) || "GBP".equals(code)
                || "JPY".equals(code) || "AUD".equals(code) || "NZD".equals(code)
                || "CAD".equals(code) || "CHF".equals(code) || "CNY".equals(code)) {
            codes.add(code);
        }
    }

    private String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }
}
