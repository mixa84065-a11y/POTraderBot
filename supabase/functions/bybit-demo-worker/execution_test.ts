import { buildSpotMarketBuy, buildSpotStopLossSell } from "./bybit.ts";
import { submitOrReconcileDemoOrder, type PendingDemoOrder } from "./execution.ts";

const baseOrder: PendingDemoOrder = {
  order_id: "00000000-0000-4000-8000-000000000001",
  position_id: null,
  symbol: "BTCUSDT",
  side: "Buy",
  purpose: "ENTRY",
  order_link_id: "g2test12345678901234567890123456",
  exchange_order_id: null,
  requested_qty: "100",
  qty_unit: "quoteCoin",
  status: "INTENT",
};

function json(data: unknown) {
  return new Response(JSON.stringify(data), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}

Deno.test("INTENT order is submitted once and fills are persisted idempotently", async () => {
  const originalFetch = globalThis.fetch;
  const requests: Array<{ method: string; path: string; query: URLSearchParams }> = [];
  Object.defineProperty(globalThis, "fetch", {
    configurable: true,
    writable: true,
    value: async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(typeof input === "string" || input instanceof URL ? input.toString() : input.url);
      const method = init?.method ?? "GET";
      requests.push({ method, path: url.pathname, query: url.searchParams });
      if (url.pathname === "/v5/order/realtime" && url.searchParams.has("orderLinkId")) {
        return json({ retCode: 0, retMsg: "OK", result: { list: [] } });
      }
      if (url.pathname === "/v5/order/history" && url.searchParams.has("orderLinkId")) {
        return json({ retCode: 0, retMsg: "OK", result: { list: [] } });
      }
      if (url.pathname === "/v5/order/create" && method === "POST") {
        return json({ retCode: 0, retMsg: "OK", result: { orderId: "exchange-order-1", orderLinkId: baseOrder.order_link_id } });
      }
      if (url.pathname === "/v5/order/realtime" && url.searchParams.has("orderId")) {
        return json({ retCode: 0, retMsg: "OK", result: { list: [{
          orderId: "exchange-order-1", orderLinkId: baseOrder.order_link_id,
          orderStatus: "Filled", cumExecQty: "0.002", cumExecValue: "100",
          avgPrice: "50000", leavesQty: "0",
        }] } });
      }
      if (url.pathname === "/v5/execution/list") {
        return json({ retCode: 0, retMsg: "OK", result: { list: [{
          execId: "fill-1", orderId: "exchange-order-1", symbol: "BTCUSDT",
          side: "Buy", execQty: "0.002", execPrice: "50000", execValue: "100",
          execFee: "0.1", feeCurrency: "USDT", execTime: "1800000000000",
        }], nextPageCursor: "" } });
      }
      throw new Error("Unexpected mocked Bybit request: " + method + " " + url.toString());
    },
  });

  const rpcCalls: Array<{ name: string; args: Record<string, unknown> }> = [];
  try {
    const result = await submitOrReconcileDemoOrder({
      apiKey: "test-api-key-123456",
      apiSecret: "test-api-secret-123456789012",
      order: baseOrder,
      payload: buildSpotMarketBuy("BTCUSDT", "100", baseOrder.order_link_id),
      rpc: async (name, args = {}) => {
        rpcCalls.push({ name, args });
        if (name === "bybit_demo_worker_record_fill") return { fee_conversion_complete: true };
        return null;
      },
    });
    if (requests.filter((r) => r.path === "/v5/order/create").length !== 1) {
      throw new Error("Expected exactly one order submission");
    }
    if (result.status !== "FILLED" || result.filledQty !== 0.002 ||
        result.filledValueUsdt !== 100 || result.feeUsdt !== 0.1 ||
        result.executionCount !== 1 || !result.feeConversionComplete) {
      throw new Error("Unexpected reconciliation result: " + JSON.stringify(result));
    }
    if (!rpcCalls.some((call) => call.name === "bybit_demo_worker_record_fill")) {
      throw new Error("Execution fill was not persisted");
    }
    if (!rpcCalls.some((call) => call.name === "bybit_demo_worker_mark_executions_complete")) {
      throw new Error("Execution completeness watermark was not persisted");
    }
  } finally {
    Object.defineProperty(globalThis, "fetch", { configurable: true, writable: true, value: originalFetch });
  }
});

Deno.test("UNKNOWN order is never blindly resubmitted when Bybit cannot find it", async () => {
  const originalFetch = globalThis.fetch;
  let createCount = 0;
  Object.defineProperty(globalThis, "fetch", {
    configurable: true,
    writable: true,
    value: async (input: RequestInfo | URL) => {
      const url = new URL(typeof input === "string" || input instanceof URL ? input.toString() : input.url);
      if (url.pathname === "/v5/order/realtime" || url.pathname === "/v5/order/history") {
        return json({ retCode: 0, retMsg: "OK", result: { list: [] } });
      }
      if (url.pathname === "/v5/order/create") createCount++;
      throw new Error("Unexpected request");
    },
  });
  try {
    let rejected = false;
    try {
      await submitOrReconcileDemoOrder({
        apiKey: "test-api-key-123456",
        apiSecret: "test-api-secret-123456789012",
        order: { ...baseOrder, status: "UNKNOWN" },
        payload: buildSpotMarketBuy("BTCUSDT", "100", baseOrder.order_link_id),
        rpc: async () => null,
      });
    } catch {
      rejected = true;
    }
    if (!rejected || createCount !== 0) {
      throw new Error("UNKNOWN order was retried without exchange confirmation");
    }
  } finally {
    Object.defineProperty(globalThis, "fetch", { configurable: true, writable: true, value: originalFetch });
  }
});


Deno.test("REJECTED order is acknowledged in DB and never resubmitted", async () => {
  const originalFetch = globalThis.fetch;
  let exchangeRequests = 0;
  Object.defineProperty(globalThis, "fetch", {
    configurable: true,
    writable: true,
    value: async () => {
      exchangeRequests++;
      throw new Error("Rejected order must not call Bybit");
    },
  });
  const rpcCalls: string[] = [];
  try {
    const result = await submitOrReconcileDemoOrder({
      apiKey: "test-api-key-123456",
      apiSecret: "test-api-secret-123456789012",
      order: { ...baseOrder, status: "REJECTED" },
      payload: buildSpotMarketBuy("BTCUSDT", "100", baseOrder.order_link_id),
      rpc: async (name) => { rpcCalls.push(name); return null; },
    });
    if (result.status !== "REJECTED" || exchangeRequests !== 0 ||
        !rpcCalls.includes("bybit_demo_worker_set_order_state")) {
      throw new Error("Rejected order was not safely finalized");
    }
  } finally {
    Object.defineProperty(globalThis, "fetch", { configurable: true, writable: true, value: originalFetch });
  }
});


Deno.test("native protection payload must match the persisted trigger price", async () => {
  let rpcCalls = 0;
  const order: PendingDemoOrder = {
    ...baseOrder,
    purpose: "PROTECTION",
    side: "Sell",
    qty_unit: "baseCoin",
    requested_qty: "0.002",
    order_link_id: "g2stop1234567890123456789012345",
    trigger_price: "49650",
    status: "REJECTED",
  };
  let rejected = false;
  try {
    await submitOrReconcileDemoOrder({
      apiKey: "test-api-key-123456",
      apiSecret: "test-api-secret-123456789012",
      order,
      payload: buildSpotStopLossSell("BTCUSDT", "0.002", "49500", order.order_link_id),
      rpc: async () => { rpcCalls++; return null; },
    });
  } catch {
    rejected = true;
  }
  if (!rejected || rpcCalls !== 0) {
    throw new Error("mismatched native stop trigger was accepted");
  }
});


Deno.test("native Spot stop order is submitted with persisted trigger and reconciled while untriggered", async () => {
  const originalFetch = globalThis.fetch;
  const requests: Array<{ path: string; method: string; body: Record<string, unknown> }> = [];
  const order: PendingDemoOrder = {
    ...baseOrder,
    purpose: "PROTECTION",
    side: "Sell",
    qty_unit: "baseCoin",
    requested_qty: "0.002",
    order_link_id: "g2stop1234567890123456789012345",
    trigger_price: "49650",
    status: "INTENT",
  };
  Object.defineProperty(globalThis, "fetch", {
    configurable: true,
    writable: true,
    value: async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(typeof input === "string" || input instanceof URL ? input.toString() : input.url);
      const method = init?.method ?? "GET";
      const body = init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : {};
      requests.push({ path: url.pathname, method, body });
      if (url.pathname === "/v5/order/realtime" && url.searchParams.has("orderLinkId")) {
        return json({ retCode: 0, retMsg: "OK", result: { list: [] } });
      }
      if (url.pathname === "/v5/order/history") {
        return json({ retCode: 0, retMsg: "OK", result: { list: [] } });
      }
      if (url.pathname === "/v5/order/create") {
        return json({ retCode: 0, retMsg: "OK", result: {
          orderId: "exchange-stop-1", orderLinkId: order.order_link_id,
        } });
      }
      if (url.pathname === "/v5/order/realtime" && url.searchParams.has("orderId")) {
        return json({ retCode: 0, retMsg: "OK", result: { list: [{
          orderId: "exchange-stop-1", orderLinkId: order.order_link_id,
          orderStatus: "Untriggered", cumExecQty: "0", cumExecValue: "0",
          avgPrice: "0", leavesQty: "0.002",
        }] } });
      }
      if (url.pathname === "/v5/execution/list") {
        return json({ retCode: 0, retMsg: "OK", result: { list: [], nextPageCursor: "" } });
      }
      throw new Error("Unexpected mocked request " + url.pathname);
    },
  });
  const rpcCalls: string[] = [];
  try {
    const result = await submitOrReconcileDemoOrder({
      apiKey: "test-api-key-123456",
      apiSecret: "test-api-secret-123456789012",
      order,
      payload: buildSpotStopLossSell("BTCUSDT", "0.002", "49650", order.order_link_id),
      rpc: async (name) => { rpcCalls.push(name); return null; },
    });
    const create = requests.find((item) => item.path === "/v5/order/create");
    if (!create || create.body.orderFilter !== "StopOrder" ||
        create.body.triggerPrice !== "49650" || create.body.marketUnit !== "baseCoin" ||
        result.status !== "SUBMITTED" || result.filledQty !== 0 ||
        !rpcCalls.includes("bybit_demo_worker_mark_executions_complete")) {
      throw new Error("native spot stop order was not safely reconciled");
    }
  } finally {
    Object.defineProperty(globalThis, "fetch", { configurable: true, writable: true, value: originalFetch });
  }
});

Deno.test("terminal order is not marked reconciled while exchange execution quantity is missing", async () => {
  const originalFetch = globalThis.fetch;
  Object.defineProperty(globalThis, "fetch", {
    configurable: true,
    writable: true,
    value: async (input: RequestInfo | URL) => {
      const url = new URL(typeof input === "string" || input instanceof URL ? input.toString() : input.url);
      if (url.pathname === "/v5/order/realtime" && url.searchParams.has("orderId")) {
        return json({ retCode: 0, retMsg: "OK", result: { list: [{
          orderId: "exchange-order-2", orderLinkId: baseOrder.order_link_id,
          orderStatus: "Filled", cumExecQty: "0.003", cumExecValue: "150",
          avgPrice: "50000", leavesQty: "0",
        }] } });
      }
      if (url.pathname === "/v5/execution/list") {
        return json({ retCode: 0, retMsg: "OK", result: { list: [{
          execId: "fill-partial", orderId: "exchange-order-2", symbol: "BTCUSDT",
          side: "Buy", execQty: "0.002", execPrice: "50000", execValue: "100",
          execFee: "0.1", feeCurrency: "USDT", execTime: "1800000000000",
        }], nextPageCursor: "" } });
      }
      throw new Error("Unexpected mocked request " + url.pathname);
    },
  });
  const rpcCalls: string[] = [];
  try {
    let rejected = false;
    try {
      await submitOrReconcileDemoOrder({
        apiKey: "test-api-key-123456",
        apiSecret: "test-api-secret-123456789012",
        order: { ...baseOrder, status: "SUBMITTED", exchange_order_id: "exchange-order-2" },
        payload: buildSpotMarketBuy("BTCUSDT", "100", baseOrder.order_link_id),
        rpc: async (name) => {
          rpcCalls.push(name);
          if (name === "bybit_demo_worker_record_fill") return { fee_conversion_complete: true };
          return null;
        },
      });
    } catch {
      rejected = true;
    }
    if (!rejected || rpcCalls.includes("bybit_demo_worker_mark_executions_complete")) {
      throw new Error("incomplete execution list was marked complete");
    }
  } finally {
    Object.defineProperty(globalThis, "fetch", { configurable: true, writable: true, value: originalFetch });
  }
});
