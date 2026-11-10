package com.potraderbot;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Minimal Supabase Auth REST client. Uses only the public anon key; never embeds service_role. */
public final class SupabaseAuthClient {
    private final String baseUrl;
    private final String anonKey;

    public SupabaseAuthClient(String baseUrl, String anonKey) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.anonKey = anonKey == null ? "" : anonKey.trim();
    }

    public boolean isConfigured() {
        return baseUrl.startsWith("https://") && !anonKey.isEmpty();
    }

    public JSONObject signUp(String email, String password) throws Exception {
        JSONObject body = new JSONObject();
        body.put("email", email);
        body.put("password", password);
        return request("/auth/v1/signup", body, null);
    }

    public JSONObject signIn(String email, String password) throws Exception {
        JSONObject body = new JSONObject();
        body.put("email", email);
        body.put("password", password);
        return request("/auth/v1/token?grant_type=password", body, null);
    }

    public void sendPasswordReset(String email, String redirectTo) throws Exception {
        JSONObject body = new JSONObject();
        body.put("email", email);
        request("/auth/v1/recover?redirect_to=" + java.net.URLEncoder.encode(redirectTo, "UTF-8"),
                body, null);
    }


    public void updatePassword(String accessToken, String newPassword) throws Exception {
        if (!isConfigured()) throw new IllegalStateException("Supabase не настроен");
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + "/auth/v1/user").openConnection();
        try {
            c.setRequestMethod("PUT");
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("apikey", anonKey);
            c.setRequestProperty("Authorization", "Bearer " + accessToken);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            JSONObject body = new JSONObject();
            body.put("password", newPassword);
            c.setDoOutput(true);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = c.getResponseCode();
            InputStream in = status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream();
            String response = readAll(in);
            if (status < 200 || status >= 300)
                throw new IllegalStateException("Не удалось обновить пароль: " + status + " " + response);
        } finally { c.disconnect(); }
    }

    public void signOut(String accessToken) throws Exception {
        request("/auth/v1/logout", null, accessToken);
    }

    public JSONObject exchangeCode(String code, String codeVerifier) throws Exception {
        JSONObject body = new JSONObject();
        body.put("auth_code", code);
        body.put("code_verifier", codeVerifier);
        return request("/auth/v1/token?grant_type=pkce", body, null);
    }

    public String googleAuthUrl(String redirectTo, String codeChallenge) throws Exception {
        if (!isConfigured()) throw new IllegalStateException("Supabase не настроен");
        return baseUrl + "/auth/v1/authorize?provider=google&redirect_to="
                + java.net.URLEncoder.encode(redirectTo, "UTF-8")
                + "&code_challenge=" + java.net.URLEncoder.encode(codeChallenge, "UTF-8")
                + "&code_challenge_method=s256";
    }

    private JSONObject request(String path, JSONObject body, String bearer) throws Exception {
        if (!isConfigured()) throw new IllegalStateException(
                "Не заданы SUPABASE_URL и SUPABASE_ANON_KEY в настройках сборки");
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        try {
            c.setRequestMethod(body == null ? "POST" : "POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("apikey", anonKey);
            c.setRequestProperty("Accept", "application/json");
            if (bearer != null && !bearer.isEmpty())
                c.setRequestProperty("Authorization", "Bearer " + bearer);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream();
            String response = readAll(in);
            if (status < 200 || status >= 300) {
                String message = "Ошибка Supabase (" + status + ")";
                try {
                    JSONObject error = new JSONObject(response);
                    message = error.optString("msg",
                            error.optString("message", error.optString("error_description",
                                    error.optString("error", message))));
                } catch (Exception ignored) { }
                throw new IllegalStateException(message);
            }
            return response.trim().isEmpty() ? new JSONObject() : new JSONObject(response);
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
