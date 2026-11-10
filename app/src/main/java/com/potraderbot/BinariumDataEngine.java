package com.potraderbot;

import java.util.List;

/**
 * Data-only analysis pipeline for Binarium manual trading.
 * It never logs in to Binarium or places orders.
 */
public class BinariumDataEngine {
    public static class Result {
        public final MarketAnalyzer.Signal signal;
        public final String reason;
        public final int candleCount;
        public final String technicalReport;

        Result(MarketAnalyzer.Signal signal, String reason, int candleCount, String technicalReport) {
            this.signal = signal;
            this.reason = reason;
            this.candleCount = candleCount;
            this.technicalReport = technicalReport;
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
        List<Candle> candles = marketClient.fetchOneMinuteCandles(pair, 500);
        String dataError = validator.validate(candles, nowUtcMillis);
        if (dataError != null) {
            return new Result(MarketAnalyzer.Signal.WAIT, dataError, candles.size(),
                    "Анализ остановлен: качество или свежесть котировок не прошли проверку");
        }

        List<EconomicEvent> events = calendarClient.fetchEvents(
                fromDate, toDate, null, "high,medium");
        NewsRiskFilter.Decision news = newsFilter.evaluate(pair, events, nowUtcMillis);
        if (news.blocked) {
            return new Result(MarketAnalyzer.Signal.WAIT, news.reason, candles.size(),
                    "Новостной фильтр заблокировал сигнал. До выхода из защитного окна сделки не рассматриваются.");
        }

        MarketAnalyzer.AnalysisReport report = analyzer.analyzeDetailed(candles);
        return new Result(report.signal,
                report.signal == MarketAnalyzer.Signal.WAIT
                        ? "WAIT: недостаточно согласованных условий для входа"
                        : "Условия направления прошли технические фильтры; это не гарантия исхода",
                candles.size(), report.toDisplayText());
    }
}
