package com.potraderbot;

import android.app.Activity;
import android.os.Bundle;
import android.net.Uri;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebChromeClient;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Separate, fixed-origin GoD AI chat. No native bridge, no account transfer. */
public final class AssistantActivity extends Activity {
    private static final String HOST="god-ai-core.onrender.com";
    private static final String CHAT="https://"+HOST+"/chat";
    private WebView web;
    private TextView status;
    private boolean closing;
    static boolean trusted(Uri uri){
        if(uri==null||!"https".equalsIgnoreCase(uri.getScheme())||!HOST.equalsIgnoreCase(uri.getHost())
            ||uri.getUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))return false;
        String path=uri.getPath();
        if(path==null)return true;
        return !path.startsWith("/device-action/")&&!"/device-action".equals(path);
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView label(String text,int color,int size){
        TextView t=new TextView(this);t.setText(text);t.setTextColor(color);t.setTextSize(size);
        t.setPadding(dp(10),dp(8),dp(10),dp(8));return t;
    }
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(0xff090e15);getWindow().setNavigationBarColor(0xff090e15);
        getWindow().getDecorView().setSystemUiVisibility(0);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xff090e15);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back=label("‹ Назад",0xfff5c65a,16);back.setOnClickListener(v->closeChat());header.addView(back);
        TextView brand=label("♛ GoD AI",0xfff5c65a,20);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView reload=label("↻",0xfff5c65a,22);header.addView(reload);root.addView(header);
        status=label("Отдельный вход · защищённый чат · без передачи торговых данных",0xffb6c0cf,12);root.addView(status);
        web=new WebView(this);web.setBackgroundColor(Color.BLACK);
        root.addView(web,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(false);
        s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(false);s.setSupportMultipleWindows(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);s.setGeolocationEnabled(false);
        CookieManager cm=CookieManager.getInstance();cm.setAcceptCookie(true);cm.setAcceptThirdPartyCookies(web,false);
        web.setWebChromeClient(new WebChromeClient());
        web.setDownloadListener((url,agent,disposition,type,length)->status.setText("Для загрузки файлов откройте отдельное приложение GoD AI."));
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){
                if(trusted(r.getUrl()))return false;
                if(r.isForMainFrame())status.setText("Внешняя ссылка или действие устройства заблокировано. Используйте отдельное приложение GoD AI.");
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                return trusted(r.getUrl())?null:new WebResourceResponse("text/plain","UTF-8",403,"Forbidden",Collections.emptyMap(),new ByteArrayInputStream("Blocked".getBytes(StandardCharsets.UTF_8)));
            }
            @Override public void onPageFinished(WebView v,String url){
                super.onPageFinished(v,url);
                if(!trusted(Uri.parse(url)))v.stopLoading();
            }
            @Override public void onReceivedError(WebView v,WebResourceRequest r,android.webkit.WebResourceError e){
                if(r.isForMainFrame())status.setText("Сервер GoD AI недоступен. Нажмите ↻ для повторной попытки.");
            }
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest r,WebResourceResponse e){
                if(r.isForMainFrame())status.setText("Сервер GoD AI: HTTP "+e.getStatusCode()+". Нажмите ↻.");
            }
        });
        reload.setOnClickListener(v->{if(!closing){status.setText("Отдельный вход · подключение к GoD AI");web.loadUrl(CHAT);}});
        // Cookie storage belongs to this application only; clear it before a new assistant session.
        reload.setEnabled(false);
        cm.removeAllCookies(ignored->{cm.flush();if(!closing&&!isFinishing()&&!isDestroyed()){reload.setEnabled(true);web.loadUrl(CHAT);}});
    }
    @Override public void onBackPressed(){closeChat();}
    private void closeChat(){
        if(closing)return;closing=true;
        if(web!=null){web.stopLoading();web.loadUrl("about:blank");}
        CookieManager.getInstance().removeAllCookies(ignored->{CookieManager.getInstance().flush();if(!isFinishing())finish();});
    }
    @Override protected void onDestroy(){
        closing=true;
        if(web!=null){web.stopLoading();web.clearHistory();web.destroy();web=null;}
        CookieManager.getInstance().removeAllCookies(ignored->CookieManager.getInstance().flush());
        super.onDestroy();
    }
}
