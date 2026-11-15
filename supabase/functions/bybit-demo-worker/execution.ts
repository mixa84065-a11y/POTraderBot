import {
  DemoSpotOrderPayload,
  findDemoSpotOrderByLinkId,
  getDemoSpotExecutions,
  getDemoSpotOrder,
  normalizeDemoOrderStatus,
  normalizeSpotFill,
  placeDemoSpotMarketOrder,
  resolveSpotFeeUsdt,
} from "./bybit.ts";

type Rpc = (name: string, args?: Record<string, unknown>) => Promise<unknown>;

export type PendingDemoOrder = {
  order_id: string;
  position_id: string | null;
  symbol: string;
  side: "Buy" | "Sell";
  purpose: "ENTRY" | "EXIT";
  order_link_id: string;
  exchange_order_id: string | null;
  requested_qty: string | number;
  qty_unit: "baseCoin" | "quoteCoin";
  status: "INTENT" | "SUBMITTED" | "PARTIALLY_FILLED" | "UNKNOWN" | "FILLED" | "CANCELLED" | "REJECTED";
};

function dbStatus(status: ReturnType<typeof normalizeDemoOrderStatus>): PendingDemoOrder["status"] {
  switch (status) {
    case "NEW": return "SUBMITTED";
    case "PARTIALLY_FILLED": return "PARTIALLY_FILLED";
    case "FILLED": return "FILLED";
    case "CANCELLED": return "CANCELLED";
    case "REJECTED": return "REJECTED";
    default: return "UNKNOWN";
  }
}

/**
 * Submits at most once. Before the network call the DB state becomes UNKNOWN,
 * so a crash or timeout can only trigger a lookup by orderLinkId, never a blind retry.
 */
export async function submitOrReconcileDemoOrder(input: {
  apiKey: string;
  apiSecret: string;
  order: PendingDemoOrder;
  payload: DemoSpotOrderPayload;
  rpc: Rpc;
}) {
  const { apiKey, apiSecret, order, payload, rpc } = input;
  if (payload.orderLinkId !== order.order_link_id ||
      payload.symbol !== order.symbol || payload.side !== order.side ||
      payload.marketUnit !== order.qty_unit) {
    throw new Error("Заявка не совпадает с сохранённым серверным намерением.");
  }

  if (order.status === "REJECTED") {
    await rpc("bybit_demo_worker_set_order_state", {
      p_order_id: order.order_id, p_exchange_order_id: order.exchange_order_id, p_status: "REJECTED",
    });
    return {
      orderId: order.exchange_order_id,
      orderLinkId: order.order_link_id,
      status: "REJECTED" as const,
      filledQty: 0,
      filledValueUsdt: 0,
      averageFillPrice: 0,
      feeUsdt: 0,
      feeConversionComplete: true,
      executionCount: 0,
    };
  }

  let exchangeOrderId = order.exchange_order_id;
  let exchangeOrder: Awaited<ReturnType<typeof getDemoSpotOrder>> | null = null;

  if (order.status === "INTENT") {
    const prior = await findDemoSpotOrderByLinkId(apiKey, apiSecret, order.symbol, order.order_link_id);
    if (prior) {
      exchangeOrderId = prior.orderId;
      await rpc("bybit_demo_worker_set_order_state", {
        p_order_id: order.order_id, p_exchange_order_id: exchangeOrderId,
        p_status: dbStatus(normalizeDemoOrderStatus(prior.orderStatus)),
      });
    } else {
      // Persist uncertainty before crossing the network boundary. If the process dies
      // after this point, recovery searches Bybit by client ID and does not resubmit.
      await rpc("bybit_demo_worker_set_order_state", {
        p_order_id: order.order_id, p_exchange_order_id: null, p_status: "UNKNOWN",
      });
      try {
        const accepted = await placeDemoSpotMarketOrder(apiKey, apiSecret, payload);
        exchangeOrderId = accepted.orderId;
        await rpc("bybit_demo_worker_set_order_state", {
          p_order_id: order.order_id, p_exchange_order_id: exchangeOrderId, p_status: "SUBMITTED",
        });
      } catch (error) {
        throw new Error(
          "Результат отправки заявки неоднозначен; повторная отправка заблокирована. " +
          (error instanceof Error ? error.message : "Unknown exchange error"),
        );
      }
    }
  } else if (!exchangeOrderId) {
    const found = await findDemoSpotOrderByLinkId(apiKey, apiSecret, order.symbol, order.order_link_id);
    if (!found) {
      throw new Error("Заявка UNKNOWN не найдена на бирже. Автоповтор запрещён во избежание дубля.");
    }
    exchangeOrderId = found.orderId;
  }

  if (!exchangeOrderId) {
    const found = await findDemoSpotOrderByLinkId(apiKey, apiSecret, order.symbol, order.order_link_id);
    if (!found) throw new Error("Bybit пока не показывает заявку; она остаётся UNKNOWN.");
    exchangeOrderId = found.orderId;
  }

  exchangeOrder = await getDemoSpotOrder(apiKey, apiSecret, order.symbol, exchangeOrderId);
  const normalizedStatus = normalizeDemoOrderStatus(exchangeOrder.orderStatus);
  const persistedStatus = dbStatus(normalizedStatus);
  await rpc("bybit_demo_worker_set_order_state", {
    p_order_id: order.order_id, p_exchange_order_id: exchangeOrderId, p_status: persistedStatus,
  });

  const executions = await getDemoSpotExecutions(apiKey, apiSecret, order.symbol, exchangeOrderId);
  if (normalizedStatus === "FILLED" && executions.length === 0) {
    throw new Error("Bybit пометил заявку исполненной, но история исполнений пуста; позиция не создаётся.");
  }

  let filledQty = 0;
  let filledValueUsdt = 0;
  let feeUsdt = 0;
  let feeConversionComplete = true;
  for (const execution of executions) {
    const fill = normalizeSpotFill(execution);
    let convertedFee: number | null = fill.feeUsdt;
    if (convertedFee === null) {
      try {
        convertedFee = await resolveSpotFeeUsdt(fill);
      } catch {
        feeConversionComplete = false;
      }
    }
    if (convertedFee === null) feeConversionComplete = false;
    const fillStatus = normalizedStatus === "NEW" ? "PARTIALLY_FILLED" : persistedStatus;
    const stored = await rpc("bybit_demo_worker_record_fill", {
      p_order_id: order.order_id,
      p_exec_id: fill.execId,
      p_qty: fill.qty,
      p_price: fill.price,
      p_value_usdt: fill.valueUsdt,
      p_fee_amount: fill.feeAmount,
      p_fee_currency: fill.feeCurrency,
      p_fee_usdt: convertedFee,
      p_executed_at: fill.executedAt,
      p_order_status: fillStatus,
    }) as { fee_conversion_complete?: boolean };
    filledQty += fill.qty;
    filledValueUsdt += fill.valueUsdt;
    if (convertedFee !== null) feeUsdt += convertedFee;
    if (stored?.fee_conversion_complete === false) feeConversionComplete = false;
  }

  return {
    orderId: exchangeOrderId,
    orderLinkId: order.order_link_id,
    status: persistedStatus,
    filledQty,
    filledValueUsdt,
    averageFillPrice: filledQty > 0 ? filledValueUsdt / filledQty : 0,
    feeUsdt,
    feeConversionComplete,
    executionCount: executions.length,
  };
}
