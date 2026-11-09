package com.potraderbot;

import java.util.ArrayList;
import java.util.List;

public class TradeEngine {
    private final MarketAnalyzer analyzer = new MarketAnalyzer();
    private final List<Trade> history = new ArrayList<>();
    private int consecutiveLosses = 0;
    private int tradesToday = 0;

    public MarketAnalyzer.Signal evaluate(List<Candle> candles) {
        if (tradesToday >= 10 || consecutiveLosses >= 2) return MarketAnalyzer.Signal.WAIT;
        return analyzer.analyze(candles);
    }

    // Simulation only: no broker connection and no real-money order placement.
    public void recordSimulatedTrade(String direction, long time, int expiry, double price, boolean win) {
        if (tradesToday >= 10 || consecutiveLosses >= 2) return;
        history.add(new Trade(direction, time, expiry, price, win ? "WIN" : "LOSS"));
        tradesToday++;
        consecutiveLosses = win ? 0 : consecutiveLosses + 1;
    }

    public List<Trade> getHistory() { return new ArrayList<>(history); }
    public int getTradesToday() { return tradesToday; }
    public boolean isPaused() { return tradesToday >= 10 || consecutiveLosses >= 2; }
}
