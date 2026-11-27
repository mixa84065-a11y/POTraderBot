package com.potraderbot;

import android.app.job.*;
import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;

public final class SportSyncJob extends JobService {
 private static final int JOB_ID=73194;
 private volatile boolean stopped;
 private Thread worker;
 public static boolean schedule(Context c){
  JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
  return js!=null&&js.schedule(new JobInfo.Builder(JOB_ID,new ComponentName(c,SportSyncJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(6L*60*60*1000).setPersisted(true).build())==JobScheduler.RESULT_SUCCESS;
 }
 public static void cancel(Context c){JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);if(js!=null)js.cancel(JOB_ID);}
 private static String day(){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date());}
 static int remaining(SharedPreferences p){return day().equals(p.getString("quota_day",""))?p.getInt("quota_remaining",-1):-1;}
 static void reserve(SharedPreferences p,boolean background)throws Exception{
  synchronized(SportActivity.QUOTA_LOCK){
   String d=day();boolean same=d.equals(p.getString("quota_day",""));
   int count=same?p.getInt("quota_count",0):0,left=same?p.getInt("quota_remaining",-1):-1;
   if(!same&&d.equals(p.getString("history_budget_day","")))count=Math.max(count,p.getInt("history_budget_count",0));
   int bg=d.equals(p.getString("gos_bg_day",""))?p.getInt("gos_bg_count",0):0;
   if(count>=100||left==0||(background&&(bg>=8||count>=90||(left>=0&&left<=10))))throw new IllegalStateException("Лимит запросов исчерпан.");
   SharedPreferences.Editor e=p.edit().putString("quota_day",d).putInt("quota_count",count+1).putInt("quota_remaining",left<0?-1:left-1);
   if(background)e.putString("gos_bg_day",d).putInt("gos_bg_count",bg+1);
   if(!e.commit())throw new IllegalStateException("Не удалось сохранить счётчик.");
  }
 }
 static void updateQuota(SharedPreferences p,String header){
  synchronized(SportActivity.QUOTA_LOCK){
   if(!day().equals(p.getString("quota_day","")))return;
   try{int reported=Integer.parseInt(header);if(reported<0)return;int saved=remaining(p);p.edit().putInt("quota_remaining",saved<0?reported:Math.min(saved,reported)).commit();}catch(Exception ignored){}
  }
 }
 static String readKey(SharedPreferences p)throws Exception{
  String enc=p.getString("cipher","");if(enc.isEmpty())return "";
  KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
  SecretKey key=(SecretKey)ks.getKey("gos_api_sports_v1",null);if(key==null)return "";
  Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Base64.decode(p.getString("iv",""),Base64.NO_WRAP)));
  return new String(cipher.doFinal(Base64.decode(enc,Base64.NO_WRAP)),StandardCharsets.UTF_8);
 }
 private static JSONArray result(SharedPreferences p,long id,String token)throws Exception{
  reserve(p,true);
  HttpsURLConnection c=(HttpsURLConnection)new URL("https://v3.football.api-sports.io/fixtures?id="+id).openConnection();
  try{
   c.setInstanceFollowRedirects(false);c.setConnectTimeout(12000);c.setReadTimeout(20000);c.setRequestProperty("x-apisports-key",token);
   int code=c.getResponseCode();updateQuota(p,c.getHeaderField("x-ratelimit-requests-remaining"));if(code!=200)throw new IOException();
   JSONObject j;
   try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
    byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>1000000)throw new IOException();out.write(b,0,n);}
    j=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
   }
   Object e=j.opt("errors");if((e instanceof JSONObject&&((JSONObject)e).length()>0)||(e instanceof JSONArray&&((JSONArray)e).length()>0))throw new IOException();
   return j.getJSONArray("response");
  }finally{c.disconnect();}
 }
 @Override public boolean onStartJob(JobParameters params){
  stopped=false;final SharedPreferences p=getSharedPreferences("gos_local",MODE_PRIVATE);
  if(!p.getBoolean("gos_auto_sync",false))return false;
  worker=new Thread(()->{
   try{
    String token=readKey(p);if(token.isEmpty())return;
    JSONObject snapshot=new JSONObject(p.getString("forecast_journal_v1","{}"));List<String> eligible=new ArrayList<>();Iterator<String> it=snapshot.keys();long now=System.currentTimeMillis()/1000;
    while(it.hasNext()){String k=it.next();JSONObject e=snapshot.optJSONObject(k);if(e!=null&&!SportForecastJournal.closed(e)&&now>=e.optLong("cutoff")+7200)eligible.add(k);}
    Collections.sort(eligible,(a,b)->Long.compare(p.getLong("gos_bg_attempt_"+a,0),p.getLong("gos_bg_attempt_"+b,0)));
    int checked=0;
    for(String k:eligible){
     if(stopped||!p.getBoolean("gos_auto_sync",false)||checked>=2)break;
     long id=Long.parseLong(k);if(id<=0)continue;checked++;
     p.edit().putLong("gos_bg_attempt_"+k,System.currentTimeMillis()).apply();
     JSONArray rows=result(p,id,token);
     if(stopped||!p.getBoolean("gos_auto_sync",false))break;
     synchronized(SportActivity.JOURNAL_LOCK){
      JSONObject current=new JSONObject(p.getString("forecast_journal_v1","{}"));JSONObject entry=current.optJSONObject(k);
      if(entry==null||SportForecastJournal.closed(entry))continue;
      for(int i=0;i<rows.length();i++){if(SportForecastJournal.settle(entry,id,rows.optJSONObject(i))){p.edit().putString("forecast_journal_v1",current.toString()).commit();break;}}
     }
    }
    p.edit().putLong("gos_bg_last",System.currentTimeMillis()).apply();
   }catch(Exception ignored){}finally{if(!stopped)jobFinished(params,false);}
  },"GoS-result-sync");worker.start();return true;
 }
 @Override public boolean onStopJob(JobParameters params){stopped=true;if(worker!=null)worker.interrupt();return false;}
}
