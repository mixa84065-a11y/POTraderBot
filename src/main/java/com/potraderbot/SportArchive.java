package com.potraderbot;
import org.json.*;
import java.util.Locale;
public final class SportArchive {
 private SportArchive(){}
 public static JSONArray forTeams(JSONArray rows,int home,int away,long cutoff){
  JSONArray out=new JSONArray();
  for(int i=0;i<rows.length();i++){
   JSONObject r=rows.optJSONObject(i),f=r==null?null:r.optJSONObject("fixture"),t=r==null?null:r.optJSONObject("teams");
   if(f==null||t==null||f.optLong("timestamp",0)<=0||f.optLong("timestamp")>=cutoff)continue;
   JSONObject st=f.optJSONObject("status"),h=t.optJSONObject("home"),a=t.optJSONObject("away");
   if(st==null||!"FT".equals(st.optString("short"))||h==null||a==null)continue;
   int x=h.optInt("id",-1),y=a.optInt("id",-1);
   if(x==home||x==away||y==home||y==away)out.put(r);
  }return out;
 }
 public static String errorCategory(Object errors){
  String raw=String.valueOf(errors).toLowerCase(Locale.ROOT);
  if(raw.contains("subscription")||raw.contains("plan")||raw.contains("season")||raw.contains("free"))return "API: ограничение тарифа или сезона.";
  if(raw.contains("requests")||raw.contains("rate limit")||raw.contains("quota"))return "API: ограничение числа запросов.";
  if(raw.contains("token")||raw.contains("key")||raw.contains("auth"))return "API: ошибка авторизации.";
  if(raw.contains("last")||raw.contains("parameter"))return "API: параметр запроса не поддерживается.";
  return "API вернул ошибку данных; категория не распознана.";
 }
}
