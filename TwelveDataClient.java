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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * One-minute forex OHLC adapter.
 * Twelve Data timestamps for forex are UTC. Call from a worker thread.
 */
public class TwelveDataClient {
    private static final String ENDPOINT = "https://api.twelvedata.com/time_series";
    private final String apiKey;

    public TwelveDataClient(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Twelve Data API key is required");
        }
        this.apiKey = apiKey.trim();
    }

    public List<Candle> fetchOneMinuteCandles(String symbol, int outputSize) throws Exception {
        int size = Math.max(30, Math.min(outputSize, 500));
        String query = "?symbol=" + URLEncoder.encode(symbol, "UTF-8")
                + "&interval=1min&outputsize=" + size;
        URL url = new URL(ENDPOINT + query);

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Authorization", "apikey " + apiKey);
        connection.setRequestProperty("Accept", "application/json");

        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = readAll(stream);
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Market data HTTP " + status + ": " + body);
            }

            JSONObject root = new JSONObject(body);
            if (!"ok".equalsIgnoreCase(root.optString("status", ""))) {
                throw new IllegalStateException(root.optString("message",
                        root.optString("code", "Market data unavailable")));
            }

            JSONArray values = root.optJSONArray("values");
            if (values == null) throw new IllegalStateException("No candle values returned");

            List<Candle> candles = new ArrayList<>();
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            for (int i = values.length() - 1; i >= 0; i--) {
                JSONObject item = values.optJSONObject(i);
                if (item == null) continue;
                Date date = format.parse(item.optString("datetime", ""));
                if (date == null) continue;
                double open = Double.parseDouble(item.getString("open"));
                double high = Double.parseDouble(item.getString("high"));
                double low = Double.parseDouble(item.getString("low"));
                double close = Double.parseDouble(item.getString("close"));
                candles.add(new Candle(date.getTime(), open, high, low, close));
            }
            return candles;
        } finally {
            connection.disconnect();
        }
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
