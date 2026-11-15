import {
  buildSpotMarketBuy,
  buildSpotMarketSell,
  floorToStep,
  makeOrderLinkId,
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

Deno.test("order link IDs are deterministic and bounded", () => {
  const a = makeOrderLinkId("12345678-1234-1234-1234-123456789abc", "BTCUSDT", "Buy", 1_800_000_000_000);
  const b = makeOrderLinkId("12345678-1234-1234-1234-123456789abc", "BTCUSDT", "Buy", 1_800_000_000_000);
  if (a !== b || a.length > 36) throw new Error("orderLinkId stability/length check failed");
});
