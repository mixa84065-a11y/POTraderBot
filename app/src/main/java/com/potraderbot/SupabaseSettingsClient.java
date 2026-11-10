package com.potraderbot;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class SupabaseSettingsClient {
    private final String baseUrl;
    private final String anonKey;

    public SupabaseSettingsClient(String url, String key) {
        baseUrl = url == null ? "" : url.trim().replaceAll("/+$", "");
        anonKey = key == null ? "" : key.trim();
    }

    public TradingSettings load(String userId, String accessToken) throws Exception {
        String path = "/rest/v1/trading_settings?select=selected_pairs,expiry_minutes,sharing_enabled&user_id=eq."
                + URLEncoder.encode(userId, "UTF-8");
        HttpURLConnection c = open(path, "GET", accessToken);
        try {
            int code = c.getResponseCode();
            String body = read(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
            if (code < 200 || code >= 300) throw new IllegalStateException("Supabase settings HTTP " + code + ": " + body);
            JSONArray rows = new JSONArray(body);
            if (rows.length() == 0) return TradingSettings.defaults();
            JSONObject row = rows.getJSONObject(0);
            JSONArray arr = row.optJSONArray("selected_pairs");
            List<String> pairs = new ArrayList<>();
            if (arr != null) for (int i=0;i<arr.length();i++) pairs.add(arr.optString(i));
            if (pairs.isEmpty()) pairs.add("EUR/USD");
            int expiry = row.optInt("expiry_minutes", 3);
            if (expiry != 3 && expiry != 5) expiry = 3;
            return new TradingSettings(pairs, expiry, row.optBoolean("sharing_enabled", false));
        } finally { c.disconnect(); }
    }

    public void save(String userId, String accessToken, TradingSettings settings) throws Exception {
        JSONObject row = new JSONObject();
        row.put("user_id", userId);
        row.put("selected_pairs", new JSONArray(settings.selectedPairs));
        row.put("expiry_minutes", settings.expiryMinutes);
        row.put("sharing_enabled", settings.sharingEnabled);
        HttpURLConnection c = open("/rest/v1/trading_settings?on_conflict=user_id", "POST", accessToken);
        try {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal");
            byte[] bytes = row.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            int code = c.getResponseCode();
            String body = read(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
            if (code < 200 || code >= 300) throw new IllegalStateException("Supabase settings HTTP " + code + ": " + body);
        } finally { c.disconnect(); }
    }

    private HttpURLConnection open(String path, String method, String token) throws Exception {
        if (!baseUrl.startsWith("https://") || anonKey.isEmpty() || token == null || token.isEmpty())
            throw new IllegalStateException("Для синхронизации настроек нужен активный аккаунт Supabase");
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("apikey", anonKey);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream input=in; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[2048]; int n;
            while ((n=input.read(b))!=-1) out.write(b,0,n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
