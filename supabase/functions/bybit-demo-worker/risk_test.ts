import { assessRunRisk, positionExitReason, shouldEnterLong } from "./risk.ts";

Deno.test("daily loss and peak drawdown independently stop the run", () => {
  const daily = assessRunRisk(790, 1000, 1050, 200, 5);
  if (!daily.stop || daily.daily_loss_usdt !== 210) throw new Error("daily loss limit did not stop");
  const drawdown = assessRunRisk(990, 1000, 1100, 200, 5);
  if (!drawdown.stop || Math.abs(drawdown.drawdown_pct - 10) > 1e-9) throw new Error("drawdown limit did not stop");
  const safe = assessRunRisk(995, 1000, 1010, 200, 5);
  if (safe.stop) throw new Error("safe equity incorrectly stopped");
});

Deno.test("spot position exit reasons respect stop, target and force close", () => {
  if (positionExitReason(99, 99.3, 101.4) !== "stop-loss 0.7%") throw new Error("stop-loss trigger failed");
  if (positionExitReason(102, 99.3, 101.4) !== "take-profit 1.4%") throw new Error("take-profit trigger failed");
  if (positionExitReason(100, 99.3, 101.4) !== "") throw new Error("unexpected exit trigger");
  if (positionExitReason(100, 99.3, 101.4, "run stop") !== "run stop") throw new Error("force close failed");
});

Deno.test("spot long entry gate rejects DOWN, stale risk, wide spread and low balance", () => {
  const base = {
    direction: "UP" as const, score: 3, spreadPct: 0.05, maxSpreadPct: 0.15,
    positionOpen: false, pendingEntry: false, feesResolved: true, stopping: false,
    availableUsdt: 500, stakeUsdt: 100, minOrderAmountUsdt: 5,
    estimatedBaseQty: 0.002, maxMarketOrderQty: 10,
  };
  if (!shouldEnterLong(base)) throw new Error("valid long entry rejected");
  if (shouldEnterLong({ ...base, direction: "DOWN" })) throw new Error("DOWN signal opened a spot short");
  if (shouldEnterLong({ ...base, spreadPct: 0.2 })) throw new Error("wide spread accepted");
  if (shouldEnterLong({ ...base, feesResolved: false })) throw new Error("unresolved fees accepted");
  if (shouldEnterLong({ ...base, availableUsdt: 50 })) throw new Error("insufficient balance accepted");
});

Deno.test("missing or zero equity baseline fails closed", () => {
  const result = assessRunRisk(0, 0, 0, 200, 5);
  if (!result.stop) throw new Error("zero baseline did not stop the run");
});
