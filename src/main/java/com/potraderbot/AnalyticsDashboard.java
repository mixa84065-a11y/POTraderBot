package com.potraderbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.EditText;
import android.text.InputType;
import java.util.Locale;

/** Home screen only. No orders, network requests or account changes originate here. */
final class AnalyticsDashboard {
    interface Action { void open(String name); }
    private static final int BG=0xff090e15, GOLD=0xfff5c65a, WHITE=0xfff2f4f7, MUTED=0xffb6c0cf;
    private final Activity a;
    private final Action action;
    AnalyticsDashboard(Activity a, Action action){this.a=a;this.action=action;}
    private int dp(float n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(a);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(a);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String s,float size,int color,boolean bold){
        TextView t=new TextView(a);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        t.setIncludeFontPadding(false);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    private GradientDrawable gradient(int top,int bottom,int stroke,int radius){
        GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{top,bottom});
        g.setCornerRadius(dp(radius));if(stroke!=0)g.setStroke(dp(1),stroke);return g;
    }
    private void clickable(View v,int stroke,int top,int bottom,Runnable r){
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30ffffff),gradient(top,bottom,stroke,13),null));
        v.setClickable(true);v.setFocusable(true);v.setOnClickListener(x->r.run());
    }
    private void centered(LinearLayout p,String s,float size,int color,boolean bold,int top){
        TextView t=text(s,size,color,bold);t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(top);p.addView(t,lp);
    }
    private void space(LinearLayout p,int height){p.addView(new View(a),new LinearLayout.LayoutParams(-1,dp(height)));}
    private View nav(String symbol,String label,int color,Runnable r){
        LinearLayout item=column();item.setGravity(Gravity.CENTER);item.setPadding(dp(2),dp(4),dp(2),dp(4));
        DashboardIcon icon=new DashboardIcon(a,symbol,color);item.addView(icon,new LinearLayout.LayoutParams(dp(26),dp(27)));
        TextView t=text(label,11,color,false);t.setGravity(Gravity.CENTER);t.setPadding(0,dp(3),0,0);item.addView(t);
        item.setContentDescription(label);item.setOnClickListener(v->r.run());item.setFocusable(true);return item;
    }
    View build(){
        LinearLayout shell=column();shell.setBackgroundColor(BG);
        ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout p=column();p.setPadding(dp(12),dp(12),dp(12),dp(14));scroll.addView(p);
        LinearLayout top=row();
        View profile=nav("profile","Профиль",WHITE,()->action.open("profile"));
        top.addView(profile,new LinearLayout.LayoutParams(dp(74),-2));
        top.addView(new View(a),new LinearLayout.LayoutParams(0,1,1));
        View notifications=nav("bell","Сигналы",WHITE,()->action.open("signals"));
        top.addView(notifications,new LinearLayout.LayoutParams(dp(74),-2));p.addView(top);
        DashboardIcon crown=new DashboardIcon(a,"crown",GOLD);
        LinearLayout.LayoutParams crownLp=new LinearLayout.LayoutParams(dp(100),dp(53));crownLp.gravity=Gravity.CENTER;
        p.addView(crown,crownLp);
        TextView brand=text("GOD OF TRADING 2.0",25,GOLD,true);brand.setGravity(Gravity.CENTER);brand.setMaxLines(2);
        brand.setShadowLayer(dp(3),0,dp(2),0xff9f6813);
        brand.getPaint().setShader(new LinearGradient(0,0,0,brand.getTextSize()*1.15f,new int[]{0xfffff8d6,0xfff2c35b,0xffb77714,0xffffda75},null,Shader.TileMode.CLAMP));
        p.addView(brand,new LinearLayout.LayoutParams(-1,-2));
        centered(p,"АНАЛИТИЧЕСКАЯ ПЛАТФОРМА",12,WHITE,false,6);
        space(p,5);
        DashboardIcon line=new DashboardIcon(a,"flare",GOLD);
        p.addView(line,new LinearLayout.LayoutParams(-1,dp(10)));
        centered(p,"Торгуй грамотно, управляй рынком",12,GOLD,true,5);
        space(p,9);
        addPair(p,tile("trade","Трейдинг","Торговый бот\nАнализ рынков",false,()->action.open("trade")),
                tile("sport","Спорт","Анализ матчей\nЭкспрессы",true,()->action.open("sport")));
        addPair(p,tile("crypto","Криптосканер","Сканер Bybit\nТорговый терминал",false,()->action.open("crypto")),
                tile("invest","Инвестиции","Акции, фонды\nДоходность",true,()->new AnalyticsTools(a).investments()));
        addPair(p,tile("news","Новости","Футбол · GoS\nЭкономика · Investing",false,()->new NewsCenter(a,()->action.open("sport")).show()),
                tile("wallet","Финансы","Демо-статистика\nПрибыль и убытки",true,()->action.open("finance")));
        addPair(p,tile("lab","Лаборатория","Риск и просадка\nПроверка сделок",false,()->new AlertDialog.Builder(a).setTitle("Лаборатория стратегий").setItems(new String[]{"Калькулятор риска","Анализ серии сделок","Бэктест спот-стратегии (CSV)"},(dialog,which)->{if(which==0)calculator();else if(which==1)new AnalyticsTools(a).laboratory();else new AnalyticsTools(a).replay();}).show()),
                tile("learn","Обучение","Трейдинг, инвестиции\nСпортивный анализ",true,()->new LearningCenter(a).show()));
        LinearLayout ai=row();ai.setPadding(dp(10),dp(9),dp(10),dp(9));
        clickable(ai,GOLD,0xff393020,0xff10151e,()->new AnalyticsTools(a).openAssistant());
        DashboardIcon robot=new DashboardIcon(a,"robot",GOLD);ai.addView(robot,new LinearLayout.LayoutParams(dp(80),dp(81)));
        LinearLayout aiCopy=column();aiCopy.addView(text("GoD AI",23,GOLD,true));aiCopy.addView(text("ИИ-помощник",15,GOLD,true));
        TextView ad=text("Анализ · Прогнозы · Автоматизация\nВстроенный чат",11,WHITE,false);ad.setPadding(0,dp(5),0,0);aiCopy.addView(ad);
        ai.addView(aiCopy,new LinearLayout.LayoutParams(0,-2,1));ai.addView(text("›",30,GOLD,true));
        p.addView(ai,new LinearLayout.LayoutParams(-1,-2));
        centered(p,"Версия "+BuildConfig.VERSION_NAME,9,MUTED,false,8);
        space(p,6);
        LinearLayout nav=row();nav.setBackground(gradient(0xff17202a,0xff0a0e15,0xff303b49,0));
        String[] signs={"home","stats","signals","settings"},labels={"Главная","Статистика","Сигналы","Настройки"};
        Runnable[] actions={()->scroll.smoothScrollTo(0,0),()->action.open("finance"),()->action.open("signals"),()->action.open("settings")};
        for(int i=0;i<4;i++)nav.addView(nav(signs[i],labels[i],i==0?GOLD:MUTED,actions[i]),new LinearLayout.LayoutParams(0,dp(56),1));
        shell.addView(nav);return shell;
    }
    private View tile(String glyph,String name,String desc,boolean gold,Runnable r){
        LinearLayout tile=row();tile.setGravity(Gravity.CENTER_VERTICAL);tile.setMinimumHeight(dp(a.getResources().getConfiguration().screenHeightDp>=740?88:76));tile.setPadding(dp(7),dp(8),dp(6),dp(8));
        clickable(tile,gold?0xff8b7139:0xff65738c,gold?0xff302b26:0xff202a3d,0xff0b111a,r);
        DashboardIcon icon=new DashboardIcon(a,glyph,GOLD);
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(38),dp(46));ip.rightMargin=dp(4);tile.addView(icon,ip);
        LinearLayout copy=column();TextView title=text(name,14,WHITE,true);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if(android.os.Build.VERSION.SDK_INT>=26)title.setAutoSizeTextTypeUniformWithConfiguration(10,14,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        else if(name.length()>11)title.setTextSize(11);
        copy.addView(title,new LinearLayout.LayoutParams(-1,dp(23)));
        TextView summary=text(desc,10,WHITE,false);summary.setPadding(0,dp(4),0,0);copy.addView(summary);
        tile.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        tile.setContentDescription(name+". "+desc);return tile;
    }
    private void addPair(LinearLayout p,View left,View right){
        LinearLayout r=row();LinearLayout.LayoutParams l=new LinearLayout.LayoutParams(0,-1,1);l.rightMargin=dp(5);
        LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(0,-1,1);q.leftMargin=dp(5);
        r.addView(left,l);r.addView(right,q);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(8);p.addView(r,lp);
    }
    private void planned(String title,String message){new AlertDialog.Builder(a).setTitle(title).setMessage(message).setPositiveButton("Понятно",null).show();}
    private void education(){planned("Обучение","ТРЕЙДИНГ\nТренд — направление движения цены. Проверяйте данные и сначала тестируйте стратегию на демо-счёте.\n\nИНВЕСТИЦИИ\nДиверсификация снижает концентрацию риска, но не гарантирует прибыль.\n\nСПОРТ\nВероятность исхода не равна гарантии. Для десятичного коэффициента k порог безубыточности без возвратов составляет 1/k.");}
    private void calculator(){
        LinearLayout box=column();box.setPadding(dp(20),dp(8),dp(20),dp(8));
        EditText balance=new EditText(a),risk=new EditText(a);
        balance.setHint("Виртуальный баланс, ₽");risk.setHint("Лимит потери, %");
        for(EditText e:new EditText[]{balance,risk}){e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);box.addView(e);}
        TextView result=text("Только расчёт, без открытия сделки.",14,WHITE,false);box.addView(result);
        AlertDialog d=new AlertDialog.Builder(a).setTitle("Калькулятор риска").setView(box).setPositiveButton("Рассчитать",null).setNegativeButton("Закрыть",null).create();
        d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{
            try{double b=Double.parseDouble(balance.getText().toString().replace(',','.')),p=Double.parseDouble(risk.getText().toString().replace(',','.'));
                if(!Double.isFinite(b)||!Double.isFinite(p)||b<=0||p<0||p>100)throw new IllegalArgumentException();
                result.setText(String.format(Locale.forLanguageTag("ru-RU"),"Лимит потери: %,.2f ₽. Это не рекомендация по сделке.",b*(p/100)));
            }catch(Exception ex){result.setText("Введите положительный баланс и процент от 0 до 100.");}
        }));d.show();
    }
    private static final class Crown extends View{
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Crown(Activity a){super(a);setContentDescription("Золотая корона");}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);float w=getWidth(),h=getHeight();
            p.setShader(new LinearGradient(0,0,w,h,new int[]{0xfffff2bc,0xffd59227,0xffffd86a},null,Shader.TileMode.CLAMP));
            Path path=new Path();path.moveTo(.12f*w,.36f*h);path.lineTo(.28f*w,.57f*h);path.lineTo(.50f*w,.08f*h);
            path.lineTo(.72f*w,.57f*h);path.lineTo(.88f*w,.36f*h);path.lineTo(.78f*w,.85f*h);path.lineTo(.22f*w,.85f*h);path.close();c.drawPath(path,p);
            c.drawRoundRect(.22f*w,.89f*h,.78f*w,.97f*h,3,3,p);
            c.drawCircle(.12f*w,.32f*h,.045f*w,p);c.drawCircle(.88f*w,.32f*h,.045f*w,p);p.setShader(null);
            p.setColor(0xff8b5b17);c.drawCircle(.50f*w,.63f*h,.05f*w,p);
        }
    }
}
