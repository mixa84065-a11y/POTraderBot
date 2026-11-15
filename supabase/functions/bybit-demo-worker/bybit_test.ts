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
