package com.potraderbot;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class SupabaseSharingClient {
    public static final class Connection {
        public final String id, requesterId, recipientId, status;
        Connection(JSONObject o) {
            id=o.optString("id"); requesterId=o.optString("requester_id");
            recipientId=o.optString("recipient_id"); status=o.optString("status");
        }
        public String otherUser(String me) { return me.equals(requesterId) ? recipientId : requesterId; }
        public boolean incoming(String me) { return me.equals(recipientId); }
    }

    private final String baseUrl, anonKey;
    public SupabaseSharingClient(String url, String key) {
        baseUrl=url==null?"":url.trim().replaceAll("/+$","");
        anonKey=key==null?"":key.trim();
    }

    public void requestConnection(String myId, String friendId, String token) throws Exception {
        if (myId.equals(friendId)) throw new IllegalArgumentException("Нельзя отправить приглашение самому себе");
        JSONObject body=new JSONObject();
        body.put("requester_id",myId); body.put("recipient_id",friendId); body.put("status","pending");
        call("/rest/v1/signal_connections","POST",token,body,"return=representation");
    }

    public List<Connection> connections(String myId, String token) throws Exception {
        String filter=URLEncoder.encode("(requester_id.eq."+myId+",recipient_id.eq."+myId+")","UTF-8");
        JSONArray a=new JSONArray(call("/rest/v1/signal_connections?select=id,requester_id,recipient_id,status&or="+filter+"&order=created_at.desc",
                "GET",token,null,null));
        List<Connection> out=new ArrayList<>();
        for(int i=0;i<a.length();i++) out.add(new Connection(a.getJSONObject(i)));
        return out;
    }

    public void respond(String connectionId, String status, String token) throws Exception {
        if (!"accepted".equals(status) && !"rejected".equals(status) && !"blocked".equals(status))
            throw new IllegalArgumentException("Недопустимый ответ на приглашение");
        JSONObject body=new JSONObject(); body.put("status",status);
        call("/rest/v1/signal_connections?id=eq."+URLEncoder.encode(connectionId,"UTF-8"),
                "PATCH",token,body,"return=minimal");
    }

    public void sendSignal(String myId, String friendId, String connectionId, String pair,
                           String direction, int expiry, String rationale, String token) throws Exception {
        if (!"CALL".equals(direction) && !"PUT".equals(direction)) return;
        JSONObject body=new JSONObject();
        body.put("sender_id",myId); body.put("recipient_id",friendId);
        body.put("connection_id",connectionId); body.put("pair",pair);
        body.put("direction",direction); body.put("timeframe","1m");
        body.put("expiry_minutes",expiry); body.put("rationale",rationale==null?"":rationale);
        call("/rest/v1/trade_signals","POST",token,body,"return=minimal");
    }

    public String recentSignals(String myId, String token) throws Exception {
        String q="/rest/v1/trade_signals?select=id,sender_id,recipient_id,pair,direction,expiry_minutes,rationale,status,created_at"
                +"&or="+URLEncoder.encode("(sender_id.eq."+myId+",recipient_id.eq."+myId+")","UTF-8")
                +"&order=created_at.desc&limit=30";
        return call(q,"GET",token,null,null);
    }

    private String call(String path,String method,String token,JSONObject body,String prefer) throws Exception {
        if(!baseUrl.startsWith("https://")||anonKey.isEmpty()||token==null||token.isEmpty())
            throw new IllegalStateException("Для обмена сигналами нужен активный аккаунт");
        HttpURLConnection c=(HttpURLConnection)new URL(baseUrl+path).openConnection();
        try {
            c.setRequestMethod(method); c.setConnectTimeout(12000); c.setReadTimeout(15000);
            c.setRequestProperty("apikey",anonKey); c.setRequestProperty("Authorization","Bearer "+token);
            c.setRequestProperty("Accept","application/json");
            if(prefer!=null)c.setRequestProperty("Prefer",prefer);
            if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
                byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
                try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
            int code=c.getResponseCode();
            String response=read(code>=200&&code<300?c.getInputStream():c.getErrorStream());
            if(code<200||code>=300)throw new IllegalStateException("Supabase sharing HTTP "+code+": "+response);
            return response;
        } finally {c.disconnect();}
    }
    private static String read(InputStream in)throws Exception{
        if(in==null)return "";
        try(InputStream input=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[2048];int n;while((n=input.read(b))!=-1)out.write(b,0,n);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
}
