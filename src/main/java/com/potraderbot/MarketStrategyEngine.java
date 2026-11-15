package com.potraderbot;

import java.util.Arrays;

/**
 * Market-aware rule engine for research and paper-trading signals.
 * It is deliberately fail-closed: stale, illiquid, wide-spread or incomplete
 * markets return NO TRADE. Scores are heuristic, never win probabilities.
 */
public final class MarketStrategyEngine {
    public static final class Result {
        public final String direction;
        public final int score;
        public final String risk;
        public final String explanation;
        Result(String direction, int score, String risk, String explanation) {
            this.direction=direction; this.score=score; this.risk=risk; this.explanation=explanation;
        }
    }

    public static final class OptionResult {
        public final String stance;
        public final String risk;
        public final String explanation;
        OptionResult(String stance, String risk, String explanation) {
            this.stance=stance; this.risk=risk; this.explanation=explanation;
        }
    }

    private MarketStrategyEngine() {}

    public static Result analyze(String marketType, double[] closes,
            double bid, double ask, double turnover24h, double fundingRate,
            double openInterestChangePct, long candleAgeMs, boolean sessionOpen) {
        String type=marketType==null?"":marketType.toLowerCase(java.util.Locale.US);
        if ("cfd".equals(type)) return noTrade("CFD требует отдельного MT5-коннектора и подтверждённых данных.");
        if (!sessionOpen) return noTrade("Торговая сессия закрыта; новые входы запрещены.");
        if (closes==null || closes.length<55) return noTrade("Недостаточно закрытых свечей: нужно не менее 55.");
        if (candleAgeMs<0 || candleAgeMs>180000L) return noTrade("Котировки устарели; новые входы запрещены.");
        if (!positiveFinite(bid) || !positiveFinite(ask) || ask<bid)
            return noTrade("Нет корректного bid/ask.");
        if (!positiveFinite(turnover24h)) return noTrade("Нет подтверждённой ликвидности за 24 часа.");
        for (double value:closes) if(!positiveFinite(value)) return noTrade("В истории есть некорректная цена.");

        double spreadPct=(ask-bid)/((ask+bid)/2.0)*100.0;
        double maxSpread=("tradfi".equals(type)?0.50:0.15);
        if (spreadPct>maxSpread) return new Result("НЕТ СДЕЛКИ",0,"ВЫСОКИЙ",
                String.format(java.util.Locale.US,"Спред %.3f%% превышает лимит %.2f%%.",spreadPct,maxSpread));

        double e9=ema(closes,9), e21=ema(closes,21), e50=ema(closes,50);
        double rsi=rsi(closes,14);
        double momentum=(closes[closes.length-1]-closes[closes.length-4])/closes[closes.length-4]*100.0;
        int score=0;
        if(e9>e21)score+=2; else if(e9<e21)score-=2;
        if(e21>e50)score++; else if(e21<e50)score--;
        if(momentum>0.03)score++; else if(momentum< -0.03)score--;
        if(rsi>=52&&rsi<=68)score++; else if(rsi>=32&&rsi<=48)score--;
        else if(rsi>78)score--; else if(rsi<22)score++;

        StringBuilder notes=new StringBuilder();
        notes.append(String.format(java.util.Locale.US,"EMA9/21/50; RSI %.1f; импульс %+.3f%%; спред %.3f%%",rsi,momentum,spreadPct));
        if ("linear".equals(type)||"inverse".equals(type)||"tradfi".equals(type)) {
            if (Double.isFinite(fundingRate) && Math.abs(fundingRate)>=0.001) {
                score += fundingRate>0 ? -1 : 1;
                notes.append(String.format(java.util.Locale.US,"; экстремальное финансирование %+.4f%% (контртрендовый штраф)",fundingRate*100.0));
            }
            if (Double.isFinite(openInterestChangePct) && Math.abs(openInterestChangePct)>=2.0) {
                if ((momentum>0 && openInterestChangePct>0)||(momentum<0 && openInterestChangePct>0)) {
                    score += momentum>0?1:-1;
                    notes.append("; OI растёт вместе с движением");
                } else if (momentum*openInterestChangePct<0) {
                    score += momentum>0?-1:1;
                    notes.append("; расхождение цены и OI");
                }
            }
        }
        String direction=score>=4?"ВВЕРХ":score<=-4?"ВНИЗ":"НЕТ СИГНАЛА";
        String risk=spreadPct>maxSpread*0.65?"СРЕДНИЙ":"УМЕРЕННЫЙ";
        if (Math.abs(score)<4) risk="ОЖИДАНИЕ";
        notes.append("; балл ").append(score).append(". Это эвристика, не вероятность дохода.");
        return new Result(direction,score,risk,notes.toString());
    }

    /** Options are evaluated with their own Greeks/IV/liquidity; never treated as spot BUY/SELL. */
    public static OptionResult analyzeOption(double bid, double ask, double markPrice,
            double delta, double gamma, double vega, double theta, double markIv,
            double turnover24h, double hoursToExpiry, int underlyingTrendScore) {
        if (!positiveFinite(bid)||!positiveFinite(ask)||ask<bid||!positiveFinite(markPrice))
            return optionNoTrade("Нет корректной цены опциона.");
        if (!positiveFinite(turnover24h)) return optionNoTrade("Недостаточная/неподтверждённая ликвидность опциона.");
        if (!Double.isFinite(delta)||!Double.isFinite(gamma)||!Double.isFinite(vega)||
                !Double.isFinite(theta)||!Double.isFinite(markIv))
            return optionNoTrade("Биржа не предоставила полные Greeks или implied volatility.");
        if (!Double.isFinite(hoursToExpiry)||hoursToExpiry<12)
            return optionNoTrade("До экспирации меньше 12 часов: новый вход заблокирован.");
        double spreadPct=(ask-bid)/((ask+bid)/2.0)*100.0;
        if (spreadPct>8.0) return optionNoTrade("Спред опциона слишком широк: "+String.format(java.util.Locale.US,"%.1f%%",spreadPct));
        if (markIv<=0||markIv>2.0) return optionNoTrade("IV вне безопасного исследовательского диапазона.");
        if (Math.abs(delta)<0.25||Math.abs(delta)>0.75) return optionNoTrade("Delta опциона вне диапазона 0.25–0.75.");
        if (underlyingTrendScore>=4 && delta>0) {
            return new OptionResult("CALL-КАНДИДАТ","ВЫСОКИЙ",
                    String.format(java.util.Locale.US,"Тренд базового актива вверх; delta %.3f, gamma %.5f, vega %.4f, theta %.4f, IV %.1f%%. Только наблюдение, не заявка.",delta,gamma,vega,theta,markIv*100));
        }
        if (underlyingTrendScore<=-4 && delta<0) {
            return new OptionResult("PUT-КАНДИДАТ","ВЫСОКИЙ",
                    String.format(java.util.Locale.US,"Тренд базового актива вниз; delta %.3f, gamma %.5f, vega %.4f, theta %.4f, IV %.1f%%. Только наблюдение, не заявка.",delta,gamma,vega,theta,markIv*100));
        }
        return new OptionResult("НЕТ СИГНАЛА","ОЖИДАНИЕ","Greeks и тренд базового актива не дают согласованного преимущества.");
    }

    private static Result noTrade(String why){return new Result("НЕТ СДЕЛКИ",0,"ЗАБЛОКИРОВАНО",why);}
    private static OptionResult optionNoTrade(String why){return new OptionResult("НЕТ СДЕЛКИ","ЗАБЛОКИРОВАНО",why);}
    private static boolean positiveFinite(double v){return Double.isFinite(v)&&v>0;}
    private static double ema(double[] a,int p){double k=2.0/(p+1.0),v=a[0];for(int i=1;i<a.length;i++)v=a[i]*k+v*(1-k);return v;}
    private static double rsi(double[] a,int p){
        double up=0,down=0;
        for(int i=1;i<=p;i++){double d=a[i]-a[i-1];if(d>0)up+=d;else down-=d;}
        up/=p;down/=p;
        for(int i=p+1;i<a.length;i++){double d=a[i]-a[i-1];up=(up*(p-1)+Math.max(0,d))/p;down=(down*(p-1)+Math.max(0,-d))/p;}
        return down==0?100:100-100/(1+up/down);
    }
}
