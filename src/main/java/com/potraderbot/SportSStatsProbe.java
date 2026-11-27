package com.potraderbot;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
/** Anonymous connection diagnostic; no API-SPORTS credential or user data. */
public final class SportSStatsProbe {
 private SportSStatsProbe(){}
 private static final String BASE="https://api.sstats.net";
 public static String check(){
  return "Список лиг:\n"+probe("/leagues")+"\n\nАрхив сезона (41 / 2023):\n"+probe("/games/list?LeagueId=41&Year=2023")+"\n\nЭто диагностика доступности, не импорт истории и не прогноз.";
 }
 private static String probe(String path){
  HttpsURLConnection c=null;
  try{
   c=(HttpsURLConnection)new URL(BASE+path).openConnection();
   c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("Accept","application/json");
   int code=c.getResponseCode();
   if(code!=200)return "HTTP "+code+(code==429?" — лимит SStats.":code==401||code==403?" — доступ ограничен.":"");
   try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
    byte[] b=new byte[8192];int n;
    while((n=in.read(b))!=-1){if(out.size()+n>4000000)return "HTTP 200; ответ больше 4 МБ, импорт не выполнен.";out.write(b,0,n);}
    return describe(new String(out.toByteArray(),StandardCharsets.UTF_8));
   }
  }catch(Exception e){return "Не удалось получить ответ по HTTPS.";}finally{if(c!=null)c.disconnect();}
 }
 static String describe(String raw)throws JSONException{
  Object parsed=new JSONTokener(raw).nextValue();
  JSONArray rows=null;
  String status="HTTP 200";
  if(parsed instanceof JSONObject){
   JSONObject envelope=(JSONObject)parsed;
   status+="; поля ответа: "+fields(envelope);
   rows=envelope.optJSONArray("data");
  }else if(parsed instanceof JSONArray)rows=(JSONArray)parsed;
  if(rows==null)return status+"\nСписок записей не распознан.";
  String out=status+"\nЗаписей в ответе: "+rows.length();
  if(rows.length()>0&&rows.optJSONObject(0)!=null)out+="\nПоля первой записи: "+fields(rows.optJSONObject(0));
  return out;
 }
 private static String fields(JSONObject o){
  List<String> names=new ArrayList<>();Iterator<String> it=o.keys();
  while(it.hasNext()&&names.size()<40){
   String k=it.next();
   if(!k.matches("[A-Za-z][A-Za-z0-9_]{0,50}"))continue;
   Object v=o.opt(k);names.add(k+":"+(v instanceof JSONObject?"object":v instanceof JSONArray?"array":v instanceof Number?"number":v instanceof Boolean?"bool":v==null||v==JSONObject.NULL?"null":"text"));
  }
  Collections.sort(names);return String.join(", ",names);
 }
}
