package com.potraderbot;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Resolution-independent artwork for the approved black/gold dashboard. */
final class DashboardIcon extends View {
    private final String kind;
    private final int tint;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    DashboardIcon(Context context,String kind,int tint){super(context);this.kind=kind;this.tint=tint;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    private static final int GOLD=0xffedb83f, LIGHT=0xffffe9a0, DARK=0xff86500e;
    private void fill(int color){p.setShader(null);p.setStyle(Paint.Style.FILL);p.setColor(color);}
    private void stroke(int color,float width){fill(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(width);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);}
    private void metal(float y1,float y2,int a,int b,int c){fill(a);p.setShader(new LinearGradient(0,y1,0,y2,new int[]{a,b,c},null,Shader.TileMode.CLAMP));}
    private Path shape(float... pts){Path x=new Path();x.moveTo(pts[0],pts[1]);for(int i=2;i<pts.length;i+=2)x.lineTo(pts[i],pts[i+1]);x.close();return x;}
    private void rect(Canvas c,float l,float t,float r,float b,float rad){c.drawRoundRect(l,t,r,b,rad,rad,p);}
    private void line(Canvas c,float x,float y,float a,float b){c.drawLine(x,y,a,b,p);}
    private void circle(Canvas c,float x,float y,float r){c.drawCircle(x,y,r,p);}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        if("flare".equals(kind)){float w=getWidth(),h=getHeight();fill(GOLD);p.setShader(new LinearGradient(0,0,w,0,new int[]{0x00efbd52,0xffe8af39,0xfffff2aa,0xffe8af39,0x00efbd52},null,Shader.TileMode.CLAMP));c.drawRect(0,h*.48f,w,h*.58f,p);p.setShader(new RadialGradient(w/2,h/2,h*.8f,new int[]{0xfffff8d4,0xffefb644,0x00efb644},null,Shader.TileMode.CLAMP));c.drawCircle(w/2,h/2,h*.8f,p);p.setShader(null);return;}
        float size=Math.min(getWidth(),getHeight());c.save();c.translate((getWidth()-size)/2,(getHeight()-size)/2);c.scale(size/64f,size/64f);
        switch(kind){
            case "trade":
                for(int i=0;i<3;i++){float x=12+i*19,y=31-i*9;int color=i==0?0xffff4740:i==1?0xfff7ac25:0xff32e571;
                    stroke(color,1.8f);line(c,x,y-9,x,y+26);metal(y,y+24,0xfffaffef,color,color==0xffff4740?0xffa90c12:color==0xfff7ac25?0xffa95607:0xff087d31);rect(c,x-5,y,x+5,y+23,1);stroke(0x80ffffff,1);rect(c,x-5,y,x+5,y+23,1);}
                break;
            case "sport":
                metal(5,58,LIGHT,GOLD,DARK);circle(c,32,32,28);stroke(0xffffdb6c,1.5f);circle(c,32,32,28);
                fill(0xff30200a);c.drawPath(shape(32,20,44,28,40,42,24,42,20,28),p);c.drawPath(shape(11,12,24,6,30,13,20,25,8,25),p);c.drawPath(shape(46,7,56,17,58,29,47,29,39,16),p);c.drawPath(shape(9,39,20,44,25,57,14,54,6,46),p);c.drawPath(shape(48,40,59,39,55,51,43,58,39,48),p);
                stroke(0xff56350c,1.1f);line(c,20,28,9,25);line(c,44,28,47,29);line(c,24,42,20,44);line(c,40,42,48,40);break;
            case "crypto":
                metal(4,60,LIGHT,GOLD,DARK);circle(c,32,32,29);stroke(0xffffe1a0,2);circle(c,32,32,26);stroke(0xff80510c,2);circle(c,32,32,22);
                fill(0xff75460a);p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));p.setTextAlign(Paint.Align.CENTER);p.setTextSize(39);c.drawText("₿",32,46,p);fill(0xffffe6a1);c.drawText("₿",31,44,p);break;
            case "invest":
                for(int i=0;i<4;i++){float x=6+i*14,y=45-i*9;metal(y,58,0xffb0ff9b,0xff34e150,0xff086521);rect(c,x,y,x+9,58,1);}
                stroke(0xff69f872,3);line(c,6,36,23,19);line(c,23,19,32,28);line(c,32,28,55,7);
                fill(0xff8bff81);c.drawPath(shape(43,7,58,4,56,18),p);break;
            case "news":
                metal(10,57,0xffedf7ff,0xff94b6e4,0xff4c659b);rect(c,13,8,57,56,4);fill(0xffd9e4f4);rect(c,6,14,50,59,4);
                fill(0xff164b99);rect(c,12,20,44,27,2);rect(c,12,32,25,47,1);stroke(0xff3e5481,2);for(int i=0;i<4;i++)line(c,29,33+i*5,44,33+i*5);line(c,12,52,44,52);break;
            case "wallet":
                metal(7,39,0xffafff81,0xff3be34b,0xff097321);c.save();c.rotate(-18,32,24);rect(c,12,8,53,34,4);c.restore();
                metal(19,55,0xffa0fc7b,0xff2cd441,0xff086621);rect(c,6,20,57,56,6);stroke(0xffb8ff9a,1.4f);rect(c,6,20,57,56,6);
                fill(0xff075a25);rect(c,37,31,61,46,5);fill(0xff7cff62);circle(c,46,38,3);break;
            case "lab":
                fill(0x664f8bcf);Path flask=shape(25,5,39,5,39,12,35,12,35,25,55,53,53,59,11,59,9,53,29,25,29,12,25,12);c.drawPath(flask,p);
                metal(34,59,0xffb2a0ff,0xff8b56ff,0xff402196);c.drawPath(shape(23,34,41,34,53,53,51,57,13,57,11,53),p);stroke(0xffd6e4ff,2.3f);c.drawPath(flask,p);fill(0xffede8ff);circle(c,26,46,2);circle(c,39,51,1.5f);circle(c,33,40,1.4f);break;
            case "learn":
                metal(27,54,0xff6cddff,0xff1098e9,0xff064876);c.drawPath(shape(14,29,14,46,32,55,50,46,50,29,32,38),p);
                metal(8,39,0xffc0f2ff,0xff21b7ff,0xff075a9a);c.drawPath(shape(2,23,32,7,62,23,32,39),p);
                stroke(0xffffca59,2.2f);line(c,51,30,55,49);fill(GOLD);rect(c,53,48,57,56,2);break;
            case "crown":
                metal(6,54,LIGHT,GOLD,DARK);Path crown=shape(4,18,18,30,22,14,27,24,32,5,37,24,42,14,46,30,60,18,52,49,12,49);c.drawPath(crown,p);
                stroke(0xffffe6a2,1.1f);c.drawPath(crown,p);metal(49,57,LIGHT,GOLD,DARK);rect(c,12,50,52,57,2);
                fill(0xff513008);c.drawPath(shape(32,34,36,39,32,44,28,39),p);circle(c,18,42,2);circle(c,46,42,2);break;
            case "robot":
                metal(4,60,0xfffff4c3,0xffdba343,0xff67400a);c.drawPath(shape(16,12,30,4,44,8,53,19,55,33,49,44,44,53,36,55,36,60,10,60,16,49,13,36,10,24),p);
                fill(0xff191b21);c.drawPath(shape(28,13,43,12,50,21,42,24,34,23,29,31,35,35,33,45,42,51,36,54,23,45,20,34),p);
                metal(24,47,LIGHT,GOLD,DARK);c.drawPath(shape(39,26,52,24,57,34,49,38,49,43,41,49,35,43,37,35),p);
                fill(0xff65ff46);c.drawPath(shape(39,25,50,24,47,29,39,29),p);stroke(0xfff6d97c,2);circle(c,20,28,9);stroke(0xff533409,2);circle(c,20,28,5);line(c,21,39,29,48);break;
            case "profile":
                stroke(tint,3);circle(c,32,18,10);c.drawArc(11,31,53,59,180,180,false,p);line(c,11,52,53,52);break;
            case "bell":
                stroke(tint,3);c.drawPath(shape(12,44,19,36,19,23,24,14,40,14,45,23,45,36,52,44),p);circle(c,32,51,3);break;
            case "home":
                fill(tint);c.drawPath(shape(5,29,32,6,59,29,52,29,52,57,38,57,38,39,26,39,26,57,12,57,12,29),p);break;
            case "stats":
                stroke(tint,3);for(int i=0;i<3;i++)rect(c,9+17*i,39-13*i,20+17*i,56,1);break;
            case "signals":
                fill(tint);circle(c,32,32,5);stroke(tint,3);c.drawArc(12,12,52,52,-48,96,false,p);c.drawArc(12,12,52,52,132,96,false,p);c.drawArc(4,4,60,60,-48,96,false,p);c.drawArc(4,4,60,60,132,96,false,p);break;
            case "settings":
                stroke(tint,3.5f);circle(c,32,32,16);circle(c,32,32,7);for(int i=0;i<8;i++){c.save();c.rotate(45*i,32,32);rect(c,28,7,36,16,1);c.restore();}break;
            default:fill(tint);circle(c,32,32,20);
        }
        p.setShader(null);c.restore();
    }
}
