package com.potraderbot;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.URL;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;

/** Only public crest URLs supplied by GoS; never send football API credentials. */
final class TeamLogoLoader {
    private static final int MAX_REQUESTS=64, MAX_BYTES=256*1024;
    private final Activity activity;
    private final ExecutorService pool=Executors.newFixedThreadPool(2);
    private final LruCache<Integer,Bitmap> cache=new LruCache<Integer,Bitmap>(2*1024*1024){
        @Override protected int sizeOf(Integer id,Bitmap image){return image.getByteCount();}
    };
    private final HashMap<Integer,ArrayList<Target>> pending=new HashMap<>();
    private final HashSet<Integer> attempted=new HashSet<>();
    private boolean closed;
    private static final class Target{
        final WeakReference<ImageView> image;final WeakReference<TextView> fallback;
        Target(ImageView i,TextView f){image=new WeakReference<>(i);fallback=new WeakReference<>(f);}
        void show(Bitmap b){ImageView i=image.get();TextView f=fallback.get();if(i!=null&&f!=null){i.setImageBitmap(b);i.setVisibility(View.VISIBLE);f.setVisibility(View.INVISIBLE);}}
    }
    TeamLogoLoader(Activity a){activity=a;}
    static boolean allowed(int id,String raw){
        if(id<=0||raw==null||raw.length()>250)return false;
        try{
            URI u=new URI(raw);
            return "https".equals(u.getScheme())&&"media.api-sports.io".equalsIgnoreCase(u.getHost())
                &&(u.getPort()==-1||u.getPort()==443)&&u.getRawUserInfo()==null&&u.getRawQuery()==null&&u.getRawFragment()==null
                &&("/football/teams/"+id+".png").equals(u.getRawPath());
        }catch(Exception e){return false;}
    }
    void bind(int id,String raw,ImageView image,TextView fallback){
        if(closed||!allowed(id,raw))return;
        Target target=new Target(image,fallback);Bitmap ready=cache.get(id);
        if(ready!=null){target.show(ready);return;}
        ArrayList<Target> waiting=pending.get(id);
        if(waiting!=null){if(waiting.size()<200)waiting.add(target);return;}
        if(attempted.size()>=MAX_REQUESTS||!attempted.add(id))return;
        waiting=new ArrayList<>();waiting.add(target);pending.put(id,waiting);
        pool.execute(()->{
            Bitmap loaded=null;
            try{loaded=fetch(raw);}catch(Exception ignored){}
            final Bitmap b=loaded;activity.runOnUiThread(()->{
                ArrayList<Target> targets=pending.remove(id);
                if(closed||activity.isFinishing()||activity.isDestroyed()||b==null)return;
                cache.put(id,b);if(targets!=null)for(Target t:targets)t.show(b);
            });
        });
    }
    private static Bitmap fetch(String raw)throws Exception{
        HttpsURLConnection c=(HttpsURLConnection)new URL(raw).openConnection();
        c.setConnectTimeout(4000);c.setReadTimeout(5000);c.setInstanceFollowRedirects(false);
        try{
            if(c.getResponseCode()!=200)return null;
            String type=c.getContentType();if(type==null||!type.toLowerCase(java.util.Locale.ROOT).startsWith("image/png"))return null;
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){if(out.size()+n>MAX_BYTES)return null;out.write(buf,0,n);}
                byte[] bytes=out.toByteArray();
                BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);
                if(o.outWidth<1||o.outHeight<1||o.outWidth>1024||o.outHeight>1024)return null;
                o.inJustDecodeBounds=false;o.inSampleSize=1;while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>128)o.inSampleSize*=2;
                return BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);
            }
        }finally{c.disconnect();}
    }
    void close(){closed=true;pool.shutdownNow();pending.clear();cache.evictAll();}
}
