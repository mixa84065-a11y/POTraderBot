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
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-cache, no-store");
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
            String message = errorBody.optString("error", errorBody.optString("msg", errorBody.optString("message", errorBody.optString("error_description", "Сервер вернул ошибку " + code))));
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
        String path = "/rest/v1/demo_trades?select=id,symbol,direction,stake,expiry_seconds,entry_price,exit_price,status,pnl,opened_at,expires_at&order=opened_at.desc&limit=1000";
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

    public static JSONObject saveBybitDemoCredentials(String token, String apiKey, String apiSecret) throws Exception {
        JSONObject b = new JSONObject();
        b.put("p_api_key", apiKey);
        b.put("p_api_secret", apiSecret);
        return (JSONObject) request("POST", "/rest/v1/rpc/bybit_demo_save_credentials", b, token);
    }

    public static JSONObject checkBybitDemoConnection(String token) throws Exception {
        return (JSONObject) request("POST", "/functions/v1/bybit-connection-check", new JSONObject(), token);
    }

    public static JSONObject startBybitDemoRun(String token, JSONArray symbols) throws Exception {
        JSONObject b = new JSONObject();
        b.put("p_symbols", symbols);
        return (JSONObject) request("POST", "/rest/v1/rpc/bybit_demo_start_run", b, token);
    }

    public static JSONObject stopBybitDemoRun(String token, String runId) throws Exception {
        JSONObject b = new JSONObject();
        b.put("p_run_id", runId);
        return (JSONObject) request("POST", "/rest/v1/rpc/bybit_demo_stop_run", b, token);
    }

    public static JSONObject getBybitDemoReport(String token) throws Exception {
        return getBybitDemoReport(token, "");
    }

    public static JSONObject getBybitDemoReport(String token, String runId) throws Exception {
        JSONObject b = new JSONObject();
        b.put("p_run_id", runId == null || runId.isEmpty() ? JSONObject.NULL : java.util.UUID.fromString(runId).toString());
        return (JSONObject) request("POST", "/rest/v1/rpc/bybit_demo_get_report", b, token);
    }
    public static JSONObject startMultiMarketPaperRun(String token) throws Exception {
        JSONObject body = new JSONObject();
        JSONArray markets = new JSONArray();
        for (String market : new String[]{"spot","linear","inverse","option","stock","ETF","forex","commodity"}) markets.put(market);
        body.put("p_market_categories", markets);
        body.put("p_starting_balance_usdt", 200.0);
        return (JSONObject) request("POST", "/rest/v1/rpc/paper_start_run", body, token);
    }

    public static JSONObject stopMultiMarketPaperRun(String token, String runId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("p_run_id", runId);
        return (JSONObject) request("POST", "/rest/v1/rpc/paper_stop_run", body, token);
    }

    public static JSONObject getMultiMarketPaperReport(String token) throws Exception {
        return getMultiMarketPaperReport(token, "");
    }

    public static JSONObject getMultiMarketPaperReport(String token, String runId) throws Exception {
        String runFilter = runId == null || runId.isEmpty() ? "" : "&id=eq." + java.util.UUID.fromString(runId).toString();
        JSONArray runs = (JSONArray) request("GET",
            "/rest/v1/paper_runs?select=id,status,market_categories,starting_balance_usdt,cash_usdt,realized_pnl_usdt,max_drawdown_pct,max_daily_loss_usdt,last_cycle_at,last_error,started_at,stopped_at&order=created_at.desc,id.desc&limit=1" + runFilter,
            null, token);
        JSONObject report = new JSONObject();
        if (runs.length() == 0) { report.put("has_run", false); return report; }
        JSONObject run = runs.getJSONObject(0);
        String id = run.getString("id");
        JSONArray positions = (JSONArray) request("GET",
            "/rest/v1/paper_positions?select=market_category,symbol,side,qty,entry_price,mark_price,unrealized_pnl_usdt,notional_usdt,stop_price,target_price,signal_score,opened_at&run_id=eq."
                + java.net.URLEncoder.encode(id, "UTF-8") + "&status=eq.OPEN&order=opened_at.desc&limit=30", null, token);
        String tradePath = "/rest/v1/paper_trades?select=id,market_category,symbol,side,entry_price,exit_price,gross_pnl_usdt,fees_usdt,net_pnl_usdt,exit_reason,closed_at&run_id=eq." + java.net.URLEncoder.encode(id, "UTF-8");
        // Keyset pagination avoids silently capping totals at the first 1000 rows.
        PaperReportStats stats = new PaperReportStats();
        String cursor = "";
        while (true) {
            JSONArray page = (JSONArray) request("GET", tradePath + "&order=id.asc&limit=200"
                    + (cursor.isEmpty() ? "" : "&id=gt." + cursor), null, token);
            if (page.length()==0) break;
            for (int i=0;i<page.length();i++) stats.add(page.getJSONObject(i));
            String next = java.util.UUID.fromString(page.getJSONObject(page.length()-1).getString("id")).toString();
            if (next.equals(cursor)) throw new Exception("Сервер повторил страницу сделок");
            cursor=next;
        }
        JSONArray trades = (JSONArray) request("GET", tradePath + "&order=closed_at.desc,id.desc&limit=20", null, token);
        JSONObject summary=stats.toJson();
        java.math.BigDecimal unrealized=java.math.BigDecimal.ZERO;
        for(int i=0;i<positions.length();i++)unrealized=unrealized.add(new java.math.BigDecimal(positions.getJSONObject(i).getString("unrealized_pnl_usdt")));
        summary.put("open_count",positions.length());
        summary.put("unrealized_pnl_usdt",unrealized.toPlainString());
        // During a server update multiple REST requests may observe different commits.
        // Do not present a mixed snapshot as confirmed statistics.
        java.math.BigDecimal ledger = new java.math.BigDecimal(summary.getString("net_pnl_usdt"));
        java.math.BigDecimal recorded = new java.math.BigDecimal(run.getString("realized_pnl_usdt"));
        if (ledger.subtract(recorded).abs().compareTo(new java.math.BigDecimal("0.000001")) > 0)
            throw new Exception("Сервер обновляет сделки или итог P/L расходится с журналом. Повторите обновление.");
        JSONArray events = (JSONArray) request("GET",
            "/rest/v1/paper_events?select=event_type,message,created_at&run_id=eq."
                + java.net.URLEncoder.encode(id, "UTF-8") + "&order=created_at.desc&limit=8", null, token);
        report.put("has_run", true); report.put("run", run); report.put("positions", positions);
        report.put("trades", trades); report.put("events", events); report.put("summary", summary);
        return report;
    }

}
