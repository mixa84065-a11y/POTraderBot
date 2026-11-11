import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Content-Type": "application/json",
};

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return new Response(JSON.stringify({ error: "Method not allowed" }), { status: 405, headers: cors });

  const url = Deno.env.get("SUPABASE_URL");
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !anonKey || !serviceKey) {
    return new Response(JSON.stringify({ error: "Server configuration incomplete" }), { status: 503, headers: cors });
  }

  const authHeader = req.headers.get("Authorization") ?? "";
  const token = authHeader.replace(/^Bearer\s+/i, "");
  if (!token || token === authHeader) {
    return new Response(JSON.stringify({ error: "Authentication required" }), { status: 401, headers: cors });
  }

  const userClient = createClient(url, anonKey, { auth: { persistSession: false, autoRefreshToken: false }, global: { headers: { Authorization: `Bearer ${token}` } } });
  const { data: userData, error: userError } = await userClient.auth.getUser(token);
  if (userError || !userData.user) {
    return new Response(JSON.stringify({ error: "Invalid or expired session" }), { status: 401, headers: cors });
  }

  let body: Record<string, unknown>;
  try { body = await req.json(); } catch {
    return new Response(JSON.stringify({ error: "Invalid JSON" }), { status: 400, headers: cors });
  }
  if (body.action !== "open") {
    return new Response(JSON.stringify({ error: "Unsupported action" }), { status: 400, headers: cors });
  }

  const symbol = typeof body.symbol === "string" ? body.symbol.trim().toUpperCase() : "";
  const direction = body.direction;
  const stake = Number(body.stake);
  const expiry = Number(body.expiry_seconds);
  if (!symbol || !["UP", "DOWN"].includes(String(direction)) || !Number.isFinite(stake) || !Number.isInteger(expiry)) {
    return new Response(JSON.stringify({ error: "Invalid trade parameters" }), { status: 422, headers: cors });
  }

  const admin = createClient(url, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
  const { data, error } = await admin.rpc("demo_open_trade", {
    p_user_id: userData.user.id,
    p_symbol: symbol,
    p_direction: direction,
    p_stake: stake,
    p_expiry_seconds: expiry,
  });
  if (error) {
    const message = error.message.includes("Котировка") || error.message.includes("Инструмент") ||
      error.message.includes("Недостаточно") || error.message.includes("виртуального") ||
      error.message.includes("Некорректные")
      ? error.message : "Не удалось открыть демо-сделку";
    return new Response(JSON.stringify({ error: message }), { status: 409, headers: cors });
  }
  return new Response(JSON.stringify({ trade: data }), { status: 200, headers: cors });
});
