package com.potraderbot;
import org.json.*;
import java.util.*;
public final class SportDetailHistory {
 private SportDetailHistory(){}
 public static List<Long> recentIds(JSONArray rows,int team,long cutoff){
   Map<Long,Long> times=new HashMap<>();
   for(int i=0;i<rows.length();i++){
     JSONObject r=rows.optJSONObject(i);if(r==null)continue;
     JSONObject f=r.optJSONObject("fixture"),t=r.optJSONObject("teams");if(f==null||t==null)continue;
     JSONObject h=t.optJSONObject("home"),a=t.optJSONObject("away"),st=f.optJSONObject("status");
     long id=f.optLong("id",0),time=f.optLong("timestamp",0);
     if(id<=0||time<=0||time>=cutoff||st==null||!"FT".equals(st.optString("short"))||h==null||a==null||(h.optInt("id",-1)!=team&&a.optInt("id",-1)!=team))continue;
     times.put(id,time);
   }
   List<Long> ids=new ArrayList<>(times.keySet());ids.sort((x,y)->{int c=Long.compare(times.get(y),times.get(x));return c==0?Long.compare(y,x):c;});return new ArrayList<>(ids.subList(0,Math.min(3,ids.size())));
 }
 private static int value(JSONObject side,String name){
   JSONArray list=side.optJSONArray("statistics");if(list==null)return -1;
   for(int i=0;i<list.length();i++){JSONObject e=list.optJSONObject(i);if(e!=null&&name.equals(e.optString("type"))){Object v=e.opt("value");if(!(v instanceof Number))return -1;double n=((Number)v).doubleValue();return n>=0&&n<=100&&n==Math.floor(n)?(int)n:-1;}}return -1;
 }
 public static final class Summary{
   public int cornersN,cornersFor,cornersAgainst,cardsN,cardsFor,cardsAgainst;
   public void add(JSONArray response,int team){
     if(response.length()!=2)return;JSONObject own=null,other=null;
     for(int i=0;i<2;i++){JSONObject x=response.optJSONObject(i);if(x==null)return;JSONObject t=x.optJSONObject("team");if(t==null||t.optInt("id",-1)<=0)return;if(t.optInt("id",-1)==team)own=x;else other=x;}
     if(own==null||other==null)return;
     int a=value(own,"Corner Kicks"),b=value(other,"Corner Kicks");
     if(a>=0&&b>=0){cornersN++;cornersFor+=a;cornersAgainst+=b;}
     a=value(own,"Yellow Cards");b=value(other,"Yellow Cards");if(a>=0&&b>=0){cardsN++;cardsFor+=a;cardsAgainst+=b;}
   }
 }
}
