package com.potraderbot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TradingSettings {
    public final List<String> selectedPairs;
    public final int expiryMinutes;
    public final boolean sharingEnabled;

    public TradingSettings(List<String> pairs, int expiry, boolean sharing) {
        if (pairs == null || pairs.isEmpty() || pairs.size() > 10)
            throw new IllegalArgumentException("Выбери от 1 до 10 валютных пар");
        if (expiry != 3 && expiry != 5)
            throw new IllegalArgumentException("Экспирация должна быть 3 или 5 минут");
        selectedPairs = new ArrayList<>(pairs);
        expiryMinutes = expiry;
        sharingEnabled = sharing;
    }

    public static TradingSettings defaults() {
        return new TradingSettings(Arrays.asList("EUR/USD"), 3, false);
    }
}
