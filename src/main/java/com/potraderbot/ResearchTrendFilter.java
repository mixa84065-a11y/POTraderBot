package com.potraderbot;

import java.util.ArrayList;
import java.util.List;

/** Experimental confirmation on completed UTC 5m/15m candles. Offline research only. */
final class ResearchTrendFilter {
    enum Decision { CONFIRMED, INSUFFICIENT_HISTORY, INVALID_HISTORY, FLAT, CONFLICT }
    static final int MINUTES_REQUIRED=240;
    private ResearchTrendFilter(){}

    /** Inputs include only observations available at the signal close. */
    static Decision evaluate(List<HistoricalSpotReplay.Bar> bars,int start,int end) {
        if(bars==null||start<0||end<start||end>=bars.size())return Decision.INVALID_HISTORY;
        if(end-start+1<MINUTES_REQUIRED)return Decision.INSUFFICIENT_HISTORY;
        for(int i=start;i<=end;i++){
            HistoricalSpotReplay.Bar bar=bars.get(i);
            if(bar==null||bar.time%60000L!=0||
                (i>start&&bar.time-bars.get(i-1).time!=60000L))
                return Decision.INVALID_HISTORY;
        }
        double[] five=completedCloses(bars,start,end,5);
        double[] fifteen=completedCloses(bars,start,end,15);
        if(five.length<15||fifteen.length<15)return Decision.INSUFFICIENT_HISTORY;
        int t5=direction(five),t15=direction(fifteen);
        if(t5<0||t15<0)return Decision.CONFLICT;
        if(t5==0||t15==0)return Decision.FLAT;
        return Decision.CONFIRMED;
    }
    /** Only full buckets; ignore partial first and current buckets. */
    static double[] completedCloses(List<HistoricalSpotReplay.Bar> bars,int start,int end,int minutes){
        long interval=minutes*60000L;
        ArrayList<Double> out=new ArrayList<>();
        for(int i=start;i+minutes-1<=end;){
            long t=bars.get(i).time;
            if(t%interval!=0){i++;continue;}
            boolean complete=true;
            for(int j=1;j<minutes;j++){
                if(bars.get(i+j).time!=t+j*60000L){complete=false;break;}
            }
            if(complete){out.add(bars.get(i+minutes-1).close);i+=minutes;}
            else i++;
        }
        double[] result=new double[out.size()];
        for(int i=0;i<result.length;i++)result[i]=out.get(i);
        return result;
    }
    /** Predeclared heuristic, not calibrated: require EMA separation and directional efficiency. */
    private static int direction(double[] closes){
        double fast=ema(closes,5),slow=ema(closes,13);
        double separation=(fast-slow)/slow;
        int start=closes.length-11;
        double path=0;
        for(int i=start+1;i<closes.length;i++)path+=Math.abs(closes[i]-closes[i-1]);
        double move=closes[closes.length-1]-closes[start];
        if(path<=0||Math.abs(move)/path<0.30||Math.abs(separation)<0.0005)return 0;
        if(separation>0&&move>0)return 1;
        if(separation<0&&move<0)return -1;
        return 0;
    }
    private static double ema(double[] closes,int period){
        double v=closes[0],k=2.0/(period+1);
        for(int i=1;i<closes.length;i++)v+=k*(closes[i]-v);
        return v;
    }
}
