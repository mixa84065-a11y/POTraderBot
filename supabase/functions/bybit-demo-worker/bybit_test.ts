import {
  buildSpotMarketBuy,
  buildSpotMarketSell,
  buildSpotStopLossSell,
  cancelDemoSpotStopOrder,
  floorToStep,
  getDemoSpotOrder,
  makeOrderLinkId,
  normalizeDemoOrderStatus,
  normalizeSpotFill,
} from "./bybit.ts";

Deno.test("floorToStep respects decimal lot increments", () => {
  if (floorToStep(1.239, "0.01") !== "1.23") throw new Error("0.01 step rounding failed");
  if (floorToStep(0.00999, "0.001") !== "0.009") throw new Error("0.001 step rounding failed");
  if (floorToStep(12.9, "1") !== "12") throw new Error("integer step rounding failed");
});

Deno.test("spot market buy spends quote USDT", () => {
  const order = buildSpotMarketBuy("BTCUSDT", "25", "g2test");
  if (order.side !== "Buy" || order.marketUnit !== "quoteCoin" || order.qty !== "25") {
    throw new Error("quote-coin buy payload mismatch");
  }
});

Deno.test("spot market sell uses exact base quantity", () => {
  const order = buildSpotMarketSell("BTCUSDT", "0.00042", "g2test");
  if (order.side !== "Sell" || order.marketUnit !== "baseCoin" || order.qty !== "0.00042") {
    throw new Error("base-coin sell payload mismatch");
  }
});

Deno.test("order link IDs are deterministic, unique by input and bounded", async () => {
  const run = "12345678-1234-4234-8234-123456789abc";
  const a = await makeOrderLinkId(run, "BTCUSDT", "Buy", 1_800_000_000_000);
  const b = await makeOrderLinkId(run, "BTCUSDT", "Buy", 1_800_000_000_000);
  const c = await makeOrderLinkId(run, "BTCUSDT", "Buy", 1_800_000_060_000);
  if (a !== b || a === c || a.length !== 34) throw new Error("orderLinkId stability/uniqueness/length check failed");
});

Deno.test("order builders reject malformed symbols, IDs and amounts", () => {
  const invalid = [
    () => buildSpotMarketBuy("BTC/USDT", "25", "g2test"),
    () => buildSpotMarketBuy("BTCUSDT", "0", "g2test"),
    () => buildSpotMarketBuy("BTCUSDT", "25", "id with spaces"),
    () => buildSpotMarketSell("BTCUSDT", "NaN", "g2test"),
  ];
  for (const test of invalid) {
    let rejected = false;
    try { test(); } catch { rejected = true; }
    if (!rejected) throw new Error("invalid order input was accepted");
  }
});

Deno.test("order status mapping preserves partial and terminal states", () => {
  if (normalizeDemoOrderStatus("PartiallyFilled") !== "PARTIALLY_FILLED") throw new Error("partial status mapping failed");
  if (normalizeDemoOrderStatus("Filled") !== "FILLED") throw new Error("filled status mapping failed");
  if (normalizeDemoOrderStatus("Cancelled") !== "CANCELLED") throw new Error("cancel status mapping failed");
  if (normalizeDemoOrderStatus("PartiallyFilledCanceled") !== "CANCELLED") throw new Error("partial-cancel status mapping failed");
  if (normalizeDemoOrderStatus("Rejected") !== "REJECTED") throw new Error("reject status mapping failed");
  if (normalizeDemoOrderStatus("mystery") !== "UNKNOWN") throw new Error("unknown status must fail closed");
});

Deno.test("spot fill fee conversion handles quote/base currencies and flags third tokens", () => {
  const common = {
    execId: "exec-1", orderId: "order-1", symbol: "BTCUSDT", side: "Buy" as const,
    execQty: "0.01", execPrice: "50000", execValue: "500",
    execFee: "0.00001", execTime: "1800000000000",
  };
  const baseFee = normalizeSpotFill({ ...common, feeCurrency: "BTC" });
  if (baseFee.feeUsdt !== 0.5) throw new Error("base-coin fee conversion failed");
  const quoteFee = normalizeSpotFill({ ...common, feeCurrency: "USDT", execFee: "0.5" });
  if (quoteFee.feeUsdt !== 0.5) throw new Error("quote-coin fee conversion failed");
  const thirdCoinFee = normalizeSpotFill({ ...common, feeCurrency: "BNB" });
  if (thirdCoinFee.feeUsdt !== null) throw new Error("third-token fee must remain unconverted");
});


Deno.test("closed order recovery falls back to Bybit order history after realtime cache misses", async () => {
  const originalFetch = globalThis.fetch;
  const paths: string[] = [];
  Object.defineProperty(globalThis, "fetch", {
    configurable: true,
    writable: true,
    value: async (input: RequestInfo | URL) => {
      const url = new URL(typeof input === "string" || input instanceof URL ? input.toString() : input.url);
      paths.push(url.pathname);
      return new Response(JSON.stringify({
        retCode: 0, retMsg: "OK",
        result: { list: url.pathname === "/v5/order/history" ? [{
          orderId: "history-order-1", orderLinkId: "g2history",
          orderStatus: "Filled", cumExecQty: "0.01", cumExecValue: "500",
          avgPrice: "50000", leavesQty: "0",
        }] : [] },
      }), { status: 200, headers: { "content-type": "application/json" } });
    },
  });
  try {
    const order = await getDemoSpotOrder("test-api-key-123456", "test-api-secret-123456789012", "BTCUSDT", "history-order-1");
    if (order.orderId !== "history-order-1" || !paths.includes("/v5/order/history")) {
      throw new Error("order history fallback was not used");
    }
  } finally {
    Object.defineProperty(globalThis, "fetch", { configurable: true, writable: true, value: originalFetch });
  }
});


Deno.test("spot native stop-loss builder uses conditional market sell", () => {
  const order = buildSpotStopLossSell("BTCUSDT", "0.002", "49650", "g2stop123");
  if (order.side !== "Sell" || order.orderType !== "Market" ||
      order.orderFilter !== "StopOrder" || order.marketUnit !== "baseCoin" ||
      order.triggerPrice !== "49650") {
    throw new Error("native stop-loss payload mismatch");
  }
});

Deno.test("spot stop cancellation uses StopOrder filter", async () => {
  const originalFetch = globalThis.fetch;
  const capturedBodies: Record<string, unknown>[] = [];
  Object.defineProperty(globalThis, "fetch", {
    configurable: true,
    writable: true,
    value: async (_input: RequestInfo | URL, init?: RequestInit) => {
      capturedBodies.push(JSON.parse(String(init?.body ?? "{}")) as Record<string, unknown>);
      return new Response(JSON.stringify({
        retCode: 0, retMsg: "OK", result: { orderId: "stop-order-1" },
      }), { status: 200, headers: { "content-type": "application/json" } });
    },
  });
  try {
    await cancelDemoSpotStopOrder("test-api-key-123456", "test-api-secret-123456789012", "BTCUSDT", "stop-order-1");
    const body = capturedBodies[0];
    if (!body || body.orderFilter !== "StopOrder" || body.orderId !== "stop-order-1") {
      throw new Error("stop order cancellation payload mismatch");
    }
  } finally {
    Object.defineProperty(globalThis, "fetch", { configurable: true, writable: true, value: originalFetch });
  }
});
