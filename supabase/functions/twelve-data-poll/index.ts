import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

/**
 * Server-side Twelve Data poller.
 * Configure TWELVE_DATA_API_KEY, TWELVE_DATA_INGEST_SECRET and optionally
 * TWELVE_DATA_SYMBOLS (comma-separated Twelve Data symbols).
 * Invoke from a trusted scheduler using POST + x-market-poll-secret.
 */
Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return new Response("Method not allowed", { status: 405 });

  const expected = Deno.env.get("TWELVE_DATA_INGEST_SECRET") ?? "";
  const supplied = req.headers.get("x-market-poll-secret") ?? "";
  if (!expected || supplied.length !== expected.length || supplied !== expected) {
    return json({ error: "Not authorized" }, expected ? 401 : 503);
  }

  const apiKey = Deno.env.get("TWELVE_DATA_API_KEY");
  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!apiKey || !supabaseUrl || !serviceKey) {
    return json({ error: "Server configuration incomplete" }, 503);
  }

  const symbols = (Deno.env.get("TWELVE_DATA_SYMBOLS") ?? "EUR/USD")
    .split(",").map((s) => s.trim()).filter(Boolean);
  if (symbols.length < 1 || symbols.length > 50) return json({ error: "Invalid symbol configuration" }, 500);

  const quotes: Array<{symbol:string;price:number;source:string;observed_at:string}> = [];
  const failures: Array<{symbol:string;error:string}> = [];

  // Sequential calls keep provider credit usage and concurrency predictable.
  for (const symbol of symbols) {
    const url = new URL("https://api.twelvedata.com/quote");
    url.searchParams.set("symbol", symbol);
    url.searchParams.set("apikey", apiKey);
    try {
      const response = await fetch(url, { headers: { "Accept": "application/json" } });
      const payload = await response.json();
      if (!response.ok || payload.status === "error" || payload.code) {
        failures.push({ symbol, error: String(payload.message ?? `Provider HTTP ${response.status}`) });
        continue;
      }
      const price = Number(payload.close ?? payload.price);
      const epoch = Number(payload.timestamp);
      if (!Number.isFinite(price) || price <= 0 || !Number.isFinite(epoch) || epoch <= 0) {
        failures.push({ symbol, error: "Provider response has no valid price/timestamp" });
        continue;
      }
      quotes.push({
        symbol: symbol.toUpperCase(),
        price,
        source: "Twelve Data",
        observed_at: new Date(epoch * 1000).toISOString(),
      });
    } catch {
      failures.push({ symbol, error: "Provider request failed" });
    }
  }

  if (quotes.length === 0) return json({ accepted: 0, failures }, 502);

  const admin = createClient(supabaseUrl, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: ingestion, error: ingestError } = await admin.rpc("market_ingest_quotes", { p_quotes: quotes });
  if (ingestError) return json({ error: "Quote ingestion failed", detail: ingestError.message, failures }, 502);

  const { data: settlement, error: settlementError } = await admin.rpc("demo_settle_due_trades", { p_limit: 200 });
  return json({
    provider: "Twelve Data",
    received: quotes.length,
    ingestion,
    settlement: settlementError ? null : settlement,
    settlement_pending: Boolean(settlementError),
    failures,
  }, settlementError ? 202 : 200);
});

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}
