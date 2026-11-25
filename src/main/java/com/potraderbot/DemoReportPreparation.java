package com.potraderbot;

/** A new-test preview is not a server run or a reset of the previous ledger. */
final class DemoReportPreparation {
    private DemoReportPreparation() {}
    static boolean canPrepare(String status,int openPositions) {
        return openPositions==0 && ("STOPPED".equals(status)||"COMPLETED".equals(status)||"FINISHED".equals(status));
    }
    static boolean keepDraft(String draftPreviousId,String actualPreviousId,boolean canPrepare) {
        return canPrepare && draftPreviousId!=null && draftPreviousId.equals(actualPreviousId);
    }
    static String zeroStatistics() {
        return "ПОДГОТОВКА • НЕ СЕРВЕРНЫЙ ОТЧЁТ\nСделок: 0\nПлюсовых: 0 • минусовых: 0\nБезубыточных: 0\nWin rate: — (нет сделок)\nЧистый P/L: 0 USDT\nКомиссии: 0 USDT\nПросадка: 0%";
    }
}
