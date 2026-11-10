package com.potraderbot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Twelve Data adapter. The API key is supplied at runtime and is never stored in the repo. */
public final class MarketDataClient {
    private static final String ENDPOINT = "https://api.twelvedata.com/time_series";
    private static final long CANDLE_MILLIS = 60_000L;

    public List<Candle> fetchOneMinuteCandles(String symbol, String apiKey) throws Exception {
        String cleanSymbol = symbol == null ? "" : symbol.trim().toUpperCase(Locale.US);
        String cleanKey = apiKey == null ? "" : apiKey.trim();
        if (!cleanSymbol.matches("[A-Z]{3}/[A-Z]{3}") && !cleanSymbol.matches("[A-Z0-9._^:-]{1,20}")) {
            throw new IllegalArgumentException("Выберите поддерживаемый рыночный символ из списка.");
        }
        if (cleanKey.isEmpty()) {
            throw new IllegalArgumentException("Введите API-ключ Twelve Data.");
        }

        String query = "?symbol=" + java.net.URLEncoder.encode(cleanSymbol, "UTF-8")
                + "&interval=1min&outputsize=120&timezone=UTC&format=JSON&apikey="
                + java.net.URLEncoder.encode(cleanKey, "UTF-8");
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT + query).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("Accept", "application/json");

        try {
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = readAll(stream);
            JSONObject root = new JSONObject(body);
            if (code < 200 || code >= 300 || root.has("status") && "error".equalsIgnoreCase(root.optString("status"))) {
                String message = root.optString("message", "HTTP " + code);
                throw new IllegalStateException("Ошибка источника котировок: " + message);
            }

            JSONArray values = root.optJSONArray("values");
            if (values == null) {
                throw new IllegalStateException("Источник не вернул массив свечей. Проверьте пару и тариф API.");
            }

            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            List<Candle> candles = new ArrayList<>();
            long now = System.currentTimeMillis();

            // Twelve Data returns newest first. Keep only fully closed one-minute candles.
            for (int i = values.length() - 1; i >= 0; i--) {
                JSONObject item = values.getJSONObject(i);
                Date date;
                try {
                    date = format.parse(item.getString("datetime"));
                } catch (ParseException e) {
                    continue;
                }
                if (date == null || date.getTime() + CANDLE_MILLIS > now) continue;
                candles.add(new Candle(date.getTime(),
                        item.getDouble("open"), item.getDouble("high"),
                        item.getDouble("low"), item.getDouble("close")));
            }
            if (candles.size() < 30) {
                throw new IllegalStateException("Получено меньше 30 закрытых свечей. Попробуйте позже.");
            }
            return candles;
        } finally {
            connection.disconnect();
        }
    }

    private String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }
}
