package com.potraderbot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Investing.com economic-calendar adapter via API.SH.
 * API.SH is an independent data intermediary, not an official Investing.com API.
 * Call from a worker thread, never from Android's main thread.
 */
public class InvestingCalendarClient {
    private static final String ENDPOINT =
            "https://api.api.sh/investing/v1/economic-calendar";
    private final String apiKey;

    public InvestingCalendarClient(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("API.SH key is required");
        }
        this.apiKey = apiKey.trim();
    }

    public List<EconomicEvent> fetchEvents(String from, String to,
                                           String countries, String importance) throws Exception {
        StringBuilder url = new StringBuilder(ENDPOINT)
                .append("?from=").append(enc(from))
                .append("&to=").append(enc(to))
                .append("&limit=200");
        if (countries != null && !countries.trim().isEmpty()) {
            url.append("&countries=").append(enc(countries));
        }
        if (importance != null && !importance.trim().isEmpty()) {
            url.append("&importance=").append(enc(importance));
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(url.toString()).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("x-api-key", apiKey);
        connection.setRequestProperty("Accept", "application/json");

        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = readAll(stream);
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Calendar API HTTP " + status + ": " + body);
            }

            JSONObject root = new JSONObject(body);
            if (root.has("success") && !root.optBoolean("success", true)) {
                throw new IllegalStateException(root.optString("error", "Calendar API error"));
            }

            JSONArray events = root.optJSONArray("events");
            List<EconomicEvent> result = new ArrayList<>();
            if (events == null) return result;

            for (int i = 0; i < events.length(); i++) {
                JSONObject item = events.optJSONObject(i);
                if (item == null) continue;
                long eventTime = parseUtc(item.optString("time", ""));
                if (eventTime <= 0) continue;
                result.add(new EconomicEvent(
                        eventTime,
                        item.optString("name", item.optString("short_name", "")),
                        item.optString("currency", ""),
                        item.optString("country", ""),
                        item.optString("importance", "low"),
                        scalar(item.opt("actual")),
                        scalar(item.opt("forecast")),
                        scalar(item.opt("previous")),
                        item.optString("url", "")
                ));
            }
            Collections.sort(result, Comparator.comparingLong(e -> e.timeUtcMillis));
            return result;
        } finally {
            connection.disconnect();
        }
    }

    private static String scalar(Object value) {
        return value == null || value == JSONObject.NULL ? "" : String.valueOf(value);
    }

    private static long parseUtc(String value) {
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date date = format.parse(value);
                if (date != null) return date.getTime();
            } catch (ParseException ignored) { }
        }
        return -1L;
    }

    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
        }
        return out.toString();
    }
}
