package com.potraderbot;

import org.json.JSONArray;
import org.json.JSONObject;

/** A new-test preview is not a server run or a reset of the previous ledger. */
final class DemoReportPreparation {
    private DemoReportPreparation() {}
    static boolean canPrepare(String status,int openPositions) {
        return openPositions==0 && ("STOPPED".equals(status)||"COMPLETED".equals(status)||"FINISHED".equals(status)||"RISK_STOP".equals(status));
    }
    static boolean canRequestStop(String status) {
        return "RUNNING".equals(status);
    }
    static int openBybitPositions(JSONArray rows) {
        if(rows==null)return -1;
        int open=0;
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);
            if(row==null)return -1;
            String status=row.optString("status","");
            if("OPEN".equals(status))open++;
            else if(!"CLOSED".equals(status))return -1;
        }
        return open;
    }
    static boolean keepDraft(String draftPreviousId,String actualPreviousId,boolean canPrepare) {
        return canPrepare && draftPreviousId!=null && draftPreviousId.equals(actualPreviousId);
    }
    static String zeroStatistics() {
        return "ПОДГОТОВКА • НЕ СЕРВЕРНЫЙ ОТЧЁТ\nСделок: 0\nПлюсовых: 0 • минусовых: 0\nБезубыточных: 0\nWin rate: — (нет сделок)\nЧистый P/L: 0 USDT\nКомиссии: 0 USDT\nПросадка: 0%";
    }
}
