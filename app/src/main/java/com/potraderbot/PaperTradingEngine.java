package com.potraderbot;

import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Local paper-trading simulator. It never sends orders to a broker.
 * Uses the external candle feed and a fixed illustrative 80% payout.
 */
public final class PaperTradingEngine {
    private static final double START_BALANCE = 10_000.0;
    private static final double STAKE = 100.0;
    private static final double SIMULATED_PAYOUT = 0.80;
    private static final int MAX_TRADES_PER_DAY = 5;
    private static final int MAX_CONSECUTIVE_LOSSES = 3;
    private static final long EXPIRY_MILLIS = 3L * 60L * 1000L;

    private final SharedPreferences preferences;
    private double balance;
    private int tradesToday;
    private int consecutiveLosses;
    private int wins;
    private int losses;
    private String dayKey;
    private Trade activeTrade;
    private long lastProcessedCandle = -1L;
    private String lastEvent = "Автосимуляция выключена.";

    private static final class Trade {
        final MarketAnalyzer.Signal direction;
        final long entryTime;
        final long expiryTime;
        final double entryPrice;

        Trade(MarketAnalyzer.Signal direction, long entryTime, long expiryTime, double entryPrice) {
            this.direction = direction;
            this.entryTime = entryTime;
            this.expiryTime = expiryTime;
            this.entryPrice = entryPrice;
        }
    }

    public PaperTradingEngine(SharedPreferences preferences) {
        this.preferences = preferences;
        resetDayIfNeeded();
        balance = preferences.getFloat("paper_balance", (float) START_BALANCE);
        tradesToday = preferences.getInt("paper_trades_today", 0);
        consecutiveLosses = preferences.getInt("paper_consecutive_losses", 0);
        wins = preferences.getInt("paper_wins", 0);
        losses = preferences.getInt("paper_losses", 0);
    }

    public synchronized String process(List<Candle> candles, MarketAnalyzer.Signal signal) {
        resetDayIfNeeded();
        if (candles == null || candles.isEmpty()) return lastEvent;
        settleIfExpired(candles);
        Candle last = candles.get(candles.size() - 1);
        if (last.time == lastProcessedCandle) return lastEvent;
        lastProcessedCandle = last.time;

        if (activeTrade != null) return lastEvent;
        if (tradesToday >= MAX_TRADES_PER_DAY) {
            lastEvent = "Лимит симуляции на сегодня достигнут (" + MAX_TRADES_PER_DAY + " сделок).";
            return lastEvent;
        }
        if (consecutiveLosses >= MAX_CONSECUTIVE_LOSSES) {
            lastEvent = "Пауза: достигнут лимит " + MAX_CONSECUTIVE_LOSSES + " убытков подряд.";
            return lastEvent;
        }
        if (balance < STAKE) {
            lastEvent = "Симуляция остановлена: виртуального баланса недостаточно.";
            return lastEvent;
        }
        if (signal != MarketAnalyzer.Signal.CALL && signal != MarketAnalyzer.Signal.PUT) {
            lastEvent = "Ожидание сигнала. Виртуальная сделка не открыта.";
            return lastEvent;
        }

        long entryTime = last.time + 60_000L;
        activeTrade = new Trade(signal, entryTime, entryTime + EXPIRY_MILLIS, last.close);
        tradesToday++;
        save();
        lastEvent = "ОТКРЫТА ВИРТУАЛЬНАЯ " + (signal == MarketAnalyzer.Signal.CALL ? "ВВЕРХ" : "ВНИЗ")
                + " • ставка " + format(STAKE) + " • вход " + format(last.close)
                + " • экспирация через 3 минуты.";
        return lastEvent;
    }

    private void settleIfExpired(List<Candle> candles) {
        if (activeTrade == null) return;
        Candle expiryCandle = null;
        for (Candle candle : candles) {
            if (candle.time + 60_000L >= activeTrade.expiryTime) {
                expiryCandle = candle;
                break;
            }
        }
        if (expiryCandle == null) return;

        double exitPrice = expiryCandle.close;
        boolean win = activeTrade.direction == MarketAnalyzer.Signal.CALL
                ? exitPrice > activeTrade.entryPrice
                : exitPrice < activeTrade.entryPrice;
        if (win) {
            double profit = STAKE * SIMULATED_PAYOUT;
            balance += profit;
            wins++;
            consecutiveLosses = 0;
            lastEvent = "ВИРТУАЛЬНАЯ СДЕЛКА В ПЛЮС • +" + format(profit)
                    + " • цена закрытия " + format(exitPrice);
        } else {
            balance -= STAKE;
            losses++;
            consecutiveLosses++;
            lastEvent = (exitPrice == activeTrade.entryPrice ? "ВИРТУАЛЬНАЯ СДЕЛКА В НОЛЬ" : "ВИРТУАЛЬНАЯ СДЕЛКА В МИНУС")
                    + " • " + (exitPrice == activeTrade.entryPrice ? "0" : "-" + format(STAKE))
                    + " • цена закрытия " + format(exitPrice);
        }
        activeTrade = null;
        save();
    }

    public synchronized String getSummary() {
        String active = activeTrade == null ? "Нет активной виртуальной сделки."
                : "Активна " + (activeTrade.direction == MarketAnalyzer.Signal.CALL ? "ВВЕРХ" : "ВНИЗ")
                + " • вход " + format(activeTrade.entryPrice) + " • закрытие ориентировочно через 3 минуты.";
        return "ДЕМО-СИМУЛЯЦИЯ (не Binarium)\n"
                + "Виртуальный баланс: " + format(balance) + "\n"
                + "Сделок сегодня: " + tradesToday + "/" + MAX_TRADES_PER_DAY
                + " • плюсовых: " + wins + " • минусовых: " + losses + "\n"
                + "Ставка: " + format(STAKE) + " • условная выплата: 80%\n"
                + active + "\n" + lastEvent;
    }

    public synchronized void resetSimulation() {
        balance = START_BALANCE;
        tradesToday = 0;
        consecutiveLosses = 0;
        wins = 0;
        losses = 0;
        activeTrade = null;
        lastProcessedCandle = -1L;
        lastEvent = "Виртуальный счёт сброшен.";
        save();
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
        preferences.edit().putFloat("paper_balance", (float) balance)
                .putInt("paper_trades_today", tradesToday)
                .putInt("paper_consecutive_losses", consecutiveLosses)
                .putInt("paper_wins", wins)
                .putInt("paper_losses", losses)
                .putString("paper_day", dayKey).apply();
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
