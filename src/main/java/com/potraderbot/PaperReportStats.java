package com.potraderbot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.json.JSONObject;
import org.json.JSONException;

/** Counts closed server paper trades only. A missing amount is an invalid report, not a zero. */
final class PaperReportStats {
    private int total, wins, losses, breakeven;
    private BigDecimal net = BigDecimal.ZERO, fees = BigDecimal.ZERO;
    void add(JSONObject row) throws JSONException {
        BigDecimal value = new BigDecimal(row.getString("net_pnl_usdt"));
        BigDecimal fee = new BigDecimal(row.getString("fees_usdt"));
        total++; net=net.add(value); fees=fees.add(fee);
        if(value.signum()>0) wins++; else if(value.signum()<0) losses++; else breakeven++;
    }
    JSONObject toJson() throws JSONException {
        JSONObject out=new JSONObject();
        out.put("trades_total",total);out.put("wins",wins);out.put("losses",losses);out.put("breakeven",breakeven);
        out.put("net_pnl_usdt",net.toPlainString());out.put("fees_usdt",fees.toPlainString());
        out.put("win_rate_pct",total==0?"0.00":BigDecimal.valueOf(wins).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total),2,RoundingMode.HALF_UP).toPlainString());
        return out;
    }
}
