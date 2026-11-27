package com.potraderbot;
import java.util.*;
import java.text.SimpleDateFormat;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.nio.charset.StandardCharsets;
/** Football-Data league CSV; independent of API-SPORTS identity and quota. */
public final class SportCsvHistory {
 private final List<Match> rows=new ArrayList<>();
 private static final Set<String> CODES=new HashSet<>(Arrays.asList("E0","E1","E2","E3","SP1","SP2","D1","D2","I1","I2","F1","F2","N1","P1","B1","SC0","T1","G1"));
 private static class Match {String home,away;long time;int hg,ag,hh,ah,hc,ac,hy,ay;}
 public static String season(){
  Calendar c=Calendar.getInstance(TimeZone.getTimeZone("UTC"));int y=c.get(Calendar.YEAR);if(c.get(Calendar.MONTH)<Calendar.JULY)y--;
  return String.format(Locale.US,"%02d%02d",y%100,(y+1)%100);
 }
 public static String download(String code,String season)throws Exception{
  if(!CODES.contains(code)||!season.matches("[0-9]{4}"))throw new IllegalArgumentException();
  HttpsURLConnection c=(HttpsURLConnection)new URL("https://www.football-data.co.uk/mmz4281/"+season+"/"+code+".csv").openConnection();
  try{
   c.setInstanceFollowRedirects(false);c.setConnectTimeout(12000);c.setReadTimeout(20000);
   if(c.getResponseCode()!=200)throw new IOException("HTTP");
   try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
    byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>2000000)throw new IOException("size");out.write(buf,0,n);}
    return new String(out.toByteArray(),StandardCharsets.UTF_8);
   }
  }finally{c.disconnect();}
 }
 // RFC-style quoted fields, escaped quotes, CRLF and quoted commas/newlines.
 static List<List<String>> csv(String raw)throws IOException{
  List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false;
  for(int i=0;i<raw.length();i++){
   char c=raw.charAt(i);if(i==0&&c=='\uFEFF')continue;
   if(c=='"'){if(quoted&&i+1<raw.length()&&raw.charAt(i+1)=='"'){cell.append('"');i++;}else quoted=!quoted;}
   else if(!quoted&&(c==','||c=='\n'||c=='\r')){
    row.add(cell.toString());cell.setLength(0);
    if(c!=','){rows.add(row);row=new ArrayList<>();if(c=='\r'&&i+1<raw.length()&&raw.charAt(i+1)=='\n')i++;}
   }else cell.append(c);
  }
  if(quoted)throw new IOException("unclosed quote");if(cell.length()>0||!row.isEmpty()){row.add(cell.toString());rows.add(row);}return rows;
 }
 private static String field(List<String> row,Map<String,Integer> index,String key){Integer i=index.get(key);return i==null||i>=row.size()?"":row.get(i).trim();}
 private static int number(List<String> row,Map<String,Integer> i,String key){try{int v=Integer.parseInt(field(row,i,key));return v>=0&&v<=100?v:-1;}catch(Exception e){return -1;}}
 private static long date(String value){
  for(String pattern:new String[]{"dd/MM/yyyy","dd/MM/yy"}){
   if(pattern.endsWith("yyyy")?!value.matches("[0-9]{2}/[0-9]{2}/[0-9]{4}"):!value.matches("[0-9]{2}/[0-9]{2}/[0-9]{2}"))continue;
   try{SimpleDateFormat f=new SimpleDateFormat(pattern,Locale.US);f.setLenient(false);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.parse(value).getTime();}catch(Exception ignored){}
  }return -1;
 }
 public static SportCsvHistory parse(String raw,String code)throws IOException{
  Calendar c=Calendar.getInstance(TimeZone.getTimeZone("UTC"));c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);
  return parseBefore(raw,code,c.getTimeInMillis());
 }
 static SportCsvHistory parseBefore(String raw,String code,long cutoff)throws IOException{
  if(!CODES.contains(code))throw new IOException("league");List<List<String>> table=csv(raw);if(table.isEmpty())throw new IOException("empty");
  Map<String,Integer> i=new HashMap<>();for(int j=0;j<table.get(0).size();j++)i.put(table.get(0).get(j).trim(),j);
  for(String key:new String[]{"Div","Date","HomeTeam","AwayTeam","FTHG","FTAG"})if(!i.containsKey(key))throw new IOException("missing column");
  SportCsvHistory data=new SportCsvHistory();Set<String> seen=new HashSet<>();
  for(int j=1;j<table.size();j++){
   List<String> r=table.get(j);if(!code.equals(field(r,i,"Div")))continue;
   Match m=new Match();m.home=field(r,i,"HomeTeam");m.away=field(r,i,"AwayTeam");m.time=date(field(r,i,"Date"));m.hg=number(r,i,"FTHG");m.ag=number(r,i,"FTAG");
   if(m.home.isEmpty()||m.away.isEmpty()||m.home.equals(m.away)||m.time<=0||m.time>=cutoff||m.hg<0||m.ag<0)continue;
   if(!seen.add(m.time+"\n"+m.home+"\n"+m.away))continue;
   m.hh=number(r,i,"HTHG");m.ah=number(r,i,"HTAG");m.hc=number(r,i,"HC");m.ac=number(r,i,"AC");m.hy=number(r,i,"HY");m.ay=number(r,i,"AY");data.rows.add(m);
  }
  Collections.sort(data.rows,(a,b)->Long.compare(b.time,a.time));return data;
 }
 public int size(){return rows.size();}
 public String[] teams(){Set<String> names=new TreeSet<>();for(Match m:rows){names.add(m.home);names.add(m.away);}return names.toArray(new String[0]);}
 public static final class Team {
  public final SportHistory.Summary goals=new SportHistory.Summary();int cn,cf,ca,yn,yf,ya;
  int recentN,recentPoints,recentGF,recentGA,homeN,homeGF,homeGA,awayN,awayGF,awayGA,secondGF,secondGA,cleanSheets,scoreless;
  StringBuilder form=new StringBuilder();
  public String formDetails(){
   return "Последние "+recentN+" (от нового к старому): "+(recentN==0?"нет данных":form.toString())+
    "\nОчки за матч (последние 5): "+average(recentN,recentPoints)+
    "\nГолы забито / пропущено (последние 5): "+mean(recentN,recentGF,recentGA)+
    "\nДома: "+mean(homeN,homeGF,homeGA)+
    "\nВ гостях: "+mean(awayN,awayGF,awayGA)+
    "\nВторой тайм: "+mean(goals.htN,secondGF,secondGA)+
    "\nСухие матчи: "+cleanSheets+"/"+goals.n+" • без забитых: "+scoreless+"/"+goals.n;
  }
  private static String average(int n,int x){return n==0?"нет данных":String.format(Locale.US,"%.2f",x/(double)n);}
  public String details(){return "Угловые (свои / соперника): "+mean(cn,cf,ca)+"\nЖК (свои / соперника): "+mean(yn,yf,ya);}
  private static String mean(int n,int f,int a){return n==0?"нет данных":String.format(Locale.US,"%.2f / %.2f (%d матчей)",f/(double)n,a/(double)n,n);}
 }
 public static final class Replay {
  int tested,skipped,baseCorrect,venueCorrect,baselineCorrect;
  double baseBrier,venueBrier,baselineBrier;
  private static int best(double[] p){int b=0;for(int i=1;i<3;i++)if(p[i]>p[b])b=i;return b;}
  private static double brier(double[] p,int actual){double sum=0;for(int i=0;i<3;i++){double d=p[i]-(i==actual?1:0);sum+=d*d;}return sum;}
  void add(double[] base,double[] venue,double[] baseline,int actual){
   tested++;if(best(base)==actual)baseCorrect++;if(best(venue)==actual)venueCorrect++;if(best(baseline)==actual)baselineCorrect++;
   baseBrier+=brier(base,actual);venueBrier+=brier(venue,actual);baselineBrier+=brier(baseline,actual);
  }
  private String metric(String label,int correct,double score){return String.format(Locale.US,"%s: %.1f%% (%d/%d) • Brier %.4f",label,100.0*correct/tested,correct,tested,score/tested);}
  public String report(){if(tested==0)return "Недостаточно исторических матчей для проверки. Пропущено: "+skipped;
   return "Проверено: "+tested+" • пропущено: "+skipped+"\n"+
    metric("Пуассон: общая форма",baseCorrect,baseBrier)+"\n"+
    metric("Пуассон: с учётом поля",venueCorrect,venueBrier)+"\n"+
    metric("Базовая частота исходов лиги",baselineCorrect,baselineBrier)+
    "\n\nBrier 1X2: 0 — идеально, меньше — лучше (диапазон 0–2). Точность — доля угаданных наиболее вероятных исходов. Одинаковая выборка для всех моделей. Это ретроспективная оценка, не гарантия будущих результатов.";
  }
 }
 public Replay replay(){
  Replay r=new Replay();
  // Limit work to the most recent 200 fixtures, use only earlier calendar days.
  for(int i=Math.min(200,rows.size())-1;i>=0;i--){
   Match m=rows.get(i);Team h=teamBefore(m.home,m.time),a=teamBefore(m.away,m.time);
   if(h.goals.n<5||a.goals.n<5||h.homeN<3||a.awayN<3){r.skipped++;continue;}
   double x=(h.goals.gf/(double)h.goals.n+a.goals.ga/(double)a.goals.n)/2;
   double y=(a.goals.gf/(double)a.goals.n+h.goals.ga/(double)h.goals.n)/2;
   double vx=(h.homeGF/(double)h.homeN+a.awayGA/(double)a.awayN)/2;
   double vy=(a.awayGF/(double)a.awayN+h.homeGA/(double)h.homeN)/2;
   double[] freq={1,1,1};double n=3;
   for(Match old:rows)if(old.time<m.time){freq[old.hg>old.ag?0:old.hg==old.ag?1:2]++;n++;}
   for(int j=0;j<3;j++)freq[j]/=n;
   r.add(SportAutoModel.outcomes(x,y),SportAutoModel.outcomes(vx,vy),freq,m.hg>m.ag?0:m.hg==m.ag?1:2);
  }
  return r;
 }
 public Team team(String name){return teamBefore(name,Long.MAX_VALUE);}
 private Team teamBefore(String name,long cutoff){
  Team t=new Team();SportHistory.Summary s=t.goals;
  for(Match m:rows){
   if(m.time>=cutoff)continue;
   boolean h=name.equals(m.home);if(!h&&!name.equals(m.away))continue;if(s.n>=20)break;
   int f=h?m.hg:m.ag,a=h?m.ag:m.hg;s.n++;s.gf+=f;s.ga+=a;if(f>a)s.w++;else if(f==a)s.d++;else s.l++;if(f>0&&a>0)s.both++;
   if(s.n<=5){t.recentN++;t.recentPoints+=f>a?3:f==a?1:0;t.recentGF+=f;t.recentGA+=a;if(t.form.length()>0)t.form.append(" ");t.form.append(f>a?"В":f==a?"Н":"П");}
   if(h){t.homeN++;t.homeGF+=f;t.homeGA+=a;}else{t.awayN++;t.awayGF+=f;t.awayGA+=a;}if(a==0)t.cleanSheets++;if(f==0)t.scoreless++;
   if(m.hh>=0&&m.ah>=0&&m.hh<=m.hg&&m.ah<=m.ag){s.htN++;s.htFor+=h?m.hh:m.ah;s.htAgainst+=h?m.ah:m.hh;if(m.hh+m.ah>0)s.htGoal++;t.secondGF+=f-(h?m.hh:m.ah);t.secondGA+=a-(h?m.ah:m.hh);}
   if(m.hc>=0&&m.ac>=0){t.cn++;t.cf+=h?m.hc:m.ac;t.ca+=h?m.ac:m.hc;}
   if(m.hy>=0&&m.ay>=0){t.yn++;t.yf+=h?m.hy:m.ay;t.ya+=h?m.ay:m.hy;}
  }return t;
 }
}
