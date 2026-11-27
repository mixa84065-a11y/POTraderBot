package com.potraderbot;
import org.json.*;
import java.util.*;
public final class SportForecastJournal{
 private SportForecastJournal(){}
 public static JSONObject create(String title,int home,int away,long cutoff,SportHistory.Summary h,SportHistory.Summary a)throws JSONException{
  if(h.n<5||a.n<5)return null;
  double x=(h.gf/(double)h.n+a.ga/(double)a.n)/2,y=(a.gf/(double)a.n+h.ga/(double)h.n)/2;
  if(x+y>50||x<0||y<0)return null;
  double[] p=SportAutoModel.outcomes(x,y);
  JSONObject j=new JSONObject().put("title",title).put("home",home).put("away",away).put("cutoff",cutoff).put("created",System.currentTimeMillis()/1000).put("model","poisson-v1").put("p1",p[0]).put("px",p[1]).put("p2",p[2]).put("btts",SportStatistics.bothScore(x,y)).put("over2",SportStatistics.above(x+y,2));
  if(h.htN>=5&&a.htN>=5){
   double u=(h.htFor/(double)h.htN+a.htAgainst/(double)a.htN)/2,v=(a.htFor/(double)a.htN+h.htAgainst/(double)h.htN)/2;
   if(u>=0&&v>=0&&u<=x&&v<=y)j.put("ht1",SportStatistics.above(u+v,0)).put("ht2",SportStatistics.above(u+v,1)).put("ht3",SportStatistics.above(u+v,2)).put("htbtts",SportStatistics.bothScore(u,v));
  }return j;
 }
 public static boolean settle(JSONObject p,long id,JSONObject row)throws JSONException{
  if(row==null||p.has("result")||p.optLong("created")>=p.optLong("cutoff"))return false;
  JSONObject f=row.optJSONObject("fixture"),t=row.optJSONObject("teams"),g=row.optJSONObject("goals");
  if(f==null||t==null||g==null||f.optLong("id")!=id)return false;
  JSONObject st=f.optJSONObject("status"),h=t.optJSONObject("home"),a=t.optJSONObject("away");
  if(st==null||!"FT".equals(st.optString("short"))||h==null||a==null||h.optInt("id")!=p.optInt("home")||a.optInt("id")!=p.optInt("away"))return false;
  if(f.optLong("timestamp")!=p.optLong("cutoff"))return false;
  int x=g.optInt("home",-1),y=g.optInt("away",-1);if(x<0||y<0)return false;
  JSONObject result=new JSONObject().put("home",x).put("away",y);
  JSONObject score=row.optJSONObject("score"),ht=score==null?null:score.optJSONObject("halftime");
  if(ht!=null){int u=ht.optInt("home",-1),v=ht.optInt("away",-1);if(u>=0&&v>=0&&u<=x&&v<=y)result.put("htHome",u).put("htAway",v);}
  p.put("result",result);return true;
 }
 private static double loss(double p,boolean actual){double d=p-(actual?1:0);return d*d;}
 public static String report(JSONObject journal){
  int n=0,correct=0,htN=0;double outcome=0,btts=0,over=0,ht=0;
  Iterator<String> keys=journal.keys();StringBuilder recent=new StringBuilder();int displayed=0;
  while(keys.hasNext()){
   JSONObject p=journal.optJSONObject(keys.next());if(p==null)continue;
   JSONObject r=p.optJSONObject("result");
   if(displayed++<20)recent.append("\n").append(p.optString("title")).append(" • ").append(r==null?"ожидает":r.optInt("home")+":"+r.optInt("away"));
   if(r==null)continue;int x=r.optInt("home"),y=r.optInt("away");double a=p.optDouble("p1"),b=p.optDouble("px"),c=p.optDouble("p2");
   n++;int actual=x>y?0:x==y?1:2;int pick=a>=b&&a>=c?0:b>=c?1:2;if(pick==actual)correct++;
   outcome+=(loss(a,actual==0)+loss(b,actual==1)+loss(c,actual==2))/3;
   btts+=loss(p.optDouble("btts"),x>0&&y>0);over+=loss(p.optDouble("over2"),x+y>=3);
   if(p.has("ht1")&&r.has("htHome")&&r.has("htAway")){int u=r.optInt("htHome"),v=r.optInt("htAway");htN++;ht+=(loss(p.optDouble("ht1"),u+v>=1)+loss(p.optDouble("ht2"),u+v>=2)+loss(p.optDouble("ht3"),u+v>=3)+loss(p.optDouble("htbtts"),u>0&&v>0))/4;}
  }
  return "Сохранено: "+journal.length()+"\nПроверено: "+n+"\nОжидают: "+(journal.length()-n)+(n==0?"\nНедостаточно результатов.":String.format(Locale.US,"\nСовпадение наиболее вероятного исхода: %d/%d (%.1f%%)\nСредняя квадратичная ошибка вероятностей (меньше — лучше):\nП1/X/П2 %.3f\nОЗ %.3f\nТотал 2,5 %.3f",correct,n,100.0*correct/n,outcome/n,btts/n,over/n))+(htN==0?"\nПервый тайм: нет проверенных данных.":String.format(Locale.US,"\nПервый тайм: %.3f (%d матчей)",ht/htN,htN))+"\n\nВыборка не доказывает будущую точность. Сверка — до 5 матчей за нажатие.\n"+recent;
 }
}
