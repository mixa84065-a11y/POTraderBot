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

/** Structured exchange rejection; HTTP and transport failures remain ambiguous. */
export class DemoApiError extends Error {
  constructor(public readonly code: number, public readonly httpStatus: number, message: string) {
    super(message);
    this.name = "DemoApiError";
  }
}
export function isConfirmedCreateBalanceRejection(error: unknown): boolean {
  return error instanceof DemoApiError && error.httpStatus === 200 && error.code === 170131;
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
    throw new DemoApiError(typeof data.retCode === "number" ? data.retCode : -1, response.status, `Bybit Demo API error ${data.retCode ?? response.status}: ${String(data.retMsg ?? "HTTP error").slice(0, 180)}`);
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

export function buildSpotStopLossSell(
  symbol: string, baseQuantity: string, triggerPrice: string, orderLinkId: string,
) {
  validateOrderIdentity(symbol, orderLinkId);
  if (!/^\d+(?:\.\d+)?$/.test(baseQuantity) ||
      !Number.isFinite(Number(baseQuantity)) || Number(baseQuantity) <= 0 ||
      !/^\d+(?:\.\d+)?$/.test(triggerPrice) ||
      !Number.isFinite(Number(triggerPrice)) || Number(triggerPrice) <= 0) {
    throw new Error("Некорректные параметры условного стоп-ордера.");
  }
  return {
    category: "spot",
    symbol,
    side: "Sell",
    orderType: "Market",
    qty: baseQuantity,
    marketUnit: "baseCoin",
    orderFilter: "StopOrder",
    triggerPrice,
    orderLinkId,
  };
}

export type DemoSpotOrderPayload =
  | ReturnType<typeof buildSpotMarketBuy>
  | ReturnType<typeof buildSpotMarketSell>
  | ReturnType<typeof buildSpotStopLossSell>;
export type DemoSpotOrderStatus = "NEW" | "PARTIALLY_FILLED" | "FILLED" | "CANCELLED" | "REJECTED" | "UNKNOWN";

export type DemoSpotOrder = {
  orderId: string;
  orderLinkId: string;
  orderStatus: string;
  cumExecQty: string;
  cumExecValue: string;
  avgPrice: string;
  leavesQty: string;
};

export type DemoSpotExecution = {
  execId: string;
  orderId: string;
  symbol: string;
  side: "Buy" | "Sell";
  execQty: string;
  execPrice: string;
  execValue: string;
  execFee: string;
  feeCurrency: string;
  execTime: string;
};

export type NormalizedSpotFill = {
  execId: string;
  qty: number;
  price: number;
  valueUsdt: number;
  feeAmount: number;
  feeCurrency: string;
  feeUsdt: number | null;
  executedAt: string;
};

export function normalizeDemoOrderStatus(status: string): DemoSpotOrderStatus {
  switch (status) {
    case "New":
    case "Untriggered":
    case "Triggered":
      return "NEW";
    case "PartiallyFilled":
      return "PARTIALLY_FILLED";
    case "Filled":
      return "FILLED";
    case "Cancelled":
    case "PartiallyFilledCanceled":
    case "Deactivated":
      return "CANCELLED";
    case "Rejected":
      return "REJECTED";
    default:
      return "UNKNOWN";
  }
}

export async function cancelDemoSpotStopOrder(
  apiKey: string, apiSecret: string, symbol: string, orderId: string,
): Promise<void> {
  if (!/^[A-Z0-9]{3,20}$/.test(symbol) || !orderId || orderId.length > 128) {
    throw new Error("Некорректные параметры отмены стоп-ордера.");
  }
  await demoPrivateRequest<{ orderId?: string }>(
    apiKey, apiSecret, "POST", "/v5/order/cancel",
    { category: "spot", symbol, orderId, orderFilter: "StopOrder" },
  );
}

export async function placeDemoSpotMarketOrder(
  apiKey: string,
  apiSecret: string,
  payload: DemoSpotOrderPayload,
): Promise<{ orderId: string; orderLinkId: string }> {
  const response = await demoPrivateRequest<{ orderId?: string; orderLinkId?: string }>(
    apiKey, apiSecret, "POST", "/v5/order/create", payload,
  );
  const orderId = response.result?.orderId;
  const orderLinkId = response.result?.orderLinkId;
  if (!orderId || !orderLinkId || orderLinkId !== payload.orderLinkId) {
    throw new Error("Bybit не подтвердил идентификатор созданной демо-заявки.");
  }
  return { orderId, orderLinkId };
}

export async function getDemoSpotOrder(
  apiKey: string,
  apiSecret: string,
  symbol: string,
  orderId: string,
): Promise<DemoSpotOrder> {
  // Query active orders first, then the recent closed-order cache.
  for (const openOnly of [0, 1]) {
    const response = await demoPrivateRequest<{ list?: DemoSpotOrder[] }>(
      apiKey, apiSecret, "GET", "/v5/order/realtime",
      { category: "spot", symbol, orderId, openOnly },
    );
    const order = response.result?.list?.find((item) => item.orderId === orderId);
    if (order) return order;
  }
  // Bybit notes that after a service restart, closed UTA orders may only
  // be available through order history rather than the realtime cache.
  const history = await demoPrivateRequest<{ list?: DemoSpotOrder[] }>(
    apiKey, apiSecret, "GET", "/v5/order/history",
    { category: "spot", symbol, orderId, limit: 50 },
  );
  const historical = history.result?.list?.find((item) => item.orderId === orderId);
  if (historical) return historical;
  throw new Error("Заявка не найдена в realtime и order history; статус оставлен неизвестным.");
}

/** Looks up an order by its client ID after an ambiguous network timeout.
 * A null result is not permission to blindly retry: the caller must keep the intent UNKNOWN.
 */
export async function findDemoSpotOrderByLinkId(
  apiKey: string,
  apiSecret: string,
  symbol: string,
  orderLinkId: string,
): Promise<DemoSpotOrder | null> {
  for (const openOnly of [0, 1]) {
    const response = await demoPrivateRequest<{ list?: DemoSpotOrder[] }>(
      apiKey, apiSecret, "GET", "/v5/order/realtime",
      { category: "spot", symbol, orderLinkId, openOnly },
    );
    const order = response.result?.list?.find((item) => item.orderLinkId === orderLinkId);
    if (order) return order;
  }
  const history = await demoPrivateRequest<{ list?: DemoSpotOrder[] }>(
    apiKey, apiSecret, "GET", "/v5/order/history",
    { category: "spot", symbol, orderLinkId, limit: 50 },
  );
  return history.result?.list?.find((item) => item.orderLinkId === orderLinkId) ?? null;
}

export async function getDemoSpotExecutions(
  apiKey: string,
  apiSecret: string,
  symbol: string,
  orderId: string,
): Promise<DemoSpotExecution[]> {
  const fills: DemoSpotExecution[] = [];
  let cursor = "";
  for (let page = 0; page < 10; page++) {
    const params: Record<string, string | number> = {
      category: "spot", symbol, orderId, limit: 100,
    };
    if (cursor) params.cursor = cursor;
    const response = await demoPrivateRequest<{
      list?: DemoSpotExecution[];
      nextPageCursor?: string;
    }>(apiKey, apiSecret, "GET", "/v5/execution/list", params);
    for (const fill of response.result?.list ?? []) {
      if (fill.orderId === orderId && fill.symbol === symbol && fill.execId) fills.push(fill);
    }
    const next = response.result?.nextPageCursor ?? "";
    if (!next) break;
    if (next === cursor) throw new Error("Bybit execution pagination cursor did not advance.");
    if (page === 9) throw new Error("Bybit execution history exceeded the safe pagination limit; reconciliation is incomplete.");
    cursor = next;
  }
  return fills;
}

/** Converts fees to USDT only when the exchange reports a known quote/base currency. */
export function normalizeSpotFill(fill: DemoSpotExecution, quoteCoin = "USDT"): NormalizedSpotFill {
  const qty = Number(fill.execQty);
  const price = Number(fill.execPrice);
  const value = Number(fill.execValue);
  const feeAmount = Number(fill.execFee);
  const execTime = Number(fill.execTime);
  if (!fill.execId || !fill.orderId || !/^[A-Z0-9]{3,20}$/.test(fill.symbol) ||
      (fill.side !== "Buy" && fill.side !== "Sell") ||
      ![qty, price, feeAmount, execTime].every(Number.isFinite) ||
      qty <= 0 || price <= 0 || feeAmount < 0 || execTime <= 0) {
    throw new Error("Bybit вернул некорректные данные исполнения.");
  }
  const valueUsdt = Number.isFinite(value) && value > 0 ? value : qty * price;
  const baseCoin = fill.symbol.endsWith(quoteCoin)
    ? fill.symbol.slice(0, -quoteCoin.length)
    : "";
  if (!baseCoin || !Number.isFinite(valueUsdt) || valueUsdt <= 0 || !fill.feeCurrency) {
    throw new Error("Невозможно однозначно определить валюту исполнения или комиссии.");
  }
  let feeUsdt: number | null = null;
  if (fill.feeCurrency === quoteCoin) feeUsdt = feeAmount;
  else if (fill.feeCurrency === baseCoin) feeUsdt = feeAmount * price;
  return {
    execId: fill.execId,
    qty,
    price,
    valueUsdt,
    feeAmount,
    feeCurrency: fill.feeCurrency,
    feeUsdt,
    executedAt: new Date(execTime).toISOString(),
  };
}

/** Resolves a third-token spot fee through a fresh public USDT market quote.
 * If the fee asset has no liquid USDT spot pair, it throws instead of understating costs.
 */
export async function resolveSpotFeeUsdt(fill: NormalizedSpotFill): Promise<number> {
  if (fill.feeUsdt !== null) return fill.feeUsdt;
  if (!/^[A-Z0-9]{2,12}$/.test(fill.feeCurrency) || fill.feeCurrency === "USDT") {
    throw new Error("Не удалось определить валюту комиссии для пересчёта в USDT.");
  }
  const symbol = fill.feeCurrency + "USDT";
  const url = new URL("https://api.bybit.com/v5/market/tickers");
  url.search = new URLSearchParams({ category: "spot", symbol }).toString();
  const response = await fetch(url, { signal: AbortSignal.timeout(8_000) });
  const data = await response.json();
  if (!response.ok || data.retCode !== 0) {
    throw new Error("Не удалось получить курс токена комиссии " + fill.feeCurrency + "/USDT.");
  }
  const ticker = data.result?.list?.[0];
  const price = Number(ticker?.lastPrice);
  if (!Number.isFinite(price) || price <= 0) {
    throw new Error("Нет достоверной котировки токена комиссии " + fill.feeCurrency + "/USDT.");
  }
  return fill.feeAmount * price;
}
