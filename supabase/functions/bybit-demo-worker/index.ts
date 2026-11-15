import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import {
  buildSpotMarketBuy,
  buildSpotMarketSell,
  demoPrivateRequest,
  floorToStep,
  makeOrderLinkId,
} from "./bybit.ts";
import { submitOrReconcileDemoOrder, type PendingDemoOrder } from "./execution.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const ALLOWED_SYMBOLS = new Set(["BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT", "BNBUSDT", "DOGEUSDT"]);

// Conservative Bybit Demo-only settings. Spot is long-only: DOWN signals are
// recorded but never translated into a short position.
const ENTRY_NOTIONAL_USDT = 100;
const DAILY_LOSS_LIMIT_USDT = 200;
const MAX_DRAWDOWN_PCT = 5;
const MAX_SPREAD_PCT = 0.15;
const STOP_LOSS_PCT = 0.7;
const TAKE_PROFIT_PCT = 1.4;

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
type Candle = { time: number; open: number; high: number; low: number; close: number; volume: number };
type Signal = {
  direction: "UP" | "DOWN" | "NEUTRAL";
  score: number;
  ema9: number;
  ema21: number;
  rsi14: number;
  momentum_pct: number;
  candle_time: number;
};
type SpotMarket = {
  status: string;
  last_price: number;
  best_bid: number;
  best_ask: number;
  spread_pct: number;
  min_order_amount_usdt: number;
  max_market_order_qty: number;
  tick_size: string;
  base_precision: string | null;
  quote_precision: string | null;
  checked_at: string;
};
type OpenPosition = {
  position_id: string;
  user_id: string;
  symbol: string;
  qty: number;
  entry_price: number;
  entry_order_id: string;
  stop_price: number;
  target_price: number;
};
type RunRiskState = {
  status: string;
  start_equity: number | null;
  end_equity: number | null;
  peak_equity: number | null;
  realized_pnl: number;
  fees_paid: number;
  max_drawdown_pct: number;
};
type PendingExitFill = {
  fill_id: number;
  order_id: string;
  position_id: string;
  symbol: string;
  qty: number;
  price: number;
  fee_usdt: number | null;
  exchange_order_id: string | null;
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
    signal: AbortSignal.timeout(12_000),
  });
  const body = await response.text();
  if (!response.ok) throw new Error(`RPC ${name} failed (${response.status}): ${body.slice(0, 400)}`);
  return body ? JSON.parse(body) as T : (null as T);
}

async function publicJson(path: string, params: Record<string, string>) {
  const url = new URL(`https://api.bybit.com${path}`);
  url.search = new URLSearchParams(params).toString();
  const response = await fetch(url, { signal: AbortSignal.timeout(10_000) });
  const data = await response.json();
  if (!response.ok || data.retCode !== 0) {
    throw new Error(`Bybit public API ${path}: ${data.retMsg ?? response.status}`);
  }
  return data;
}

type SpotInstrument = {
  symbol: string;
  status: string;
  lotSizeFilter: {
    minOrderAmt?: string;
    maxMarketOrderQty?: string;
    basePrecision?: string;
    quotePrecision?: string;
  };
  priceFilter: { tickSize?: string };
};

async function publicSpotPreflight(symbol: string): Promise<SpotMarket> {
  const [instrumentData, tickerData] = await Promise.all([
    publicJson("/v5/market/instruments-info", { category: "spot", symbol }),
    publicJson("/v5/market/tickers", { category: "spot", symbol }),
  ]);
  const instrument = (instrumentData.result?.list ?? [])[0] as SpotInstrument | undefined;
  const ticker = (tickerData.result?.list ?? [])[0];
  if (!instrument || instrument.symbol !== symbol || instrument.status !== "Trading") {
    throw new Error(`${symbol}: инструмент отсутствует или не в статусе Trading.`);
  }
  const last = Number(ticker?.lastPrice);
  const bid = Number(ticker?.bid1Price);
  const ask = Number(ticker?.ask1Price);
  const minOrderAmt = Number(instrument.lotSizeFilter?.minOrderAmt);
  const maxMarketOrderQty = Number(instrument.lotSizeFilter?.maxMarketOrderQty);
  const tickSize = Number(instrument.priceFilter?.tickSize);
  if (![last, bid, ask, minOrderAmt, maxMarketOrderQty, tickSize].every(Number.isFinite) ||
      last <= 0 || bid <= 0 || ask <= 0 || ask < bid ||
      minOrderAmt <= 0 || maxMarketOrderQty <= 0 || tickSize <= 0) {
    throw new Error(`${symbol}: биржа вернула неполные или некорректные торговые ограничения.`);
  }
  return {
    status: instrument.status,
    last_price: last,
    best_bid: bid,
    best_ask: ask,
    spread_pct: Number(((ask - bid) / last * 100).toFixed(5)),
    min_order_amount_usdt: minOrderAmt,
    max_market_order_qty: maxMarketOrderQty,
    tick_size: String(instrument.priceFilter.tickSize),
    base_precision: instrument.lotSizeFilter.basePrecision ?? null,
    quote_precision: instrument.lotSizeFilter.quotePrecision ?? null,
    checked_at: new Date().toISOString(),
  };
}

async function publicKlines(symbol: string): Promise<Candle[]> {
  const data = await publicJson("/v5/market/kline", { category: "spot", symbol, interval: "1", limit: "80" });
  const parsed = (data.result?.list ?? []).map((row: string[]) => ({
    time: Number(row[0]), open: Number(row[1]), high: Number(row[2]),
    low: Number(row[3]), close: Number(row[4]), volume: Number(row[5]),
  })).reverse();
  const closed = parsed.filter((candle: Candle) =>
    Number.isFinite(candle.time) && Number.isFinite(candle.close) &&
    candle.close > 0 && candle.time + 60_000 <= Date.now()
  );
  if (closed.length < 30) throw new Error(`Недостаточно закрытых свечей для ${symbol}.`);
  return closed;
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
function analyze(candles: Candle[]): Signal {
  const closes = candles.map((c) => c.close);
  const fast = ema(closes, 9), slow = ema(closes, 21), strength = rsi(closes);
  const momentum = closes.length >= 6 && closes.at(-6)! > 0
    ? (closes.at(-1)! / closes.at(-6)! - 1) * 100 : 0;
  if (![fast, slow, strength, momentum].every(Number.isFinite)) {
    throw new Error("Индикаторы не рассчитаны: недостаточно корректных данных.");
  }
  const score = (fast > slow ? 1 : -1) +
    (strength >= 52 ? 1 : strength <= 48 ? -1 : 0) +
    (momentum > 0.04 ? 1 : momentum < -0.04 ? -1 : 0);
  return {
    direction: score >= 2 ? "UP" : score <= -2 ? "DOWN" : "NEUTRAL",
    score, ema9: fast, ema21: slow, rsi14: strength,
    momentum_pct: momentum, candle_time: candles.at(-1)?.time ?? 0,
  };
}

async function log(runId: string, level: string, eventType: string, message: string, details: unknown = {}) {
  await rpc("bybit_demo_worker_log", {
    p_run_id: runId, p_level: level, p_event_type: eventType, p_message: message, p_details: details,
  });
}

function buildPayload(order: PendingDemoOrder) {
  const qty = String(order.requested_qty);
  if (order.side === "Buy" && order.qty_unit === "quoteCoin") {
    return buildSpotMarketBuy(order.symbol, qty, order.order_link_id);
  }
  if (order.side === "Sell" && order.qty_unit === "baseCoin") {
    return buildSpotMarketSell(order.symbol, qty, order.order_link_id);
  }
  throw new Error("Сохранённая заявка имеет недопустимую комбинацию side/qty_unit.");
}

async function reconcileOrders(run: ActiveRun, orders: PendingDemoOrder[]) {
  const blockedSymbols = new Set<string>();
  for (const order of orders) {
    try {
      const result = await submitOrReconcileDemoOrder({
        apiKey: run.api_key,
        apiSecret: run.api_secret,
        order,
        payload: buildPayload(order),
        rpc,
      });
      if (order.purpose === "ENTRY" &&
          (result.status === "FILLED" || result.status === "CANCELLED") &&
          result.filledQty > 0) {
        const finalized = await rpc<{ position_id: string; created: boolean; fee_conversion_complete: boolean }>(
          "bybit_demo_worker_finalize_entry", { p_order_id: order.order_id },
        );
        if (!finalized.fee_conversion_complete) {
          blockedSymbols.add(order.symbol);
          await log(run.run_id, "WARN", "ENTRY_FEE_CONVERSION_PENDING",
            "Вход исполнен, но комиссия пока не полностью пересчитана в USDT. Новые входы приостановлены.",
            { symbol: order.symbol, position_id: finalized.position_id },
          );
        } else {
          await log(run.run_id, "INFO", "ENTRY_POSITION_FINALIZED",
            "Исполненная заявка восстановлена и записана как открытая демо-позиция.",
            { symbol: order.symbol, position_id: finalized.position_id, qty: result.filledQty, average_price: result.averageFillPrice },
          );
        }
      }
      if (!result.feeConversionComplete) blockedSymbols.add(order.symbol);
    } catch (error) {
      blockedSymbols.add(order.symbol);
      const message = error instanceof Error ? error.message : "Unknown order reconciliation error";
      await log(run.run_id, "ERROR", "ORDER_RECONCILIATION_ERROR", message, {
        symbol: order.symbol, order_id: order.order_id, purpose: order.purpose, status: order.status,
      });
    }
  }
  return blockedSymbols;
}

async function applyPendingExitFills(runId: string) {
  const fills = await rpc<PendingExitFill[]>("bybit_demo_worker_pending_exit_fills", { p_run_id: runId });
  let applied = 0;
  for (const fill of fills ?? []) {
    const result = await rpc<{ applied: boolean; already_applied: boolean; fee_conversion_pending?: boolean }>(
      "bybit_demo_worker_apply_exit_fill",
      { p_fill_id: fill.fill_id, p_reason: "Bybit Demo risk/stop exit" },
    );
    if (result.applied) {
      applied++;
      if (result.fee_conversion_pending) {
        await log(runId, "WARN", "EXIT_FEE_CONVERSION_PENDING",
          "Количество закрытой позиции учтено; комиссия будет добавлена в PnL после пересчёта в USDT.",
          { symbol: fill.symbol, fill_id: fill.fill_id },
        );
      }
    }
  }
  return applied;
}

async function walletState(apiKey: string, apiSecret: string) {
  const response = await demoPrivateRequest<{
    list?: Array<{ totalEquity?: string; totalWalletBalance?: string; coin?: Array<{
      coin: string; walletBalance?: string; locked?: string; availableToWithdraw?: string;
    }> }>;
  }>(apiKey, apiSecret, "GET", "/v5/account/wallet-balance", {
    accountType: "UNIFIED", coin: "USDT",
  });
  const account = response.result?.list?.[0];
  if (!account) throw new Error("Bybit Demo API не вернул Unified wallet.");
  const equity = Number(account.totalEquity ?? account.totalWalletBalance);
  if (!Number.isFinite(equity) || equity < 0) throw new Error("Некорректный баланс Bybit Demo API.");
  const usdt = account.coin?.find((coin) => coin.coin === "USDT");
  if (!usdt) throw new Error("Bybit Demo API не вернул USDT в составе кошелька.");
  const walletBalance = Number(usdt.walletBalance ?? 0);
  const locked = Number(usdt.locked ?? 0);
  const availableRaw = usdt.availableToWithdraw;
  const available = typeof availableRaw === "string" && availableRaw.trim() !== "" && Number.isFinite(Number(availableRaw))
    ? Number(availableRaw)
    : walletBalance - locked;
  if (![walletBalance, locked, available].every(Number.isFinite) || available < 0) {
    throw new Error("Не удалось безопасно определить доступный баланс USDT.");
  }
  return { equity, available_usdt: available };
}

async function createAndSubmitExit(
  run: ActiveRun,
  position: OpenPosition,
  market: SpotMarket,
  candleTime: number,
  reason: string,
) {
  if (!market.base_precision) throw new Error(`${position.symbol}: биржа не вернула точность базового актива; продажа заблокирована.`);
  const sellQty = floorToStep(Math.min(position.qty, market.max_market_order_qty), market.base_precision);
  if (Number(sellQty) <= 0) throw new Error(`${position.symbol}: остаток меньше минимального шага продажи.`);
  const linkId = await makeOrderLinkId(run.run_id, position.symbol, "Sell", candleTime);
  const orderId = await rpc<string>("bybit_demo_worker_create_order_intent", {
    p_run_id: run.run_id, p_symbol: position.symbol, p_side: "Sell", p_purpose: "EXIT",
    p_order_link_id: linkId, p_requested_qty: sellQty, p_qty_unit: "baseCoin",
    p_position_id: position.position_id,
  });
  const pending = await rpc<PendingDemoOrder[]>("bybit_demo_worker_pending_orders", { p_run_id: run.run_id });
  const order = (pending ?? []).find((item) => item.order_id === orderId);
  if (!order) throw new Error("Сервер не вернул созданное намерение на выход; заявка не отправлена.");
  await log(run.run_id, "WARN", "EXIT_ORDER_INTENT",
    `Подготовлена демо-продажа позиции: ${reason}.`,
    { symbol: position.symbol, position_id: position.position_id, qty: sellQty, stop: position.stop_price, target: position.target_price },
  );
  const result = await submitOrReconcileDemoOrder({
    apiKey: run.api_key, apiSecret: run.api_secret, order,
    payload: buildPayload(order), rpc,
  });
  return result;
}

async function processRun(run: ActiveRun) {
  const lease = crypto.randomUUID();
  const claimed = await rpc<boolean>("bybit_demo_worker_claim_run", {
    p_run_id: run.run_id, p_lease_token: lease,
  });
  if (!claimed) return { run_id: run.run_id, status: "SKIPPED_LOCKED" };

  try {
    const expired = Date.parse(run.ends_at) <= Date.now();
    const wallet = await walletState(run.api_key, run.api_secret);
    let riskRows = await rpc<RunRiskState[]>("bybit_demo_worker_get_run_state", { p_run_id: run.run_id });
    let risk = riskRows?.[0];
    const startingEquity = Number(risk?.start_equity ?? wallet.equity);
    const peakEquity = Number(risk?.peak_equity ?? startingEquity);
    const dailyLoss = Math.max(0, startingEquity - wallet.equity);
    const drawdown = peakEquity > 0 ? Math.max(0, (peakEquity - wallet.equity) / peakEquity * 100) : 0;
    const riskStop = dailyLoss >= DAILY_LOSS_LIMIT_USDT || drawdown >= MAX_DRAWDOWN_PCT;
    if ((expired || riskStop) && run.status === "RUNNING") {
      await rpc("bybit_demo_worker_request_stop", {
        p_run_id: run.run_id,
        p_reason: expired
          ? "Достигнуто запланированное время окончания демо-запуска."
          : `Достигнут лимит риска: убыток ${dailyLoss.toFixed(2)} USDT, просадка ${drawdown.toFixed(2)}%.`,
      });
    }
    const stopping = run.status === "STOPPING" || expired || riskStop;

    const symbols = (run.symbols ?? []).filter((symbol) => ALLOWED_SYMBOLS.has(symbol)).slice(0, 3);
    if (symbols.length === 0 || symbols.length !== (run.symbols ?? []).length) {
      throw new Error("В запуске обнаружен пустой или неподдерживаемый список инструментов.");
    }

    // Recover every persisted order before looking for new entries or finalizing a stop.
    let pendingOrders = await rpc<PendingDemoOrder[]>("bybit_demo_worker_pending_orders", { p_run_id: run.run_id });
    const blockedSymbols = await reconcileOrders(run, pendingOrders ?? []);
    const appliedExitFills = await applyPendingExitFills(run.run_id);

    const positions = await rpc<OpenPosition[]>("bybit_demo_worker_open_positions", { p_run_id: run.run_id });
    const markets = new Map<string, SpotMarket>();
    const signals = new Map<string, Signal>();
    const candleTimes = new Map<string, number>();
    for (const symbol of symbols) {
      try {
        const market = await publicSpotPreflight(symbol);
        markets.set(symbol, market);
        const candles = await publicKlines(symbol);
        const signal = analyze(candles);
        signals.set(symbol, signal);
        candleTimes.set(symbol, signal.candle_time);
      } catch (error) {
        await log(run.run_id, "ERROR", "MARKET_DATA_ERROR",
          error instanceof Error ? error.message : "Market data unavailable", { symbol });
      }
    }

    // Stop-loss, take-profit, and run-stop flattening. Spot has no short exposure.
    let exitOrders = 0;
    const pendingAfterRecovery = await rpc<PendingDemoOrder[]>("bybit_demo_worker_pending_orders", { p_run_id: run.run_id });
    for (const position of positions ?? []) {
      const market = markets.get(position.symbol);
      if (!market) continue;
      const hasPendingExit = (pendingAfterRecovery ?? []).some((order) =>
        order.purpose === "EXIT" && order.position_id === position.position_id
      );
      if (hasPendingExit) continue;
      let reason = "";
      if (stopping) reason = "остановка запуска/лимит риска";
      else if (market.last_price <= position.stop_price) reason = "stop-loss 0.7%";
      else if (market.last_price >= position.target_price) reason = "take-profit 1.4%";
      if (!reason) continue;
      const candleTime = Math.max(
        candleTimes.get(position.symbol) ?? 0,
        Math.floor(Date.now() / 60_000) * 60_000,
      );
      try {
        const result = await createAndSubmitExit(run, position, market, candleTime, reason);
        exitOrders++;
        if (result.status === "FILLED" || result.status === "CANCELLED") {
          await applyPendingExitFills(run.run_id);
        }
      } catch (error) {
        await log(run.run_id, "ERROR", "EXIT_ORDER_ERROR",
          error instanceof Error ? error.message : "Exit order failed", {
            symbol: position.symbol, position_id: position.position_id, reason,
          });
      }
    }

    const unpricedFees = await rpc<number>("bybit_demo_worker_unpriced_order_count", { p_run_id: run.run_id });
    const currentPositions = await rpc<OpenPosition[]>("bybit_demo_worker_open_positions", { p_run_id: run.run_id });
    const hasOpenPosition = (currentPositions ?? []).length > 0;

    // Only long spot entries are supported. Never convert a DOWN signal into
    // an unsupported short sale. One open position per symbol is enforced in SQL.
    let entryOrders = 0;
    if (!stopping && unpricedFees === 0 && !blockedSymbols.size) {
      const currentPending = await rpc<PendingDemoOrder[]>("bybit_demo_worker_pending_orders", { p_run_id: run.run_id });
      const openSymbols = new Set((currentPositions ?? []).map((position) => position.symbol));
      const pendingEntrySymbols = new Set((currentPending ?? [])
        .filter((order) => order.purpose === "ENTRY")
        .map((order) => order.symbol));
      for (const symbol of symbols) {
        const signal = signals.get(symbol);
        const market = markets.get(symbol);
        if (!signal || !market || openSymbols.has(symbol) || pendingEntrySymbols.has(symbol)) continue;
        if (signal.direction !== "UP" || signal.score < 2) continue;
        if (market.spread_pct > MAX_SPREAD_PCT) {
          await log(run.run_id, "INFO", "ENTRY_SKIPPED_SPREAD",
            "Вход пропущен из-за широкого спреда.", { symbol, spread_pct: market.spread_pct });
          continue;
        }
        if (market.min_order_amount_usdt > ENTRY_NOTIONAL_USDT ||
            ENTRY_NOTIONAL_USDT / market.last_price > market.max_market_order_qty) {
          await log(run.run_id, "WARN", "ENTRY_SKIPPED_EXCHANGE_LIMIT",
            "Размер демо-заявки не проходит ограничения инструмента.", {
              symbol, min_order_amount_usdt: market.min_order_amount_usdt,
              max_market_order_qty: market.max_market_order_qty,
            });
          continue;
        }
        if (wallet.available_usdt < ENTRY_NOTIONAL_USDT * 1.01) {
          await log(run.run_id, "WARN", "ENTRY_SKIPPED_BALANCE",
            "Недостаточно свободных USDT для следующей демо-покупки.", {
              available_usdt: wallet.available_usdt, required_usdt: ENTRY_NOTIONAL_USDT * 1.01,
            });
          break;
        }
        const linkId = await makeOrderLinkId(run.run_id, symbol, "Buy", signal.candle_time);
        const orderId = await rpc<string>("bybit_demo_worker_create_order_intent", {
          p_run_id: run.run_id, p_symbol: symbol, p_side: "Buy", p_purpose: "ENTRY",
          p_order_link_id: linkId, p_requested_qty: ENTRY_NOTIONAL_USDT,
          p_qty_unit: "quoteCoin", p_position_id: null,
        });
        const refreshedPending = await rpc<PendingDemoOrder[]>("bybit_demo_worker_pending_orders", { p_run_id: run.run_id });
        const order = (refreshedPending ?? []).find((item) => item.order_id === orderId);
        if (!order) {
          await log(run.run_id, "INFO", "ENTRY_SKIPPED_DUPLICATE_CANDLE",
            "Для этой свечи уже существует обработанная заявка; повторный вход запрещён.", { symbol, candle_time: signal.candle_time });
          continue;
        }
        const result = await submitOrReconcileDemoOrder({
          apiKey: run.api_key, apiSecret: run.api_secret, order,
          payload: buildPayload(order), rpc,
        });
        entryOrders++;
        if ((result.status === "FILLED" || result.status === "CANCELLED") && result.filledQty > 0) {
          const finalized = await rpc<{ position_id: string; created: boolean; fee_conversion_complete: boolean }>(
            "bybit_demo_worker_finalize_entry", { p_order_id: order.order_id },
          );
          if (!finalized.fee_conversion_complete) {
            await log(run.run_id, "WARN", "ENTRY_FEE_CONVERSION_PENDING",
              "Позиция открыта, но комиссия ещё не полностью пересчитана; новые входы приостановлены.",
              { symbol, position_id: finalized.position_id },
            );
          }
          await log(run.run_id, "INFO", "ENTRY_POSITION_FINALIZED",
            "Вход Bybit Demo подтверждён и записан в журнал позиции.",
            { symbol, position_id: finalized.position_id, qty: result.filledQty, average_price: result.averageFillPrice },
          );
        }
        if (!result.feeConversionComplete) {
          await log(run.run_id, "WARN", "ENTRY_FEE_CONVERSION_PENDING",
            "Комиссия входа пока не полностью пересчитана в USDT; новые входы будут приостановлены.", { symbol });
          break;
        }
        // At most one new market entry per worker cycle to limit correlated exposure.
        break;
      }
    } else if (unpricedFees > 0) {
      await log(run.run_id, "WARN", "NEW_ENTRIES_PAUSED_UNPRICED_FEES",
        "Новые входы заблокированы до полного пересчёта комиссий в USDT.", { unresolved_orders: unpricedFees });
    }

    await rpc("bybit_demo_worker_heartbeat_v2", {
      p_run_id: run.run_id, p_equity: wallet.equity, p_realized_pnl: null,
      p_fees: null, p_error: null, p_status: null,
    });

    // A stopping run is finalized only after every position and pending order is reconciled.
    if (stopping) {
      const finalPositions = await rpc<OpenPosition[]>("bybit_demo_worker_open_positions", { p_run_id: run.run_id });
      const finalPending = await rpc<PendingDemoOrder[]>("bybit_demo_worker_pending_orders", { p_run_id: run.run_id });
      if ((finalPositions ?? []).length === 0 && (finalPending ?? []).length === 0) {
        const finalStatus = run.status === "STOPPING" || riskStop ? "STOPPED" : "COMPLETED";
        await rpc("bybit_demo_worker_heartbeat_v2", {
          p_run_id: run.run_id, p_equity: wallet.equity, p_realized_pnl: null,
          p_fees: null, p_error: null, p_status: finalStatus,
        });
        await log(run.run_id, "INFO", finalStatus,
          "Демо-запуск завершён: открытых позиций и необработанных заявок нет.",
        );
        return { run_id: run.run_id, status: finalStatus, applied_exit_fills: appliedExitFills };
      }
      await log(run.run_id, "WARN", "STOPPING_WAITING_FOR_RECONCILIATION",
        "Новые входы отключены. Остановка ожидает закрытия позиций и подтверждения всех заявок.",
        { open_positions: (finalPositions ?? []).length, pending_orders: (finalPending ?? []).length },
      );
      return {
        run_id: run.run_id, status: "STOPPING_WAITING_FOR_SAFE_FLATTEN",
        open_positions: (finalPositions ?? []).length, pending_orders: (finalPending ?? []).length,
        exit_orders, applied_exit_fills: appliedExitFills,
      };
    }

    await log(run.run_id, "INFO", "DEMO_ENGINE_CYCLE",
      "Цикл Bybit Demo: заявки сверены, риск проверен, стоп/тейк обработан; новые входы только по подтверждённому сигналу UP.",
      {
        equity_usdt: wallet.equity, available_usdt: wallet.available_usdt,
        daily_loss_usdt: dailyLoss, drawdown_pct: drawdown,
        unpriced_fee_orders: unpricedFees, entry_orders: entryOrders, exit_orders: exitOrders,
        applied_exit_fills: appliedExitFills,
        signals: symbols.map((symbol) => ({ symbol, signal: signals.get(symbol) ?? null, market: markets.get(symbol) ?? null })),
        execution_mode: "BYBIT_DEMO_SPOT_ONLY",
      },
    );
    return {
      run_id: run.run_id, status: "DEMO_ENGINE_CYCLE",
      equity_usdt: wallet.equity, available_usdt: wallet.available_usdt,
      daily_loss_usdt: dailyLoss, drawdown_pct: drawdown,
      entry_orders: entryOrders, exit_orders: exitOrders, applied_exit_fills: appliedExitFills,
      unpriced_fee_orders: unpricedFees,
    };
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
