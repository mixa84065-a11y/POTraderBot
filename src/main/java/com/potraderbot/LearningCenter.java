package com.potraderbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.RadioButton;
import android.content.res.ColorStateList;

final class LearningCenter {
    private static final int BG=0xff090e15,PANEL=0xff17212e,GOLD=0xfff5c65a,WHITE=0xfff2f4f7,MUTED=0xffb6c0cf;
    private final Activity a;
    LearningCenter(Activity a){this.a=a;}
    private static final String[][] LESSONS={
        {"01 · Графики и свечи","Свеча показывает цены открытия, закрытия, максимума и минимума за период. Тело показывает разницу между открытием и закрытием, тени — экстремумы. Линейный график обычно показывает только цены закрытия.\n\nТаймфрейм M1 — одна минута, M5 — пять минут. Незакрытая свеча может измениться. Для воспроизводимого анализа используйте завершённые свечи.","Что показывает верхняя тень свечи?","Максимум за период","Объём торгов","Следующую цену","0","Верхняя тень доходит до максимума цены за период."},
        {"02 · Тренд и индикаторы","EMA — экспоненциальная скользящая средняя, которая сильнее учитывает свежие наблюдения. RSI оценивает относительную силу недавних движений. Значения индикаторов не гарантируют разворот.\n\nВ боковом рынке пересечения средних могут давать ложные сигналы. Сравнивайте стратегию на разных участках истории.","Гарантирует ли высокий RSI падение цены?","Да","Нет","Только на M1","1","Высокий RSI может сохраняться в сильном тренде."},
        {"03 · Риск и торговые расходы","Спред — разница между ценой покупки и продажи. Комиссия и проскальзывание уменьшают результат. При использовании плеча увеличивается риск ликвидации.\n\nПросадка измеряет снижение капитала от предыдущего максимума. При расчёте результата учитывайте все расходы. Проверяйте стратегии на демо.","Какие расходы нужно учитывать в тесте?","Только комиссию","Никакие","Комиссию, спред и проскальзывание","2","Все перечисленные расходы влияют на итог."},
        {"04 · Историческое тестирование","Бэктест проверяет правила на исторических данных, доступных на момент сигнала. Использование будущих свечей создаёт искажение.\n\nРазделяйте период настройки и независимой проверки. Сравнивайте результаты с базовым вариантом. Высокая доходность на истории не гарантирует будущую.","Когда должна быть доступна информация для сигнала?","До принятия решения","После закрытия сделки","Неважно","0","Будущие данные нельзя использовать для исторического сигнала."},
        {"05 · Инвестиции","Акция представляет долю в компании, облигация — долговое обязательство, ETF — фонд, торгуемый на бирже. Их риски различаются.\n\nСуммарная доходность учитывает изменение цены, выплаты и расходы. Диверсификация уменьшает концентрацию риска, но не исключает убытки.","Гарантирует ли диверсификация прибыль?","Да","Нет","При покупке ETF всегда","1","Даже диверсифицированный портфель может терять стоимость."},
        {"06 · Спортивная статистика","Оценивайте выборку матчей, домашнюю и выездную форму, составы, травмы и качество источников. Прогнозы вероятностны. Небольшая выборка может быть нерепрезентативной.\n\nДесятичный коэффициент 2,00 соответствует порогу безубыточности 50% без возвратов. Это не подтверждённая вероятность события. Экспрессы увеличивают риск потери всей суммы.","Что означает обратная величина коэффициента?","Гарантию выигрыша","Официальный прогноз счёта","Порог безубыточности без возвратов","2","Порог не равен оцененной реальной вероятности."},
        {"07 · Экономические новости","Экономический календарь показывает запланированные публикации, например инфляцию, занятость и решения центральных банков. Рынок может реагировать на отличие факта от ожиданий.\n\nПроверяйте дату, часовой пояс, первичный источник и возможные пересмотры. Влияние новости зависит от контекста, а реакция может отличаться от предполагаемой.","Что нужно проверить перед интерпретацией новости?","Время, источник, факт и ожидания","Только заголовок","Только направление прошлой свечи","0","Контекст и качество данных необходимы для интерпретации."}
    };
    private int dp(int v){return Math.round(v*a.getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout p=new LinearLayout(a);p.setOrientation(LinearLayout.VERTICAL);p.setBackgroundColor(BG);p.setPadding(dp(14),dp(12),dp(14),dp(12));return p;}
    private TextView text(LinearLayout p,String value,int size,int color,boolean bold){
        TextView t=new TextView(a);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(dp(5),dp(8),dp(5),dp(8));
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);p.addView(t);return t;
    }
    private void button(LinearLayout p,String title,Runnable action){
        Button b=new Button(a);b.setText(title);b.setAllCaps(false);b.setTextColor(WHITE);
        GradientDrawable bg=new GradientDrawable();bg.setColor(PANEL);bg.setCornerRadius(dp(12));bg.setStroke(dp(1),GOLD);
        b.setBackground(bg);b.setPadding(dp(12),dp(12),dp(12),dp(12));b.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(8);p.addView(b,lp);
    }
    private AlertDialog dialog(String title,LinearLayout p){
        ScrollView s=new ScrollView(a);s.addView(p);return new AlertDialog.Builder(a).setTitle(title).setView(s).setPositiveButton("Закрыть",null).create();
    }
    void show(){
        LinearLayout p=column();text(p,"♛ АКАДЕМИЯ",23,GOLD,true);
        text(p,"7 уроков · от основ до анализа данных. Каждый урок содержит проверочный вопрос. Учебные материалы не являются торговыми рекомендациями.",13,MUTED,false);
        for(String[] lesson:LESSONS)button(p,lesson[0],()->open(lesson));
        dialog("Обучение",p).show();
    }
    private void open(String[] lesson){
        LinearLayout p=column();text(p,lesson[0],20,GOLD,true);text(p,lesson[1],15,WHITE,false);
        text(p,"Проверьте знания",17,GOLD,true);text(p,lesson[2],15,WHITE,true);
        RadioGroup group=new RadioGroup(a);group.setOrientation(RadioGroup.VERTICAL);
        for(int i=0;i<3;i++){
            RadioButton b=new RadioButton(a);b.setId(100+i);b.setText(lesson[3+i]);b.setTextColor(WHITE);
            b.setButtonTintList(ColorStateList.valueOf(GOLD));group.addView(b);
        }
        p.addView(group);TextView result=text(p,"Выберите ответ.",14,MUTED,false);
        button(p,"Проверить ответ",()->{
            int chosen=group.getCheckedRadioButtonId();
            if(chosen==-1){result.setText("Сначала выберите ответ.");return;}
            boolean correct=chosen==100+Integer.parseInt(lesson[6]);
            result.setTextColor(correct?0xff7de4a3:GOLD);
            result.setText((correct?"Верно. ":"Не совсем. ")+lesson[7]);
        });dialog("Академия",p).show();
    }
}
