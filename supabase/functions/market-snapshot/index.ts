import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
  "Content-Type": "application/json",
};

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "GET") return new Response(JSON.stringify({ error: "Method not allowed" }), { status: 405, headers: cors });
  const url = Deno.env.get("SUPABASE_URL");
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !anonKey || !serviceKey) return new Response(JSON.stringify({ error: "Server configuration incomplete" }), { status: 503, headers: cors });
  const auth = req.headers.get("Authorization") ?? "";
  const token = auth.replace(/^Bearer\s+/i, "");
  if (!token || token === auth) return new Response(JSON.stringify({ error: "Authentication required" }), { status: 401, headers: cors });
  const userClient = createClient(url, anonKey, { auth: { persistSession: false, autoRefreshToken: false } });
  const { data: u, error: ue } = await userClient.auth.getUser(token);
  if (ue || !u.user) return new Response(JSON.stringify({ error: "Invalid session" }), { status: 401, headers: cors });
  const symbol = (new URL(req.url).searchParams.get("symbol") ?? "EUR/USD").toUpperCase();
  if (!/^[A-Z0-9._/-]{3,20}$/.test(symbol)) return new Response(JSON.stringify({ error: "Invalid symbol" }), { status: 422, headers: cors });
  const admin = createClient(url, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
  const [quoteResult, candleResult, configResult] = await Promise.all([
    admin.from("market_quotes").select("symbol,price,source,observed_at,received_at").eq("symbol", symbol).order("observed_at", { ascending: false }).limit(1).maybeSingle(),
    admin.from("market_candles").select("symbol,interval_seconds,open_time,open,high,low,close,tick_count,source").eq("symbol", symbol).eq("interval_seconds", 60).order("open_time", { ascending: false }).limit(120),
    admin.from("demo_market_config").select("enabled").eq("symbol", symbol).maybeSingle()
  ]);
  if (quoteResult.error || candleResult.error || configResult.error) return new Response(JSON.stringify({ error: "Market data temporarily unavailable" }), { status: 503, headers: cors });
  const q = quoteResult.data;
  const fresh = !!q && (Date.now() - Date.parse(q.observed_at) <= 15000) && (Date.now() - Date.parse(q.observed_at) >= -5000);
  return new Response(JSON.stringify({
    symbol, quote: q, quote_fresh: fresh,
    candles: (candleResult.data ?? []).reverse(),
    demo_enabled: configResult.data?.enabled === true
  }), { status: 200, headers: cors });
});
