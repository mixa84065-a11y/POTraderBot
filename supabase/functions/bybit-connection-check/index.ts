import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const DEMO_BASE = "https://api-demo.bybit.com";
const RECV_WINDOW = "5000";

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json",
      "cache-control": "no-store",
      "access-control-allow-origin": "*",
      "access-control-allow-headers": "authorization, apikey, content-type, x-client-info",
      "access-control-allow-methods": "POST, OPTIONS",
    },
  });
}

async function hmacHex(secret: string, message: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" }, false, ["sign"],
  );
  const signature = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(message));
  return Array.from(new Uint8Array(signature), b => b.toString(16).padStart(2, "0")).join("");
}

function canonicalQuery(params: Record<string, string>): string {
  return Object.entries(params).sort(([a], [b]) => a.localeCompare(b))
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join("&");
}

async function privateGet(apiKey: string, apiSecret: string, path: string, params: Record<string, string>) {
  const timestamp = Date.now().toString();
  const query = canonicalQuery(params);
  const signature = await hmacHex(apiSecret, timestamp + apiKey + RECV_WINDOW + query);
  const response = await fetch(`${DEMO_BASE}${path}?${query}`, {
    method: "GET",
    headers: {
      "X-BAPI-API-KEY": apiKey,
      "X-BAPI-TIMESTAMP": timestamp,
      "X-BAPI-RECV-WINDOW": RECV_WINDOW,
      "X-BAPI-SIGN": signature,
      "X-BAPI-SIGN-TYPE": "1",
      "accept": "application/json",
    },
    signal: AbortSignal.timeout(12000),
  });
  const data = await response.json();
  if (!response.ok || data.retCode !== 0) {
    const code = Number(data.retCode ?? response.status);
    let hint = String(data.retMsg ?? "Ошибка ответа Bybit").slice(0, 160);
    if (code === 10003) hint = "Ключ не подходит для Demo Trading API. Создайте отдельный ключ именно в режиме Demo Trading.";
    else if (code === 10004) hint = "Подпись API не прошла проверку. Проверьте пару API Key / API Secret и не добавляйте пробелы.";
    else if (code === 10005) hint = "У ключа нет права чтения кошелька. Включите только необходимое разрешение Read-Only / Wallet.";
    else if (code === 10010) hint = "IP-адрес сервера не разрешён в ограничениях API-ключа. Уберите IP-фильтр либо добавьте разрешённый адрес сервера.";
    else if (code === 10002) hint = "Время запроса не совпало с временем Bybit. Повторите проверку позже.";
    throw new Error(`Bybit Demo API (${code}): ${hint}`);
  }
  return data;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return json({ ok: true });
  if (req.method !== "POST") return json({ ok: false, error: "POST required" }, 405);
  try {
    const authHeader = req.headers.get("authorization") ?? "";
    const bearer = authHeader.startsWith("Bearer ") ? authHeader.slice(7) : "";
    if (!bearer) return json({ ok: false, error: "Требуется активная сессия GOD OF TRADING." }, 401);

    const userResponse = await fetch(`${SUPABASE_URL}/auth/v1/user`, {
      headers: { apikey: SERVICE_ROLE_KEY, authorization: `Bearer ${bearer}` },
      signal: AbortSignal.timeout(10000),
    });
    if (!userResponse.ok) return json({ ok: false, error: "Сессия недействительна. Войдите в приложение заново." }, 401);
    const user = await userResponse.json();
    if (!user?.id) return json({ ok: false, error: "Не удалось подтвердить пользователя." }, 401);

    const credentialsResponse = await fetch(`${SUPABASE_URL}/rest/v1/rpc/bybit_demo_worker_credentials`, {
      method: "POST",
      headers: {
        apikey: SERVICE_ROLE_KEY,
        authorization: `Bearer ${SERVICE_ROLE_KEY}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({ p_user_id: user.id }),
      signal: AbortSignal.timeout(10000),
    });
    if (!credentialsResponse.ok) throw new Error("Не удалось получить сохранённые ключи из защищённого хранилища.");
    const rows = await credentialsResponse.json();
    const credentials = Array.isArray(rows) ? rows[0] : null;
    if (!credentials?.api_key || !credentials?.api_secret) {
      return json({ ok: false, connected: false, error: "Для этого аккаунта ключи Bybit Demo ещё не сохранены." }, 404);
    }

    const data = await privateGet(credentials.api_key, credentials.api_secret,
      "/v5/account/wallet-balance", { accountType: "UNIFIED", coin: "USDT" });
    const account = data.result?.list?.[0];
    const usdt = account?.coin?.find((item: { coin: string }) => item.coin === "USDT");
    if (!account || !usdt) throw new Error("Авторизация прошла, но Bybit не вернул USDT Unified Wallet. Проверьте, что Demo-аккаунт активирован.");
    const equity = Number(account.totalEquity ?? account.totalWalletBalance);
    const walletBalance = Number(usdt.walletBalance ?? 0);
    const locked = Number(usdt.locked ?? 0);
    const available = Math.max(0, walletBalance - locked);
    if (![equity, walletBalance, locked, available].every(Number.isFinite)) {
      throw new Error("Bybit вернул некорректные значения баланса.");
    }
    return json({
      ok: true,
      connected: true,
      mode: "BYBIT_DEMO",
      checked_at: new Date().toISOString(),
      account_type: "UNIFIED",
      equity_usdt: equity,
      wallet_balance_usdt: walletBalance,
      locked_usdt: locked,
      available_usdt: available,
      permissions_checked: ["signed wallet read"],
      trading_orders_sent: false,
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Неизвестная ошибка проверки Bybit.";
    return json({ ok: false, connected: false, error: message, trading_orders_sent: false }, 400);
  }
});
