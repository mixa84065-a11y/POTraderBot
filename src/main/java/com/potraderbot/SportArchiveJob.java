package com.potraderbot;
import android.app.job.*;
import android.content.*;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;
/** Explicitly enabled, bounded local archive collection. No new server or external storage. */
public final class SportArchiveJob extends JobService{
 private static final int ID=73195;
 private static final Object LOCK=new Object();
 private volatile boolean stopped;
 private Thread worker;
 public static boolean schedule(Context c){
  JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
  return js!=null&&js.schedule(new JobInfo.Builder(ID,new ComponentName(c,SportArchiveJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(6L*60*60*1000).setPersisted(true).build())==JobScheduler.RESULT_SUCCESS;
 }
 public static void cancel(Context c){JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);if(js!=null)js.cancel(ID);}
 private static String date(Calendar c){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(c.getTime());}
 private static JSONArray fetch(SharedPreferences p,String target,String token)throws Exception{
  HttpsURLConnection c=(HttpsURLConnection)new URL("https://v3.football.api-sports.io/fixtures?date="+target+"&timezone=UTC").openConnection();
  try{
   c.setInstanceFollowRedirects(false);c.setConnectTimeout(12000);c.setReadTimeout(20000);c.setRequestProperty("x-apisports-key",token);
   int code=c.getResponseCode();SportSyncJob.updateQuota(p,c.getHeaderField("x-ratelimit-requests-remaining"));
   if(code!=200)throw new IllegalStateException("HTTP "+code);
   JSONObject data;
   try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
    byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>8000000)throw new IOException();out.write(b,0,n);}
    data=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
   }
   Object e=data.opt("errors");if((e instanceof JSONObject&&((JSONObject)e).length()>0)||(e instanceof JSONArray&&((JSONArray)e).length()>0))throw new IllegalStateException(SportArchive.errorCategory(e));
   return data.getJSONArray("response");
  }finally{c.disconnect();}
 }
 @Override public boolean onStartJob(JobParameters params){
  stopped=false;SharedPreferences p=getSharedPreferences("gos_local",MODE_PRIVATE);
  if(!p.getBoolean("gos_archive_auto",false))return false;
  worker=new Thread(()->{
   try{
    Calendar c=Calendar.getInstance(TimeZone.getTimeZone("UTC"));String today=date(c);
    // Wait until 06:00 UTC so late fixtures are more likely to have final scores.
    if(c.get(Calendar.HOUR_OF_DAY)<6)return;
    c.add(Calendar.DATE,-1);String target=date(c);
    String token=SportSyncJob.readKey(p);if(token.isEmpty()||stopped)return;
    synchronized(LOCK){
     if(!p.getBoolean("gos_archive_auto",false)||today.equals(p.getString("gos_archive_attempt_day","")))return;
     SportSyncJob.reserve(p,true);
     if(!p.edit().putString("gos_archive_attempt_day",today).commit())return;
    }
    if(stopped||!p.getBoolean("gos_archive_auto",false))return;
    JSONArray rows=fetch(p,target,token);
    if(stopped||!p.getBoolean("gos_archive_auto",false))return;
    try(SportLocalArchive db=new SportLocalArchive(getApplicationContext())){
     db.ingest(rows);p.edit().putString("gos_archive_status",target+" UTC: получено "+rows.length()+" записей; база "+db.count()+" завершённых матчей").apply();
    }
   }catch(IllegalStateException e){p.edit().putString("gos_archive_status",e.getMessage()).apply();}
    catch(Exception e){p.edit().putString("gos_archive_status","Ошибка соединения или записи; повтор в следующие сутки.").apply();}
    finally{if(!stopped)jobFinished(params,false);}
  },"GoS-local-archive");worker.start();return true;
 }
 @Override public boolean onStopJob(JobParameters params){stopped=true;if(worker!=null)worker.interrupt();return false;}
}
