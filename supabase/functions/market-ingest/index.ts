import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return new Response("Method not allowed", { status: 405 });
  const secret = Deno.env.get("MARKET_INGEST_SECRET");
  const supplied = req.headers.get("x-market-ingest-secret") ?? "";
  if (!secret || supplied.length !== secret.length || supplied !== secret) {
    return new Response(JSON.stringify({ error: "Ingestion is not configured or not authorized" }), {
      status: secret ? 401 : 503, headers: { "Content-Type": "application/json" }
    });
  }
  const url = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !serviceKey) return new Response(JSON.stringify({ error: "Server configuration incomplete" }), { status: 503 });
  let body: { quotes?: unknown };
  try { body = await req.json(); } catch { return new Response(JSON.stringify({ error: "Invalid JSON" }), { status: 400 }); }
  if (!Array.isArray(body.quotes) || body.quotes.length < 1 || body.quotes.length > 500) {
    return new Response(JSON.stringify({ error: "quotes must contain 1 to 500 records" }), { status: 422 });
  }
  const admin = createClient(url, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
  const { data, error } = await admin.rpc("market_ingest_quotes", { p_quotes: body.quotes });
  if (error) return new Response(JSON.stringify({ error: "Quote validation failed", detail: error.message }), { status: 422 });
  return new Response(JSON.stringify(data), { status: 200, headers: { "Content-Type": "application/json" } });
});
