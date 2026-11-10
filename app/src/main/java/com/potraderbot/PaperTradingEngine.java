package com.potraderbot;

import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Local paper-trading simulator. It has no broker integration and never sends orders.
 * Stake, payout and expiry are supplied by the on-screen demo risk calculator.
 */
public final class PaperTradingEngine {
    private static final double START_BALANCE = 20_000.0;
    private static final int MAX_TRADES_PER_DAY = 5;
    private static final int MAX_CONSECUTIVE_LOSSES = 3;

    private final SharedPreferences preferences;
    private double balance;
    private int tradesToday;
    private int consecutiveLosses;
    private int wins;
    private int losses;
    private String dayKey;
    private Trade activeTrade;
    private String lastEvent = "Автосимуляция выключена.";

    private static final class Trade {
        final MarketAnalyzer.Signal direction;
        final long entryTime;
        final long expiryTime;
        final double entryPrice;
        final double stake;
        final double payoutPercent;
        final String symbol;

        Trade(MarketAnalyzer.Signal direction, long entryTime, long expiryTime,
              double entryPrice, double stake, double payoutPercent, String symbol) {
            this.direction = direction;
            this.entryTime = entryTime;
            this.expiryTime = expiryTime;
            this.entryPrice = entryPrice;
            this.stake = stake;
            this.payoutPercent = payoutPercent;
            this.symbol = symbol == null ? "" : symbol;
        }
    }

    public PaperTradingEngine(SharedPreferences preferences) {
        this.preferences = preferences;
        resetDayIfNeeded();
        boolean balanceV2Initialized = preferences.getBoolean("paper_balance_v2_initialized", false);
        if (!balanceV2Initialized) {
            // One-time migration: align the existing demo account with the requested 20,000 start.
            balance = START_BALANCE;
            preferences.edit().putFloat("paper_balance", (float) START_BALANCE)
                    .putBoolean("paper_balance_v2_initialized", true).apply();
        } else {
            balance = preferences.getFloat("paper_balance", (float) START_BALANCE);
        }
        tradesToday = preferences.getInt("paper_trades_today", 0);
        consecutiveLosses = preferences.getInt("paper_consecutive_losses", 0);
        wins = preferences.getInt("paper_wins", 0);
        losses = preferences.getInt("paper_losses", 0);
        restoreActiveTrade();
        lastEvent = preferences.getString("paper_last_event", "Автосимуляция выключена.");
    }

    public synchronized double getVirtualBalance() {
        return balance;
    }

    public synchronized String process(List<Candle> candles, MarketAnalyzer.Signal signal,
                                       double stake, double payoutPercent, int expiryMinutes, String symbol) {
        resetDayIfNeeded();
        if (candles == null || candles.isEmpty()) return lastEvent;
        settleIfExpired(candles, symbol);
        if (activeTrade != null && !activeTrade.symbol.equalsIgnoreCase(symbol == null ? "" : symbol)) return lastEvent;
        if (!Double.isFinite(stake) || stake <= 0 || stake > balance
                || !Double.isFinite(payoutPercent) || payoutPercent <= 0 || payoutPercent > 1000
                || expiryMinutes < 3 || expiryMinutes > 5) {
            lastEvent = "Сделка не открыта: проверьте виртуальную ставку, выплату и экспирацию 3–5 минут.";
            persistEvent();
            return lastEvent;
        }
        Candle last = candles.get(candles.size() - 1);
        String normalizedSymbol = symbol == null ? "" : symbol.trim().toUpperCase(Locale.US);
        String candleKey = "paper_last_processed_candle_" + normalizedSymbol.replaceAll("[^A-Z0-9._-]", "_");
        long lastProcessedForSymbol = preferences.getLong(candleKey, -1L);
        if (last.time == lastProcessedForSymbol) return lastEvent;
        preferences.edit().putLong(candleKey, last.time).apply();

        if (activeTrade != null) return lastEvent;
        if (tradesToday >= MAX_TRADES_PER_DAY) {
            lastEvent = "Лимит симуляции на сегодня достигнут (" + MAX_TRADES_PER_DAY + " сделок).";
            persistEvent();
            return lastEvent;
        }
        if (consecutiveLosses >= MAX_CONSECUTIVE_LOSSES) {
            lastEvent = "Пауза: достигнут лимит " + MAX_CONSECUTIVE_LOSSES + " убытков подряд.";
            persistEvent();
            return lastEvent;
        }
        if (stake > balance) {
            lastEvent = "Симуляция остановлена: виртуального баланса недостаточно для выбранного риска.";
            persistEvent();
            return lastEvent;
        }
        if (signal != MarketAnalyzer.Signal.CALL && signal != MarketAnalyzer.Signal.PUT) {
            lastEvent = "Ожидание сигнала. Виртуальная сделка не открыта.";
            persistEvent();
            return lastEvent;
        }

        long entryTime = last.time + 60_000L;
        long expiryTime = entryTime + expiryMinutes * 60_000L;
        activeTrade = new Trade(signal, entryTime, expiryTime, last.close, stake, payoutPercent, symbol);
        tradesToday++;
        save();
        lastEvent = "ОТКРЫТА ВИРТУАЛЬНАЯ " + (signal == MarketAnalyzer.Signal.CALL ? "ВВЕРХ" : "ВНИЗ")
                + " • ставка " + format(stake) + " • выплата " + format(payoutPercent)
                + "% • вход " + format(last.close) + " • экспирация " + expiryMinutes + " мин.";
        persistEvent();
        return lastEvent;
    }

    private void settleIfExpired(List<Candle> candles, String symbol) {
        if (activeTrade == null || !activeTrade.symbol.equalsIgnoreCase(symbol == null ? "" : symbol)) return;
        Candle expiryCandle = null;
        for (Candle candle : candles) {
            if (candle.time + 60_000L >= activeTrade.expiryTime) {
                expiryCandle = candle;
                break;
            }
        }
        if (expiryCandle == null) return;

        double exitPrice = expiryCandle.close;
        boolean tie = Double.compare(exitPrice, activeTrade.entryPrice) == 0;
        boolean win = activeTrade.direction == MarketAnalyzer.Signal.CALL
                ? exitPrice > activeTrade.entryPrice : exitPrice < activeTrade.entryPrice;
        if (win) {
            double profit = activeTrade.stake * activeTrade.payoutPercent / 100.0;
            balance += profit;
            wins++;
            consecutiveLosses = 0;
            lastEvent = "ВИРТУАЛЬНАЯ СДЕЛКА В ПЛЮС • +" + format(profit)
                    + " • цена закрытия " + format(exitPrice);
        } else if (tie) {
            lastEvent = "ВИРТУАЛЬНАЯ СДЕЛКА В НОЛЬ • возврат ставки " + format(activeTrade.stake);
        } else {
            balance -= activeTrade.stake;
            losses++;
            consecutiveLosses++;
            lastEvent = "ВИРТУАЛЬНАЯ СДЕЛКА В МИНУС • -" + format(activeTrade.stake)
                    + " • цена закрытия " + format(exitPrice);
        }
        activeTrade = null;
        save();
        persistEvent();
    }

    public synchronized String getSummary() {
        SimpleDateFormat expiryFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);
        expiryFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        String active = activeTrade == null ? "Нет активной виртуальной сделки."
                : "Активна " + (activeTrade.direction == MarketAnalyzer.Signal.CALL ? "ВВЕРХ" : "ВНИЗ")
                + " • вход " + format(activeTrade.entryPrice) + " • ставка " + format(activeTrade.stake)
                + " • актив " + activeTrade.symbol + " • экспирация " + expiryFormat.format(new Date(activeTrade.expiryTime)) + " UTC.";
        String history = preferences.getString("paper_log", "");
        return "ДЕМО-СИМУЛЯЦИЯ (только виртуально, без связи с брокером)\n"
                + "Виртуальный баланс: " + format(balance) + "\n"
                + "Сделок сегодня: " + tradesToday + "/" + MAX_TRADES_PER_DAY
                + " • плюсовых: " + wins + " • минусовых: " + losses + "\n"
                + "Убытков подряд: " + consecutiveLosses + "/" + MAX_CONSECUTIVE_LOSSES + "\n"
                + active + "\nПоследнее событие: " + lastEvent
                + (history.isEmpty() ? "" : "\n\nПОСЛЕДНИЕ СОБЫТИЯ:\n" + history);
    }

    public synchronized void resetSimulation() {
        balance = START_BALANCE;
        tradesToday = 0;
        consecutiveLosses = 0;
        wins = 0;
        losses = 0;
        activeTrade = null;
        SharedPreferences.Editor resetEditor = preferences.edit();
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith("paper_last_processed_candle_")) resetEditor.remove(key);
        }
        resetEditor.apply();
        lastEvent = "Виртуальный счёт сброшен.";
        preferences.edit().remove("paper_active_direction").remove("paper_active_entry_time")
                .remove("paper_active_expiry_time").remove("paper_active_entry_price")
                .remove("paper_active_stake").remove("paper_active_payout")
                .putString("paper_log", "").apply();
        save();
        persistEvent();
    }

    private void restoreActiveTrade() {
        String direction = preferences.getString("paper_active_direction", "");
        if (direction.isEmpty()) return;
        try {
            MarketAnalyzer.Signal signal = MarketAnalyzer.Signal.valueOf(direction);
            activeTrade = new Trade(signal,
                    preferences.getLong("paper_active_entry_time", 0L),
                    preferences.getLong("paper_active_expiry_time", 0L),
                    Double.longBitsToDouble(preferences.getLong("paper_active_entry_price", 0L)),
                    Double.longBitsToDouble(preferences.getLong("paper_active_stake", 0L)),
                    Double.longBitsToDouble(preferences.getLong("paper_active_payout", 0L)),
                    preferences.getString("paper_active_symbol", ""));
        } catch (Exception ignored) {
            activeTrade = null;
            clearActiveTrade();
        }
    }

    private void resetDayIfNeeded() {
        String today = utcDay();
        String savedDay = preferences.getString("paper_day", "");
        if (!today.equals(savedDay)) {
            dayKey = today;
            tradesToday = 0;
            consecutiveLosses = 0;
            preferences.edit().putString("paper_day", today)
                    .putInt("paper_trades_today", 0)
                    .putInt("paper_consecutive_losses", 0).apply();
        } else {
            dayKey = today;
        }
    }

    private void save() {
        SharedPreferences.Editor editor = preferences.edit()
                .putFloat("paper_balance", (float) balance)
                .putInt("paper_trades_today", tradesToday)
                .putInt("paper_consecutive_losses", consecutiveLosses)
                .putInt("paper_wins", wins)
                .putInt("paper_losses", losses)
                .putString("paper_day", dayKey);
        if (activeTrade == null) {
            editor.remove("paper_active_direction").remove("paper_active_entry_time")
                    .remove("paper_active_expiry_time").remove("paper_active_entry_price")
                    .remove("paper_active_stake").remove("paper_active_payout").remove("paper_active_symbol");
        } else {
            editor.putString("paper_active_direction", activeTrade.direction.name())
                    .putLong("paper_active_entry_time", activeTrade.entryTime)
                    .putLong("paper_active_expiry_time", activeTrade.expiryTime)
                    .putLong("paper_active_entry_price", Double.doubleToRawLongBits(activeTrade.entryPrice))
                    .putLong("paper_active_stake", Double.doubleToRawLongBits(activeTrade.stake))
                    .putLong("paper_active_payout", Double.doubleToRawLongBits(activeTrade.payoutPercent))
                    .putString("paper_active_symbol", activeTrade.symbol);
        }
        editor.apply();
    }

    private void clearActiveTrade() {
        preferences.edit().remove("paper_active_direction").remove("paper_active_entry_time")
                .remove("paper_active_expiry_time").remove("paper_active_entry_price")
                .remove("paper_active_stake").remove("paper_active_payout").remove("paper_active_symbol").apply();
    }

    private void persistEvent() {
        String old = preferences.getString("paper_log", "");
        String updated = (old.isEmpty() ? "" : old + "\n") + lastEvent;
        String[] lines = updated.split("\\n");
        StringBuilder limited = new StringBuilder();
        for (int i = Math.max(0, lines.length - 8); i < lines.length; i++) {
            if (limited.length() > 0) limited.append("\n");
            limited.append(lines[i]);
        }
        preferences.edit().putString("paper_last_event", lastEvent)
                .putString("paper_log", limited.toString()).apply();
    }

    private String utcDay() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private String format(double value) {
        return String.format(Locale.US, "%.2f", value);
    }
}
