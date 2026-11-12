import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import postgres from "npm:postgres@3.4.3";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
  "Content-Type": "application/json",
  "Cache-Control": "no-store",
};

const databaseUrl = Deno.env.get("SUPABASE_DB_URL") ?? "";
const db = databaseUrl
  ? postgres(databaseUrl, { max: 1, idle_timeout: 20, connect_timeout: 10, prepare: false })
  : null;

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "GET") return respond({ error: "Method not allowed" }, 405);

  const url = Deno.env.get("SUPABASE_URL");
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !anonKey || !serviceKey || !db) {
    return respond({ error: "Server configuration incomplete" }, 503);
  }

  const auth = req.headers.get("Authorization") ?? "";
  const token = auth.replace(/^Bearer\s+/i, "");
  if (!token || token === auth) return respond({ error: "Authentication required" }, 401);

  const userClient = createClient(url, anonKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: userData, error: userError } = await userClient.auth.getUser(token);
  if (userError || !userData.user) return respond({ error: "Invalid session" }, 401);

  const symbol = (new URL(req.url).searchParams.get("symbol") ?? "EUR/USD").trim().toUpperCase();
  if (!/^[A-Z0-9._/-]{3,20}$/.test(symbol)) return respond({ error: "Invalid symbol" }, 422);

  const uid = userData.user.id;
  const source = "Twelve Data:" + uid;
  const admin = createClient(url, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  try {
    // Decrypted key stays only in this server function's memory.
    const keyRows = await db.unsafe(
      "select ds.decrypted_secret as api_key, ps.history_attempted_at from private.user_market_provider_secrets s join vault.decrypted_secrets ds on ds.id=s.secret_id left join private.user_market_poll_state ps on ps.user_id=s.user_id and ps.symbol=$2 where s.user_id=$1::uuid and s.provider='twelvedata' limit 1",
      [uid, symbol],
    );
    const apiKey = keyRows[0]?.api_key as string | undefined;

    const [quoteBefore, candlesBefore, configResult] = await Promise.all([
      admin.from("market_quotes").select("symbol,price,source,observed_at,received_at")
        .eq("symbol", symbol).eq("source", source)
        .order("observed_at", { ascending: false }).limit(1).maybeSingle(),
      admin.from("market_candles")
        .select("symbol,interval_seconds,open_time,open,high,low,close,tick_count,source")
        .eq("symbol", symbol).eq("source", source).eq("interval_seconds", 60)
        .order("open_time", { ascending: false }).limit(120),
      admin.from("demo_market_config").select("enabled").eq("symbol", symbol).maybeSingle(),
    ]);
    if (quoteBefore.error || candlesBefore.error || configResult.error) {
      return respond({ error: "Market data temporarily unavailable" }, 503);
    }

    if (!apiKey) {
      await admin.rpc("demo_settle_due_trades", { p_limit: 200 });
      return respond({
        symbol, quote: quoteBefore.data ?? null,
        quote_fresh: false, candles: (candlesBefore.data ?? []).reverse(),
        demo_enabled: configResult.data?.enabled === true, key_required: true,
      });
    }

    const minRefreshSeconds = Math.max(10, Number(Deno.env.get("TWELVE_DATA_MIN_REFRESH_SECONDS") ?? "15"));
    const historyAttempt = keyRows[0]?.history_attempted_at
      ? Date.parse(keyRows[0].history_attempted_at) : 0;

    // Backfill M1 history once on first connection; retry no more than every six hours.
    if ((candlesBefore.data ?? []).length < 25
        && (!historyAttempt || Date.now() - historyAttempt >= 6 * 60 * 60 * 1000)) {
      const claimHistory = await db.unsafe(
        "insert into private.user_market_poll_state (user_id,symbol,history_attempted_at) values ($1::uuid,$2,now()) on conflict (user_id,symbol) do update set history_attempted_at=now(),updated_at=now() where private.user_market_poll_state.history_attempted_at is null or private.user_market_poll_state.history_attempted_at <= now()-interval '6 hours' returning user_id",
        [uid, symbol],
      );
      if (claimHistory.length > 0) {
        const historyUrl = new URL("https://api.twelvedata.com/time_series");
        historyUrl.searchParams.set("symbol", symbol);
        historyUrl.searchParams.set("interval", "1min");
        historyUrl.searchParams.set("outputsize", "120");
        historyUrl.searchParams.set("timezone", "UTC");
        historyUrl.searchParams.set("apikey", apiKey);
        try {
          const historyResponse = await fetch(historyUrl, { signal: AbortSignal.timeout(12000) });
          const history = await historyResponse.json();
          if (historyResponse.ok && Array.isArray(history.values)) {
            for (const item of history.values) {
              const openTime = parseProviderDate(item.datetime);
              const o = Number(item.open), h = Number(item.high), l = Number(item.low), c = Number(item.close);
              if (!openTime || ![o,h,l,c].every(Number.isFinite) || o <= 0 || h <= 0 || l <= 0 || c <= 0) continue;
              await db.unsafe(
                "insert into public.market_candles (symbol,interval_seconds,open_time,open,high,low,close,tick_count,source,updated_at) values ($1,60,$2,$3,$4,$5,$6,0,$7,now()) on conflict (symbol,interval_seconds,open_time,source) do update set open=excluded.open,high=excluded.high,low=excluded.low,close=excluded.close,updated_at=now()",
                [symbol, openTime, o, h, l, c, source],
              );
            }
          }
        } catch {
          // Keep existing feed data usable if historical backfill is temporarily unavailable.
        }
      }
    }

    // Atomic per-user throttle prevents parallel app refreshes from spending credits repeatedly.
    const claimQuote = await db.unsafe(
      "insert into private.user_market_poll_state (user_id,symbol,last_attempt_at) values ($1::uuid,$2,now()) on conflict (user_id,symbol) do update set last_attempt_at=now(),updated_at=now() where private.user_market_poll_state.last_attempt_at is null or private.user_market_poll_state.last_attempt_at <= now()-make_interval(secs=>$3) returning user_id",
      [uid, symbol, minRefreshSeconds],
    );

    if (claimQuote.length > 0) {
      const quoteUrl = new URL("https://api.twelvedata.com/price");
      quoteUrl.searchParams.set("symbol", symbol);
      quoteUrl.searchParams.set("apikey", apiKey);
      try {
        const providerResponse = await fetch(quoteUrl, { signal: AbortSignal.timeout(12000) });
        const payload = await providerResponse.json();
        const priceValue = payload.close ?? payload.price;
        const price = typeof priceValue === "number" ? priceValue : Number(String(priceValue ?? "").trim());
        // /price returns the latest available price and may omit a source timestamp.
        // Stamp successful retrieval time; /quote timestamp is only the minute-bar open time.
        const observedAt = parseQuoteTime(payload.timestamp, payload.datetime) ?? new Date();
        const observedMs = observedAt.getTime();
        if (providerResponse.ok && payload.status !== "error" && !payload.code
            && Number.isFinite(price) && price > 0 && Number.isFinite(observedMs)
            && observedMs <= Date.now() + 5000 && observedMs >= Date.now() - 10 * 60 * 1000) {
          const inserted = await db.unsafe(
            "insert into public.market_quotes (symbol,price,source,observed_at) values ($1,$2,$3,$4) on conflict (symbol,source,observed_at) do nothing returning id",
            [symbol, price, source, observedAt.toISOString()],
          );
          if (inserted.length > 0) {
            const minute = new Date(Math.floor(observedMs / 60000) * 60000).toISOString();
            await db.unsafe(
              "insert into public.market_candles (symbol,interval_seconds,open_time,open,high,low,close,tick_count,source,updated_at) values ($1,60,$2,$3,$3,$3,$3,1,$4,now()) on conflict (symbol,interval_seconds,open_time,source) do update set high=greatest(public.market_candles.high,excluded.high),low=least(public.market_candles.low,excluded.low),close=excluded.close,tick_count=public.market_candles.tick_count+1,updated_at=now()",
              [symbol, minute, price, source],
            );
          }
        }
      } catch {
        // Never expose provider URLs, API keys, or raw provider payloads in errors.
      }
    }

    const [quoteResult, candleResult] = await Promise.all([
      admin.from("market_quotes").select("symbol,price,source,observed_at,received_at")
        .eq("symbol", symbol).eq("source", source)
        .order("observed_at", { ascending: false }).limit(1).maybeSingle(),
      admin.from("market_candles")
        .select("symbol,interval_seconds,open_time,open,high,low,close,tick_count,source")
        .eq("symbol", symbol).eq("source", source).eq("interval_seconds", 60)
        .order("open_time", { ascending: false }).limit(120),
    ]);
    await admin.rpc("demo_settle_due_trades", { p_limit: 200 });
    if (quoteResult.error || candleResult.error) return respond({ error: "Market data temporarily unavailable" }, 503);

    const q = quoteResult.data;
    const age = q ? Date.now() - Date.parse(q.observed_at) : Number.POSITIVE_INFINITY;
    const fresh = !!q && age <= 15000 && age >= -5000;
    return respond({
      symbol, quote: q, quote_fresh: fresh,
      candles: (candleResult.data ?? []).reverse(),
      demo_enabled: configResult.data?.enabled === true, key_required: false,
    });
  } catch {
    return respond({ error: "Market data temporarily unavailable" }, 503);
  }
});

function parseQuoteTime(timestamp: unknown, datetime: unknown): Date | null {
  if (typeof timestamp === "number" || (typeof timestamp === "string" && /^\d+(?:\.\d+)?$/.test(timestamp.trim()))) {
    const numeric = Number(timestamp);
    if (Number.isFinite(numeric) && numeric > 0) {
      // Accept Unix seconds and Unix milliseconds.
      const millis = numeric > 1e12 ? numeric : numeric > 1e9 ? numeric * 1000 : Number.NaN;
      if (Number.isFinite(millis)) {
        const date = new Date(millis);
        if (Number.isFinite(date.getTime())) return date;
      }
    }
  }
  if (typeof datetime === "string" && datetime.trim()) {
    const normalized = datetime.trim().includes("T") ? datetime.trim() : datetime.trim().replace(" ", "T");
    const parsed = Date.parse(normalized.endsWith("Z") ? normalized : normalized + "Z");
    if (Number.isFinite(parsed)) return new Date(parsed);
  }
  return null;
}

function parseProviderDate(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const normalized = value.includes("T") ? value : value.replace(" ", "T");
  const parsed = Date.parse(normalized.endsWith("Z") ? normalized : normalized + "Z");
  return Number.isFinite(parsed) ? new Date(parsed).toISOString() : null;
}

function respond(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: cors });
}
