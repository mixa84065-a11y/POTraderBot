import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const BYBIT_DEMO_BASE = "https://api-demo.bybit.com";
const RECV_WINDOW = "5000";
const ALLOWED_SYMBOLS = new Set(["BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT", "BNBUSDT", "DOGEUSDT"]);

type ActiveRun = {
  run_id: string;
  user_id: string;
  symbols: string[];
  started_at: string;
  ends_at: string;
  status: "RUNNING" | "STOPPING";
  api_key: string;
  api_secret: string;
};

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store" },
  });
}

async function rpc<T>(name: string, args: Record<string, unknown> = {}): Promise<T> {
  const response = await fetch(`${SUPABASE_URL}/rest/v1/rpc/${name}`, {
    method: "POST",
    headers: {
      apikey: SERVICE_ROLE_KEY,
      authorization: `Bearer ${SERVICE_ROLE_KEY}`,
      "content-type": "application/json",
    },
    body: JSON.stringify(args),
  });
  const body = await response.text();
  if (!response.ok) throw new Error(`RPC ${name} failed (${response.status}): ${body.slice(0, 400)}`);
  return body ? JSON.parse(body) as T : (null as T);
}

async function hmacHex(secret: string, message: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"],
  );
  const signature = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(message));
  return Array.from(new Uint8Array(signature), (b) => b.toString(16).padStart(2, "0")).join("");
}

async function signedGet(apiKey: string, apiSecret: string, path: string, params: Record<string, string>) {
  const query = Object.keys(params).sort().map((key) =>
    `${encodeURIComponent(key)}=${encodeURIComponent(params[key])}`
  ).join("&");
  const timestamp = Date.now().toString();
  const signature = await hmacHex(apiSecret, timestamp + apiKey + RECV_WINDOW + query);
  const response = await fetch(`${BYBIT_DEMO_BASE}${path}?${query}`, {
    headers: {
      "X-BAPI-API-KEY": apiKey,
      "X-BAPI-TIMESTAMP": timestamp,
      "X-BAPI-RECV-WINDOW": RECV_WINDOW,
      "X-BAPI-SIGN": signature,
      "X-BAPI-SIGN-TYPE": "1",
    },
    signal: AbortSignal.timeout(12_000),
  });
  const data = await response.json();
  if (!response.ok || data.retCode !== 0) {
    throw new Error(`Bybit Demo API error ${data.retCode ?? response.status}: ${String(data.retMsg ?? "HTTP error").slice(0, 180)}`);
  }
  return data;
}

async function publicKlines(symbol: string) {
  const url = new URL(`${BYBIT_DEMO_BASE}/v5/market/kline`);
  url.search = new URLSearchParams({ category: "spot", symbol, interval: "1", limit: "80" }).toString();
  const response = await fetch(url, { signal: AbortSignal.timeout(10_000) });
  const data = await response.json();
  if (!response.ok || data.retCode !== 0) throw new Error(`Market data ${symbol}: ${data.retMsg ?? response.status}`);
  return (data.result?.list ?? []).map((row: string[]) => ({
    time: Number(row[0]), open: Number(row[1]), high: Number(row[2]),
    low: Number(row[3]), close: Number(row[4]), volume: Number(row[5]),
  })).reverse();
}

function ema(values: number[], period: number): number {
  if (values.length < period) return NaN;
  const alpha = 2 / (period + 1);
  let value = values.slice(0, period).reduce((a, b) => a + b, 0) / period;
  for (const item of values.slice(period)) value = item * alpha + value * (1 - alpha);
  return value;
}

function rsi(values: number[], period = 14): number {
  if (values.length <= period) return NaN;
  let gains = 0, losses = 0;
  for (let i = values.length - period; i < values.length; i++) {
    const delta = values[i] - values[i - 1];
    if (delta > 0) gains += delta;
    else losses -= delta;
  }
  if (losses === 0) return 100;
  return 100 - 100 / (1 + gains / losses);
}

function analyze(candles: { close: number; time: number }[]) {
  const closes = candles.map((c) => c.close);
  const fast = ema(closes, 9), slow = ema(closes, 21), strength = rsi(closes);
  const momentum = closes.length >= 6 ? (closes.at(-1)! / closes.at(-6)! - 1) * 100 : 0;
  const score = (fast > slow ? 1 : -1) + (strength >= 52 ? 1 : strength <= 48 ? -1 : 0) + (momentum > 0.04 ? 1 : momentum < -0.04 ? -1 : 0);
  return {
    direction: score >= 2 ? "UP" : score <= -2 ? "DOWN" : "NEUTRAL",
    score, ema9: Number.isFinite(fast) ? fast : null, ema21: Number.isFinite(slow) ? slow : null,
    rsi14: Number.isFinite(strength) ? strength : null, momentum_pct: momentum,
    candle_time: candles.at(-1)?.time ?? null,
  };
}

async function log(runId: string, level: string, eventType: string, message: string, details: unknown = {}) {
  await rpc("bybit_demo_worker_log", {
    p_run_id: runId, p_level: level, p_event_type: eventType, p_message: message, p_details: details,
  });
}

async function processRun(run: ActiveRun) {
  const lease = crypto.randomUUID();
  const claimed = await rpc<boolean>("bybit_demo_worker_claim_run", {
    p_run_id: run.run_id, p_lease_token: lease,
  });
  if (!claimed) return { run_id: run.run_id, status: "SKIPPED_LOCKED" };

  try {
    if (run.status === "STOPPING") {
      await log(run.run_id, "WARN", "STOP_REQUEST_PENDING", "Остановка ожидает безопасного закрытия позиций. Новые заявки не отправляются.");
      return { run_id: run.run_id, status: "STOPPING_REQUIRES_FLATTEN" };
    }
    const symbols = (run.symbols ?? []).filter((symbol) => ALLOWED_SYMBOLS.has(symbol)).slice(0, 3);
    if (symbols.length === 0 || symbols.length !== (run.symbols ?? []).length) {
      throw new Error("В запуске обнаружен пустой или неподдерживаемый список инструментов.");
    }

    const wallet = await signedGet(run.api_key, run.api_secret, "/v5/account/wallet-balance", {
      accountType: "UNIFIED", coin: "USDT",
    });
    const account = wallet.result?.list?.[0];
    if (!account) throw new Error("Bybit Demo API не вернул Unified wallet.");
    const equity = Number(account.totalEquity ?? account.totalWalletBalance);
    if (!Number.isFinite(equity) || equity < 0) throw new Error("Некорректный баланс Bybit Demo API.");

    const results = [];
    for (const symbol of symbols) {
      const candles = await publicKlines(symbol);
      if (candles.length < 30) throw new Error(`Недостаточно свечей для ${symbol}.`);
      const signal = analyze(candles);
      results.push({ symbol, ...signal });
    }

    await rpc("bybit_demo_worker_heartbeat_v2", {
      p_run_id: run.run_id, p_equity: equity, p_realized_pnl: null,
      p_fees: null, p_error: null, p_status: null,
    });
    await log(run.run_id, "INFO", "DEMO_PREFLIGHT_OK",
      "Соединение с Bybit Demo API подтверждено. Анализ работает в безопасном режиме без выставления заявок.",
      { equity_usdt: equity, signals: results, execution_enabled: false },
    );
    return { run_id: run.run_id, status: "PREFLIGHT_OK_NO_ORDERS", equity_usdt: equity, signals: results };
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unknown worker error";
    await log(run.run_id, "ERROR", "WORKER_ERROR", message);
    await rpc("bybit_demo_worker_heartbeat_v2", {
      p_run_id: run.run_id, p_equity: null, p_realized_pnl: null,
      p_fees: null, p_error: message, p_status: null,
    });
    return { run_id: run.run_id, status: "ERROR", error: message };
  } finally {
    await rpc("bybit_demo_worker_release_run", { p_run_id: run.run_id, p_lease_token: lease });
  }
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "POST required" }, 405);
  const suppliedSecret = req.headers.get("x-worker-secret");
  if (!suppliedSecret) return json({ error: "Unauthorized" }, 401);
  try {
    const authorized = await rpc<boolean>("bybit_demo_worker_authorize", { p_secret: suppliedSecret });
    if (!authorized) return json({ error: "Unauthorized" }, 401);
    const runs = await rpc<ActiveRun[]>("bybit_demo_worker_active_runs");
    const results = [];
    for (const run of runs ?? []) results.push(await processRun(run));
    return json({ ok: true, processed: results.length, results });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Worker failed";
    return json({ error: message }, 500);
  }
});
