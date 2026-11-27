package com.potraderbot;
import org.json.*;
public final class SportSquads {
 private SportSquads(){}
 private static String name(JSONObject o){return o==null?"":o.optString("name","");}
 public static String lineups(JSONArray rows){
  StringBuilder out=new StringBuilder();
  for(int i=0;i<rows.length();i++){
   JSONObject r=rows.optJSONObject(i);if(r==null)continue;
   JSONArray list=r.optJSONArray("startXI");if(list==null||list.length()==0)continue;
   StringBuilder players=new StringBuilder();
   for(int j=0;j<list.length();j++){
    JSONObject e=list.optJSONObject(j),p=e==null?null:e.optJSONObject("player");String n=name(p);if(n.isEmpty())continue;
    players.append("\n").append(p.optString("number","")).append(" ").append(n);
   }
   if(players.length()>0)out.append("\n").append(name(r.optJSONObject("team"))).append(" • ").append(r.optString("formation","")).append(players).append("\n");
  }
  return out.length()==0?"Составы не опубликованы или недоступны.":out.toString();
 }
 public static String injuries(JSONArray rows){
  StringBuilder out=new StringBuilder();
  for(int i=0;i<rows.length();i++){
   JSONObject r=rows.optJSONObject(i),p=r==null?null:r.optJSONObject("player");String n=name(p);if(n.isEmpty())continue;
   out.append("\n").append(name(r.optJSONObject("team"))).append(" • ").append(n).append(" — ").append(p.optString("type","")).append(" ").append(p.optString("reason",""));
  }
  return out.length()==0?"Нет опубликованных записей; статус игроков неизвестен.":out.toString();
 }
}
