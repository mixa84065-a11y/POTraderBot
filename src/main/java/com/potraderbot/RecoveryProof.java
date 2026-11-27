package com.potraderbot;
import org.json.JSONObject;
import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;

final class RecoveryProof {
    static JSONObject body(String projectUrl,String email,String input)throws Exception{
        String proof=input.trim();
        JSONObject b=new JSONObject();b.put("type","recovery");
        if(proof.matches("[0-9]{6,10}")){b.put("email",email.trim());b.put("token",proof);return b;}
        URI expected=new URI(projectUrl),link=new URI(proof);
        if(!"https".equalsIgnoreCase(link.getScheme())||!expected.getHost().equalsIgnoreCase(link.getHost())||link.getUserInfo()!=null||link.getPort()!=-1||!"/auth/v1/verify".equals(link.getPath())||link.getFragment()!=null||link.getRawQuery()==null)
            throw new IllegalArgumentException("Неверная ссылка восстановления");
        HashMap<String,String> params=new HashMap<>();
        for(String pair:link.getRawQuery().split("&")){
            String[] kv=pair.split("=",2);
            if(kv.length!=2)throw new IllegalArgumentException("Неверная ссылка");
            String key=URLDecoder.decode(kv[0],"UTF-8");
            if(params.put(key,URLDecoder.decode(kv[1],"UTF-8"))!=null)throw new IllegalArgumentException("Повтор параметра");
        }
        String hash=params.get("token");
        if(!"recovery".equals(params.get("type"))||hash==null||!hash.matches("[A-Za-z0-9_-]{32,256}")||params.containsKey("token_hash"))throw new IllegalArgumentException("Неверный тип или токен");
        b.put("token_hash",hash);return b;
    }
}
