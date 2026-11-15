export type RiskAssessment = {
  daily_loss_usdt: number;
  drawdown_pct: number;
  stop: boolean;
};

export function assessRunRisk(
  equity: number,
  startEquity: number,
  peakEquity: number,
  dailyLossLimitUsdt: number,
  maxDrawdownPct: number,
): RiskAssessment {
  if (![equity, startEquity, peakEquity, dailyLossLimitUsdt, maxDrawdownPct].every(Number.isFinite) ||
      equity < 0 || startEquity < 0 || peakEquity < 0 ||
      dailyLossLimitUsdt <= 0 || maxDrawdownPct <= 0) {
    throw new Error("Некорректные параметры контроля риска.");
  }
  if (startEquity === 0 || peakEquity === 0) {
    return { daily_loss_usdt: 0, drawdown_pct: 0, stop: true };
  }
  const dailyLoss = Math.max(0, startEquity - equity);
  const drawdown = Math.max(0, (peakEquity - equity) / peakEquity * 100);
  return {
    daily_loss_usdt: dailyLoss,
    drawdown_pct: drawdown,
    stop: dailyLoss >= dailyLossLimitUsdt || drawdown >= maxDrawdownPct,
  };
}

export function positionExitReason(
  lastPrice: number,
  stopPrice: number,
  targetPrice: number,
  forceCloseReason = "",
): string {
  if (forceCloseReason) return forceCloseReason;
  if (![lastPrice, stopPrice, targetPrice].every(Number.isFinite) ||
      lastPrice <= 0 || stopPrice <= 0 || targetPrice <= stopPrice) {
    throw new Error("Некорректные цены для контроля открытой позиции.");
  }
  if (lastPrice <= stopPrice) return "stop-loss 0.7%";
  if (lastPrice >= targetPrice) return "take-profit 1.4%";
  return "";
}

export function shouldEnterLong(input: {
  direction: "UP" | "DOWN" | "NEUTRAL";
  score: number;
  spreadPct: number;
  maxSpreadPct: number;
  positionOpen: boolean;
  pendingEntry: boolean;
  feesResolved: boolean;
  stopping: boolean;
  availableUsdt: number;
  stakeUsdt: number;
  minOrderAmountUsdt: number;
  estimatedBaseQty: number;
  maxMarketOrderQty: number;
}): boolean {
  return input.direction === "UP" && input.score >= 2 &&
    Number.isFinite(input.spreadPct) && input.spreadPct <= input.maxSpreadPct &&
    !input.positionOpen && !input.pendingEntry && input.feesResolved && !input.stopping &&
    input.availableUsdt >= input.stakeUsdt * 1.01 &&
    input.minOrderAmountUsdt <= input.stakeUsdt &&
    input.estimatedBaseQty > 0 && input.estimatedBaseQty <= input.maxMarketOrderQty;
}
