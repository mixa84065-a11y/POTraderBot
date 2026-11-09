package com.potraderbot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Calendar;

/** Risk guard and paper-trade ledger only. It never sends broker orders. */
public final class TradeEngine {
    public static final int MAX_TRADES_PER_DAY = 10;
    public static final int MAX_CONSECUTIVE_LOSSES = 2;

    public static final class Result {
        public final int dayKey;
        public final boolean win;
        Result(int dayKey, boolean win) { this.dayKey = dayKey; this.win = win; }
    }

    private int day = currentDayKey();
    private int tradesToday;
    private int consecutiveLosses;
    private int wins;
    private int losses;
    private final List<Result> history = new ArrayList<>();

    public synchronized boolean canOpenPaperTrade() {
        refreshDay();
        return tradesToday < MAX_TRADES_PER_DAY && consecutiveLosses < MAX_CONSECUTIVE_LOSSES;
    }

    public synchronized void recordPaperResult(boolean win) {
        refreshDay();
        if (!canOpenPaperTrade()) throw new IllegalStateException("Дневной лимит или стоп-правило достигнуты.");
        tradesToday++;
        if (win) {
            wins++;
            consecutiveLosses = 0;
        } else {
            losses++;
            consecutiveLosses++;
        }
        history.add(new Result(day, win));
    }

    public synchronized int getTradesToday() { refreshDay(); return tradesToday; }
    public synchronized int getWins() { return wins; }
    public synchronized int getLosses() { return losses; }
    public synchronized int getConsecutiveLosses() { refreshDay(); return consecutiveLosses; }
    public synchronized List<Result> getHistory() { return Collections.unmodifiableList(new ArrayList<>(history)); }

    private void refreshDay() {
        int today = currentDayKey();
        if (today != day) {
            day = today;
            tradesToday = 0;
            consecutiveLosses = 0;
        }
    }

    private static int currentDayKey() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
    }
}
