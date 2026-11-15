const DEMO_BASE = "https://api-demo.bybit.com";
const RECV_WINDOW = "5000";

export type BybitResult<T> = { retCode: number; retMsg: string; result: T; time?: number };

async function hmacHex(secret: string, message: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(message));
  return Array.from(new Uint8Array(signature), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function canonicalQuery(params: Record<string, string | number>): string {
  return Object.entries(params)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    .join("&");
}

async function parseResponse<T>(response: Response): Promise<BybitResult<T>> {
  const raw = await response.text();
  let data: BybitResult<T>;
  try {
    data = JSON.parse(raw) as BybitResult<T>;
  } catch {
    throw new Error(`Bybit Demo API вернул некорректный JSON (HTTP ${response.status}).`);
  }
  if (!response.ok || data.retCode !== 0) {
    throw new Error(`Bybit Demo API error ${data.retCode ?? response.status}: ${String(data.retMsg ?? "HTTP error").slice(0, 180)}`);
  }
  return data;
}

/** Signed V5 private request. POST signs the exact JSON body sent over the wire. */
export async function demoPrivateRequest<T>(
  apiKey: string,
  apiSecret: string,
  method: "GET" | "POST",
  path: string,
  params: Record<string, string | number>,
): Promise<BybitResult<T>> {
  if (!apiKey || !apiSecret || !path.startsWith("/v5/")) {
    throw new Error("Неверные параметры приватного запроса Bybit.");
  }
  const timestamp = Date.now().toString();
  const query = method === "GET" ? canonicalQuery(params) : "";
  const body = method === "POST" ? JSON.stringify(params) : "";
  const payload = timestamp + apiKey + RECV_WINDOW + (method === "GET" ? query : body);
  const signature = await hmacHex(apiSecret, payload);
  const response = await fetch(
    `${DEMO_BASE}${path}${query ? `?${query}` : ""}`,
    {
      method,
      headers: {
        "X-BAPI-API-KEY": apiKey,
        "X-BAPI-TIMESTAMP": timestamp,
        "X-BAPI-RECV-WINDOW": RECV_WINDOW,
        "X-BAPI-SIGN": signature,
        "X-BAPI-SIGN-TYPE": "1",
        ...(method === "POST" ? { "content-type": "application/json" } : {}),
      },
      ...(method === "POST" ? { body } : {}),
      signal: AbortSignal.timeout(12_000),
    },
  );
  return await parseResponse<T>(response);
}

function decimalToScaled(value: string, decimals: number): bigint {
  const [mantissa, exponentText] = value.toLowerCase().split("e");
  const exponent = exponentText ? Number(exponentText) : 0;
  const [whole, fraction = ""] = mantissa.split(".");
  if (!/^\d+$/.test(whole) || !/^\d*$/.test(fraction) || !Number.isInteger(exponent)) {
    throw new Error("Некорректное десятичное значение.");
  }
  const digits = BigInt((whole + fraction).replace(/^0+(?=\d)/, ""));
  const shift = decimals - fraction.length + exponent;
  return shift >= 0 ? digits * (10n ** BigInt(shift)) : digits / (10n ** BigInt(-shift));
}

/** Floors a positive quantity to the exchange step using integer arithmetic. */
export function floorToStep(quantity: number, stepText: string): string {
  if (!Number.isFinite(quantity) || quantity <= 0 || !/^\d+(?:\.\d+)?$/.test(stepText)) {
    throw new Error("Некорректное количество или шаг лота.");
  }
  const decimals = (stepText.split(".")[1] ?? "").length;
  const scale = 10n ** BigInt(decimals);
  const step = decimalToScaled(stepText, decimals);
  const scaledQty = decimalToScaled(quantity.toString(), decimals);
  if (step <= 0n) throw new Error("Шаг лота должен быть положительным.");
  const rounded = (scaledQty / step) * step;
  if (rounded <= 0n) throw new Error("Количество после округления меньше минимального шага.");
  const whole = rounded / scale;
  const fraction = (rounded % scale).toString().padStart(decimals, "0");
  return decimals === 0 ? whole.toString() : `${whole}.${fraction}`;
}

function validateOrderIdentity(symbol: string, orderLinkId: string) {
  if (!/^[A-Z0-9]{3,20}$/.test(symbol)) throw new Error("Некорректный символ Bybit.");
  if (!/^[A-Za-z0-9]{1,36}$/.test(orderLinkId)) {
    throw new Error("orderLinkId должен содержать 1–36 букв или цифр.");
  }
}

/** Stable 34-character ID: SHA-256 avoids truncating the candle key. */
export async function makeOrderLinkId(
  runId: string,
  symbol: string,
  side: "Buy" | "Sell",
  candleTime: number,
): Promise<string> {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(runId) ||
      !/^[A-Z0-9]{3,20}$/.test(symbol) ||
      (side !== "Buy" && side !== "Sell") ||
      !Number.isSafeInteger(candleTime) || candleTime <= 0) {
    throw new Error("Невозможно сформировать безопасный orderLinkId.");
  }
  const material = new TextEncoder().encode("god2|" + runId + "|" + symbol + "|" + side + "|" + candleTime);
  const digest = await crypto.subtle.digest("SHA-256", material);
  const hex = Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
  return "g2" + hex.slice(0, 32);
}

export function buildSpotMarketBuy(symbol: string, quoteAmountUsdt: string, orderLinkId: string) {
  validateOrderIdentity(symbol, orderLinkId);
  if (!/^\d+(?:\.\d+)?$/.test(quoteAmountUsdt) ||
      !Number.isFinite(Number(quoteAmountUsdt)) || Number(quoteAmountUsdt) <= 0) {
    throw new Error("Сумма покупки USDT должна быть положительной.");
  }
  return {
    category: "spot",
    symbol,
    side: "Buy",
    orderType: "Market",
    qty: quoteAmountUsdt,
    marketUnit: "quoteCoin",
    orderFilter: "Order",
    orderLinkId,
  };
}

export function buildSpotMarketSell(symbol: string, baseQuantity: string, orderLinkId: string) {
  validateOrderIdentity(symbol, orderLinkId);
  if (!/^\d+(?:\.\d+)?$/.test(baseQuantity) ||
      !Number.isFinite(Number(baseQuantity)) || Number(baseQuantity) <= 0) {
    throw new Error("Количество продажи базового актива должно быть положительным.");
  }
  return {
    category: "spot",
    symbol,
    side: "Sell",
    orderType: "Market",
    qty: baseQuantity,
    marketUnit: "baseCoin",
    orderFilter: "Order",
    orderLinkId,
  };
}
