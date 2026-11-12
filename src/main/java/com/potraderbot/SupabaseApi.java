package com.potraderbot;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class SupabaseApi {
    private SupabaseApi() {}

    private static Object request(String method, String path, JSONObject body, String accessToken) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BuildConfig.SUPABASE_URL + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY);
        c.setRequestProperty("Accept", "application/json");
        if (accessToken != null && !accessToken.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + accessToken);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (stream != null) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = stream.read(buffer)) != -1) bytes.write(buffer, 0, n);
            stream.close();
        }
        String raw = bytes.toString("UTF-8");
        c.disconnect();
        if (code < 200 || code >= 300) {
            JSONObject errorBody;
            try {
                Object parsed = raw.isEmpty() ? new JSONObject() : new JSONTokener(raw).nextValue();
                errorBody = parsed instanceof JSONObject ? (JSONObject) parsed : new JSONObject();
            } catch (Exception ignored) {
                errorBody = new JSONObject();
            }
            String message = errorBody.optString("msg", errorBody.optString("message", errorBody.optString("error_description", "Сервер вернул ошибку " + code)));
            throw new Exception(message);
        }
        return raw.isEmpty() ? new JSONObject() : new JSONTokener(raw).nextValue();
    }

    public static JSONObject signIn(String email, String password) throws Exception {
        JSONObject b = new JSONObject();
        b.put("email", email.trim());
        b.put("password", password);
        return (JSONObject) request("POST", "/auth/v1/token?grant_type=password", b, null);
    }

    public static JSONObject signUp(String email, String password) throws Exception {
        JSONObject b = new JSONObject();
        b.put("email", email.trim());
        b.put("password", password);
        return (JSONObject) request("POST", "/auth/v1/signup", b, null);
    }

    public static JSONObject refresh(String refreshToken) throws Exception {
        JSONObject b = new JSONObject();
        b.put("refresh_token", refreshToken);
        return (JSONObject) request("POST", "/auth/v1/token?grant_type=refresh_token", b, null);
    }

    public static JSONObject getUser(String token) throws Exception {
        return (JSONObject) request("GET", "/auth/v1/user", null, token);
    }

    public static JSONObject getWallet(String token) throws Exception {
        Object response = request("GET", "/rest/v1/demo_wallets?select=balance,currency&limit=1", null, token);
        if (response instanceof JSONArray) {
            JSONArray rows = (JSONArray) response;
            return rows.length() > 0 ? rows.getJSONObject(0) : new JSONObject();
        }
        if (response instanceof JSONObject) return (JSONObject) response;
        throw new Exception("Неожиданный формат ответа баланса");
    }

    public static JSONArray getTrades(String token) throws Exception {
        String path = "/rest/v1/demo_trades?select=id,symbol,direction,stake,expiry_seconds,entry_price,exit_price,status,pnl,opened_at,expires_at&order=opened_at.desc&limit=20";
        return (JSONArray) request("GET", path, null, token);
    }

    public static JSONObject getMarketSnapshot(String token, String symbol) throws Exception {
        String encoded = java.net.URLEncoder.encode(symbol, "UTF-8");
        return (JSONObject) request("GET", "/functions/v1/market-snapshot?symbol=" + encoded, null, token);
    }

    public static JSONObject getTwelveDataKeyStatus(String token) throws Exception {
        return (JSONObject) request("GET", "/functions/v1/twelvedata-key", null, token);
    }

    public static JSONObject saveTwelveDataKey(String token, String apiKey) throws Exception {
        JSONObject body = new JSONObject();
        body.put("api_key", apiKey);
        return (JSONObject) request("POST", "/functions/v1/twelvedata-key", body, token);
    }

    public static JSONObject deleteTwelveDataKey(String token) throws Exception {
        return (JSONObject) request("DELETE", "/functions/v1/twelvedata-key", null, token);
    }

    public static JSONObject openDemoTrade(String token, String symbol, String direction, double stake, int expirySeconds) throws Exception {
        JSONObject b = new JSONObject();
        b.put("action", "open");
        b.put("symbol", symbol);
        b.put("direction", direction);
        b.put("stake", stake);
        b.put("expiry_seconds", expirySeconds);
        return (JSONObject) request("POST", "/functions/v1/demo-trade", b, token);
    }
}
