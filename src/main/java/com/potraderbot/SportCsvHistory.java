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
 private static final Set<String> CODES=new HashSet<>(Arrays.asList("E0","SP1","D1","I1","F1"));
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
  public String details(){return "Угловые (свои / соперника): "+mean(cn,cf,ca)+"\nЖК (свои / соперника): "+mean(yn,yf,ya);}
  private static String mean(int n,int f,int a){return n==0?"нет данных":String.format(Locale.US,"%.2f / %.2f (%d матчей)",f/(double)n,a/(double)n,n);}
 }
 public Team team(String name){
  Team t=new Team();SportHistory.Summary s=t.goals;
  for(Match m:rows){
   boolean h=name.equals(m.home);if(!h&&!name.equals(m.away))continue;if(s.n>=20)break;
   int f=h?m.hg:m.ag,a=h?m.ag:m.hg;s.n++;s.gf+=f;s.ga+=a;if(f>a)s.w++;else if(f==a)s.d++;else s.l++;if(f>0&&a>0)s.both++;
   if(m.hh>=0&&m.ah>=0&&m.hh<=m.hg&&m.ah<=m.ag){s.htN++;s.htFor+=h?m.hh:m.ah;s.htAgainst+=h?m.ah:m.hh;if(m.hh+m.ah>0)s.htGoal++;}
   if(m.hc>=0&&m.ac>=0){t.cn++;t.cf+=h?m.hc:m.ac;t.ca+=h?m.ac:m.hc;}
   if(m.hy>=0&&m.ay>=0){t.yn++;t.yf+=h?m.hy:m.ay;t.ya+=h?m.ay:m.hy;}
  }return t;
 }
}
