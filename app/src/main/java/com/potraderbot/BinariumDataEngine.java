package com.potraderbot;

import java.util.List;

/**
 * Data-only analysis pipeline for Binarium manual trading.
 * Invoke analyze() on a background thread. It never logs in to Binarium or places orders.
 */
public class BinariumDataEngine {
    public static class Result {
        public final MarketAnalyzer.Signal signal;
        public final String reason;
        public final int candleCount;

        Result(MarketAnalyzer.Signal signal, String reason, int candleCount) {
            this.signal = signal;
            this.reason = reason;
            this.candleCount = candleCount;
        }
    }

    private final TwelveDataClient marketClient;
    private final InvestingCalendarClient calendarClient;
    private final MarketAnalyzer analyzer = new MarketAnalyzer();
    private final NewsRiskFilter newsFilter = new NewsRiskFilter();
    private final CandleSeriesValidator validator = new CandleSeriesValidator();

    public BinariumDataEngine(String twelveDataKey, String apiShKey) {
        marketClient = new TwelveDataClient(twelveDataKey);
        calendarClient = new InvestingCalendarClient(apiShKey);
    }

    public Result analyze(String pair, String fromDate, String toDate, long nowUtcMillis)
            throws Exception {
        List<Candle> candles = marketClient.fetchOneMinuteCandles(pair, 100);
        String dataError = validator.validate(candles, nowUtcMillis);
        if (dataError != null) {
            return new Result(MarketAnalyzer.Signal.WAIT, dataError, candles.size());
        }

        List<EconomicEvent> events = calendarClient.fetchEvents(
                fromDate, toDate, null, "high,medium");
        NewsRiskFilter.Decision news = newsFilter.evaluate(pair, events, nowUtcMillis);
        if (news.blocked) {
            return new Result(MarketAnalyzer.Signal.WAIT, news.reason, candles.size());
        }

        MarketAnalyzer.Signal signal = analyzer.analyze(candles);
        return new Result(signal, signal == MarketAnalyzer.Signal.WAIT
                ? "Технические условия для входа не подтверждены"
                : "Сигнал рассчитан по реальным минутным свечам; сделка не открывалась",
                candles.size());
    }
}
