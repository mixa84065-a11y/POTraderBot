package com.potraderbot;
import org.json.*;
import java.util.HashSet;
public final class SportHistory {
    private SportHistory(){}
    public static final class Summary {public int n,w,d,l,gf,ga,both,htN,htGoal,htFor,htAgainst;}
    public static Summary summarize(JSONArray rows,int team,long cutoff){
        Summary s=new Summary();HashSet<Long> seen=new HashSet<>();
        for(int i=0;i<rows.length();i++){
            JSONObject r=rows.optJSONObject(i);if(r==null)continue;
            JSONObject f=r.optJSONObject("fixture"),t=r.optJSONObject("teams"),g=r.optJSONObject("goals");
            if(f==null||t==null||g==null)continue;
            JSONObject status=f.optJSONObject("status"),h=t.optJSONObject("home"),a=t.optJSONObject("away");
            long id=f.optLong("id",0),time=f.optLong("timestamp",0);
            if(status==null||!"FT".equals(status.optString("short"))||id<=0||time<=0||time>=cutoff||h==null||a==null)continue;
            boolean home=h.optInt("id",-1)==team;if(!home&&a.optInt("id",-1)!=team)continue;
            int hg=g.optInt("home",-1),ag=g.optInt("away",-1);
            if(hg<0||ag<0||hg>100||ag>100||!seen.add(id))continue;
            int gf=home?hg:ag,ga=home?ag:hg;s.n++;s.gf+=gf;s.ga+=ga;
            if(gf>ga)s.w++;else if(gf==ga)s.d++;else s.l++;
            if(hg>0&&ag>0)s.both++;
            JSONObject score=r.optJSONObject("score"),ht=score==null?null:score.optJSONObject("halftime");
            if(ht!=null){int x=ht.optInt("home",-1),y=ht.optInt("away",-1);if(x>=0&&y>=0&&x<=hg&&y<=ag){s.htN++;s.htFor+=home?x:y;s.htAgainst+=home?y:x;if(x+y>0)s.htGoal++;}}
        }return s;
    }
}
