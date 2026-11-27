package com.potraderbot;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Test APK only. No production bypass, no credentials, no market actions. */
public final class DashboardSmokeRunner extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private void main(Runnable action)throws Exception{
        AtomicReference<Throwable> error=new AtomicReference<>();
        runOnMainSync(()->{try{action.run();}catch(Throwable t){error.set(t);}});
        if(error.get()!=null)throw new Exception("UI assertion",error.get());
        waitForIdleSync();
    }
    private static View find(View v,String prefix){
        CharSequence desc=v.getContentDescription();
        if(desc!=null&&(desc.toString().equals(prefix)||desc.toString().startsWith(prefix+". ")))return v;
        if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int i=0;i<group.getChildCount();i++){View child=find(group.getChildAt(i),prefix);if(child!=null)return child;}}
        return null;
    }
    private static TextView text(View v,String value){
        if(v instanceof TextView&&value.contentEquals(((TextView)v).getText()))return (TextView)v;
        if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int i=0;i<group.getChildCount();i++){TextView child=text(group.getChildAt(i),value);if(child!=null)return child;}}
        return null;
    }
    private static ScrollView scroll(View v){
        if(v instanceof ScrollView)return (ScrollView)v;
        if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int i=0;i<group.getChildCount();i++){ScrollView child=scroll(group.getChildAt(i));if(child!=null)return child;}}
        return null;
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void screenshot(String filename)throws Exception{
        Bitmap b=getUiAutomation().takeScreenshot();
        if(b==null)throw new IllegalStateException("Screenshot unavailable");
        try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getFilesDir(),filename))){
            if(!b.compress(Bitmap.CompressFormat.PNG,100,out))throw new IllegalStateException("Screenshot encoding failed");
        }finally{b.recycle();}
    }
    @Override public void onStart(){
        Bundle result=new Bundle();Activity activity=null;
        try{
            Intent intent=new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity=startActivitySync(intent);final Activity screen=activity;
            waitForIdleSync();
            // A fresh emulator must show the real login screen first.
            main(()->require(text(screen.getWindow().getDecorView(),"Войти")!=null
                ||text(screen.getWindow().getDecorView(),"ВХОД")!=null
                ||containsLogin(screen.getWindow().getDecorView()),"Expected login screen on clean install"));
            screenshot("ui-smoke-login.png");
            List<String> clicks=new ArrayList<>();
            AtomicReference<View> root=new AtomicReference<>();
            main(()->{View view=new AnalyticsDashboard(screen,clicks::add).build();root.set(view);screen.setContentView(view);});
            main(()->{
                View view=root.get();
                require(view.getWidth()>0&&view.getHeight()>0,"Dashboard not laid out");
                require(text(view,"GOD OF TRADING 2.0")!=null,"Missing title");
                for(String name:new String[]{"Трейдинг","Спорт","Криптосканер","Инвестиции","Новости","Финансы","Лаборатория","Обучение","GoD AI"}){
                    TextView title=text(view,name);
                    require(title!=null&&title.getWidth()>0&&title.getHeight()>0,"Missing or empty title: "+name);
                    require(title.getLayout()!=null&&title.getLayout().getLineCount()>0,"Unlaid title: "+name);
                    int last=title.getLayout().getLineCount()-1;
                    require(title.getLayout().getEllipsisCount(last)==0,"Ellipsized title: "+name);
                }
                for(String name:new String[]{"Главная","Статистика","Сигналы","Настройки"})require(find(view,name)!=null,"Missing navigation: "+name);
                require(scroll(view)!=null,"Missing scroll container");
            });
            screenshot("ui-smoke-dashboard.png");
            main(()->{ScrollView s=scroll(root.get());s.fullScroll(View.FOCUS_DOWN);});
            screenshot("ui-smoke-dashboard-bottom.png");
            main(()->{
                for(String name:new String[]{"Трейдинг","Спорт","Криптосканер","Финансы","Настройки"}){
                    View target=find(root.get(),name);require(target!=null&&target.performClick(),"Click failed: "+name);
                }
                require(clicks.equals(java.util.Arrays.asList("trade","sport","crypto","finance","settings")),"Incorrect navigation callback: "+clicks);
            });
            result.putString("stream","\nDASHBOARD_SMOKE_OK\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable e){
            result.putString("stream","\nDASHBOARD_SMOKE_FAILED: "+android.util.Log.getStackTraceString(e)+"\n");
            finish(Activity.RESULT_CANCELED,result);
        }finally{if(activity!=null){final Activity a=activity;try{runOnMainSync(a::finish);}catch(Exception ignored){}}}
    }
    private static boolean containsLogin(View v){
        if(v instanceof TextView){String t=((TextView)v).getText().toString().toLowerCase(java.util.Locale.ROOT);if(t.contains("войти")||t.contains("авторизац")||t.contains("вход"))return true;}
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)if(containsLogin(g.getChildAt(i)))return true;}
        return false;
    }
}
