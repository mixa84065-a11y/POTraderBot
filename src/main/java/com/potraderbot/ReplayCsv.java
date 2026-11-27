package com.potraderbot;
import java.util.ArrayList;
import java.util.List;

/** Bounded offline input; no network calls or inferred missing prices. */
final class ReplayCsv {
    static List<HistoricalSpotReplay.Bar> parse(String csv){
        if(csv==null||csv.length()>1000000)throw new IllegalArgumentException("CSV слишком большой");
        String[] lines=csv.split("\\r?\\n",-1);
        if(lines.length==0||!"timestamp_ms,open,close,turnover24h".equals(lines[0].replace("\uFEFF","").trim()))
            throw new IllegalArgumentException("Неверный заголовок CSV");
        ArrayList<HistoricalSpotReplay.Bar> bars=new ArrayList<>();
        long previous=-1;
        for(int i=1;i<lines.length;i++){
            String line=lines[i].trim();if(line.isEmpty())continue;
            if(bars.size()>=5000)throw new IllegalArgumentException("Максимум 5000 свечей");
            String[] f=line.split(",",-1);
            try{
                if(f.length!=4)throw new IllegalArgumentException();
                long timestamp=Long.parseLong(f[0].trim());
                if(timestamp<0||timestamp%60000L!=0||timestamp<=previous)throw new IllegalArgumentException();
                double open=Double.parseDouble(f[1].trim()),close=Double.parseDouble(f[2].trim()),turnover=Double.parseDouble(f[3].trim());
                if(open>1e12||close>1e12||open<1e-12||close<1e-12||turnover>1e18)throw new IllegalArgumentException();
                bars.add(new HistoricalSpotReplay.Bar(timestamp,open,close,turnover));previous=timestamp;
            }catch(IllegalArgumentException e){throw new IllegalArgumentException("Некорректная строка "+(i+1)+": проверьте время и числа");}
        }
        if(bars.isEmpty())throw new IllegalArgumentException("Нет свечей");
        return bars;
    }
}
