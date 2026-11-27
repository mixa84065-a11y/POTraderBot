package com.potraderbot;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
public final class SportLocalArchive extends SQLiteOpenHelper {
 public SportLocalArchive(Context context){super(context,"gos_football_archive.db",null,1);}
 @Override public void onCreate(SQLiteDatabase db){
  db.execSQL("CREATE TABLE matches (id INTEGER PRIMARY KEY, kickoff INTEGER NOT NULL, home INTEGER NOT NULL, away INTEGER NOT NULL, payload TEXT NOT NULL)");
  db.execSQL("CREATE INDEX matches_home ON matches(home,kickoff)");
  db.execSQL("CREATE INDEX matches_away ON matches(away,kickoff)");
 }
 @Override public void onUpgrade(SQLiteDatabase db,int old,int next){throw new IllegalStateException("Unknown archive schema");}
 public static JSONObject compact(JSONObject r)throws JSONException{
  if(r==null)return null;
  JSONObject f=r.optJSONObject("fixture"),t=r.optJSONObject("teams"),g=r.optJSONObject("goals");
  if(f==null||t==null||g==null)return null;
  JSONObject st=f.optJSONObject("status"),h=t.optJSONObject("home"),a=t.optJSONObject("away");
  if(st==null||!"FT".equals(st.optString("short"))||h==null||a==null)return null;
  long id=f.optLong("id",0),time=f.optLong("timestamp",0);int x=h.optInt("id",-1),y=a.optInt("id",-1),gf=g.optInt("home",-1),ga=g.optInt("away",-1);
  if(id<=0||time<=0||x<=0||y<=0||x==y||gf<0||ga<0||gf>100||ga>100)return null;
  JSONObject out=new JSONObject().put("fixture",new JSONObject().put("id",id).put("timestamp",time).put("status",new JSONObject().put("short","FT"))).put("teams",new JSONObject().put("home",new JSONObject().put("id",x)).put("away",new JSONObject().put("id",y))).put("goals",new JSONObject().put("home",gf).put("away",ga));
  JSONObject score=r.optJSONObject("score"),ht=score==null?null:score.optJSONObject("halftime");
  if(ht!=null){int u=ht.optInt("home",-1),v=ht.optInt("away",-1);if(u>=0&&v>=0&&u<=gf&&v<=ga)out.put("score",new JSONObject().put("halftime",new JSONObject().put("home",u).put("away",v)));}
  return out;
 }
 public void ingest(JSONArray rows)throws JSONException{
  SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
  try{for(int i=0;i<rows.length();i++){
    JSONObject r=compact(rows.optJSONObject(i));if(r==null)continue;
    JSONObject f=r.getJSONObject("fixture"),t=r.getJSONObject("teams");
    ContentValues v=new ContentValues();v.put("id",f.getLong("id"));v.put("kickoff",f.getLong("timestamp"));v.put("home",t.getJSONObject("home").getInt("id"));v.put("away",t.getJSONObject("away").getInt("id"));v.put("payload",r.toString());db.insertWithOnConflict("matches",null,v,SQLiteDatabase.CONFLICT_REPLACE);
   }
   db.execSQL("DELETE FROM matches WHERE id IN (SELECT id FROM matches ORDER BY kickoff DESC,id DESC LIMIT -1 OFFSET 50000)");
   db.setTransactionSuccessful();
  }finally{db.endTransaction();}
 }
 public int count(){try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM matches",null)){return c.moveToFirst()?c.getInt(0):0;}}
 private JSONArray query(String where,String[] args,String limit)throws JSONException{
  JSONArray out=new JSONArray();try(Cursor c=getReadableDatabase().query("matches",new String[]{"payload"},where,args,null,null,"kickoff DESC,id DESC",limit)){while(c.moveToNext())out.put(new JSONObject(c.getString(0)));}return out;
 }
 public JSONArray history(int team,long cutoff)throws JSONException{return query("(home=? OR away=?) AND kickoff<?",new String[]{String.valueOf(team),String.valueOf(team),String.valueOf(cutoff)},"20");}
 public JSONArray pair(int home,int away,long cutoff)throws JSONException{return query("((home=? AND away=?) OR (home=? AND away=?)) AND kickoff<?",new String[]{String.valueOf(home),String.valueOf(away),String.valueOf(away),String.valueOf(home),String.valueOf(cutoff)},"10");}
}
