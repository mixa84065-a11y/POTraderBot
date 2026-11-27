package com.potraderbot;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.TreeMap;

/** Validates public spot market data before local paper decisions. */
final class PaperMarketGuard {
    static boolean samePosition(String selected,String position,double price){
        return selected!=null&&!selected.isEmpty()&&selected.equals(position)&&Double.isFinite(price)&&price>0;
    }
    private static JSONObject result(JSONObject root)throws Exception{
        if(root.optInt("retCode",-1)!=0)throw new IllegalArgumentException("Ошибка ответа Bybit");
        JSONObject r=root.getJSONObject("result");
        if(!"spot".equals(r.optString("category")))throw new IllegalArgumentException("Неверная категория рынка");
        return r;
    }
    static JSONObject ticker(JSONObject root,String symbol)throws Exception{
        JSONObject t=result(root).getJSONArray("list").getJSONObject(0);
        if(!samePosition(symbol,t.optString("symbol"),Double.parseDouble(t.getString("lastPrice"))))throw new IllegalArgumentException("Котировка другого инструмента или неверная цена");
        return t;
    }
    static JSONArray closedBars(JSONObject root,String symbol)throws Exception{
        JSONObject r=result(root);
        if(!symbol.equals(r.optString("symbol")))throw new IllegalArgumentException("История другого инструмента");
        long now=root.optLong("time",0);
        if(now<=0)throw new IllegalArgumentException("Нет времени сервера");
        JSONArray list=r.getJSONArray("list");TreeMap<Long,JSONArray> sorted=new TreeMap<>();
        if(list.length()>101)throw new IllegalArgumentException("Неверный размер истории");
        for(int i=0;i<list.length();i++){
            JSONArray row=list.getJSONArray(i);long start=Long.parseLong(row.getString(0));
            if(start<=0||start%60000L!=0||start>now)throw new IllegalArgumentException("Неверное время свечи");
            if(now-start<60000L)continue;
            double o=Double.parseDouble(row.getString(1)),h=Double.parseDouble(row.getString(2)),l=Double.parseDouble(row.getString(3)),c=Double.parseDouble(row.getString(4));
            if(!Double.isFinite(o)||!Double.isFinite(h)||!Double.isFinite(l)||!Double.isFinite(c)||l<=0||h<Math.max(o,c)||l>Math.min(o,c))throw new IllegalArgumentException("Некорректная свеча");
            if(sorted.put(start,row)!=null)throw new IllegalArgumentException("Повтор свечи");
        }
        while(sorted.size()>100)sorted.pollFirstEntry();
        JSONArray out=new JSONArray();long previous=0;
        for(java.util.Map.Entry<Long,JSONArray> entry:sorted.entrySet()){
            if(previous>0&&entry.getKey()-previous!=60000L)throw new IllegalArgumentException("Пропуск минутных свечей");
            out.put(entry.getValue());previous=entry.getKey();
        }
        if(out.length()==0)throw new IllegalArgumentException("Нет закрытых свечей");
        return out;
    }
}
