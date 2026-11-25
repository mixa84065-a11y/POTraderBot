package com.potraderbot;

import java.util.List;

/** Offline research only. No network, no orders. Fixed horizon; no stop/target simulation. */
public final class HistoricalSpotReplay {
    private HistoricalSpotReplay() {}
    public static final class Bar {
        public final long time;
        public final double open, close, turnover24h;
        public Bar(long time,double open,double close,double turnover24h){
            if(time<0||!positive(open)||!positive(close)||!positive(turnover24h))
                throw new IllegalArgumentException("Invalid historical bar");
            this.time=time;this.open=open;this.close=close;this.turnover24h=turnover24h;
        }
    }
    public static final class Metrics {
        public int trades,wins,losses,breakeven;
        public double equity=1.0, maxDrawdownPct=0.0;
        private double peak=1.0;
        void record(double factor){
            trades++;
            if(factor>1.0)wins++;else if(factor<1.0)losses++;else breakeven++;
            equity*=factor;peak=Math.max(peak,equity);
            maxDrawdownPct=Math.max(maxDrawdownPct,100*(peak-equity)/peak);
        }
        public double returnPct(){return 100*(equity-1.0);}
    }
    public static final class Report {
        public int eligibleWindows, skippedGaps;
        public final Metrics strategy=new Metrics(), baseline=new Metrics();
        public final Metrics matchedOriginal=new Metrics(), filtered=new Metrics();
        public int comparisonWindows, filterFlat, filterConflict, filterUnavailable;
    }
    private static boolean positive(double v){return Double.isFinite(v)&&v>0;}
    private static boolean validBps(double v){return Double.isFinite(v)&&v>=0&&v<=100;}
    /** Buy at next bar open (ask + slippage), exit at a later open (bid - slippage).
     * Fee applies on both legs. All bps inputs are explicit research assumptions. */
    static double netFactor(double entry,double exit,double spreadBps,double feeBps,double slippageBps){
        if(!positive(entry)||!positive(exit)||!validBps(spreadBps)||!validBps(feeBps)||!validBps(slippageBps))
            throw new IllegalArgumentException("Invalid execution assumptions");
        double impact=(spreadBps/2+slippageBps)/10000.0, fee=feeBps/10000.0;
        return exit*(1-impact)*(1-fee)/(entry*(1+impact)*(1+fee));
    }
    /** Compare the current Android spot signal against a long-only baseline on identical nonoverlapping windows.
     * No future observations are passed to the signal. Gaps invalidate the whole window.
     * Equity/drawdown are marked at completed exits only, not intratrade. */
    public static Report replay(List<Bar> bars,int lookback,int horizon,double spreadBps,double feeBps,double slippageBps){
        if(bars==null||lookback<55||horizon<1||!validBps(spreadBps)||!validBps(feeBps)||!validBps(slippageBps))
            throw new IllegalArgumentException("Invalid replay settings");
        for(int i=0;i<bars.size();i++)
            if(bars.get(i)==null||(i>0&&bars.get(i).time<=bars.get(i-1).time))
                throw new IllegalArgumentException("History must be strictly chronological");
        Report report=new Report();
        for(int i=lookback-1;i<bars.size()-1-horizon;i+=horizon+1){
            int start=i-lookback+1, entry=i+1, exit=entry+horizon;
            boolean contiguous=true;
            for(int j=start+1;j<=exit;j++)if(bars.get(j).time-bars.get(j-1).time!=60000L){contiguous=false;break;}
            if(!contiguous){report.skippedGaps++;continue;}
            report.eligibleWindows++;
            double[] closes=new double[lookback];
            for(int j=0;j<lookback;j++)closes[j]=bars.get(start+j).close;
            Bar signal=bars.get(i);
            double half=spreadBps/20000.0;
            MarketStrategyEngine.Result decision=MarketStrategyEngine.analyze("spot",closes,signal.close*(1-half),
                signal.close*(1+half),signal.turnover24h,Double.NaN,Double.NaN,0,true);
            double factor=netFactor(bars.get(entry).open,bars.get(exit).open,spreadBps,feeBps,slippageBps);
            report.baseline.record(factor);
            boolean longSignal="ВВЕРХ".equals(decision.direction);
            if(longSignal)report.strategy.record(factor);
            // Only compare on windows where both versions had enough usable history.
            ResearchTrendFilter.Decision confirmation=ResearchTrendFilter.evaluate(bars,start,i);
            if(confirmation==ResearchTrendFilter.Decision.INSUFFICIENT_HISTORY||
               confirmation==ResearchTrendFilter.Decision.INVALID_HISTORY){
                report.filterUnavailable++;
                continue;
            }
            report.comparisonWindows++;
            if(longSignal){
                report.matchedOriginal.record(factor);
                if(confirmation==ResearchTrendFilter.Decision.CONFIRMED)report.filtered.record(factor);
                else if(confirmation==ResearchTrendFilter.Decision.FLAT)report.filterFlat++;
                else if(confirmation==ResearchTrendFilter.Decision.CONFLICT)report.filterConflict++;
            }
        }
        return report;
    }
    /** CSV: timestamp_ms,open,close,turnover24h; header required; chronological UTC minute bars.
     * Args: file lookback horizon spreadBps feeBps slippageBps. */
    public static void main(String[] args) throws Exception {
        if(args.length!=6)throw new IllegalArgumentException("Usage: CSV lookback horizon spreadBps feeBps slippageBps");
        java.util.ArrayList<Bar> bars=new java.util.ArrayList<>();
        try(java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(args[0]),java.nio.charset.StandardCharsets.UTF_8))){
            if(!"timestamp_ms,open,close,turnover24h".equals(reader.readLine()))
                throw new IllegalArgumentException("Unexpected CSV header");
            String line;
            while((line=reader.readLine())!=null){
                String[] f=line.split(",",-1);
                if(f.length!=4)throw new IllegalArgumentException("Malformed CSV row");
                bars.add(new Bar(Long.parseLong(f[0]),Double.parseDouble(f[1]),Double.parseDouble(f[2]),Double.parseDouble(f[3])));
            }
        }
        Report r=replay(bars,Integer.parseInt(args[1]),Integer.parseInt(args[2]),Double.parseDouble(args[3]),Double.parseDouble(args[4]),Double.parseDouble(args[5]));
        System.out.println("Offline research; not server worker performance. Eligible="+r.eligibleWindows+" gaps="+r.skippedGaps);
        print("Android spot signal",r.strategy);print("Long baseline",r.baseline);
        System.out.println("Matched windows="+r.comparisonWindows+" unavailable="+r.filterUnavailable+
                " rejected flat="+r.filterFlat+" rejected conflict="+r.filterConflict);
        if(r.comparisonWindows>0){print("Original on matched windows",r.matchedOriginal);print("Experimental 5m/15m",r.filtered);}
        else System.out.println("No valid comparison windows; use lookback >= 240.");
    }
    private static void print(String label,Metrics m){
        System.out.printf(java.util.Locale.US,"%s: trades=%d wins=%d losses=%d ties=%d return=%.6f%% closed-equity drawdown=%.6f%%%n",
                label,m.trades,m.wins,m.losses,m.breakeven,m.returnPct(),m.maxDrawdownPct);
    }
}
