package com.potraderbot;
import java.util.Locale;
/** Conservative display aliases only. Keep provider names and identifiers for queries. */
final class SportRussianNames {
    static String team(String name){
        if(name==null)return "";
        switch(name.toLowerCase(Locale.ROOT)){
            case "diriangén":case "diriangen":return "Дирианген";
            case "matagalpa":return "Матагальпа";
            case "atletico torque":case "atlético torque":return "Атлетико Торке";
            case "club nacional":return "Насьональ";
            case "cf pachuca":case "pachuca":return "Пачука";
            case "guadalajara chivas":case "guadalajara":return "Гвадалахара";
            case "real madrid":return "Реал Мадрид";
            case "barcelona":return "Барселона";
            case "manchester city":return "Манчестер Сити";
            case "manchester united":return "Манчестер Юнайтед";
            case "liverpool":return "Ливерпуль";
            case "arsenal":return "Арсенал";
            case "chelsea":return "Челси";
            case "bayern munich":case "bayern münchen":return "Бавария";
            case "borussia dortmund":return "Боруссия Дортмунд";
            case "paris saint germain":return "Пари Сен-Жермен";
            default:return name;
        }
    }
    static String league(String name){
        if(name==null)return "";
        switch(name.toLowerCase(Locale.ROOT)){
            case "primera division":case "primera división":return "Примера дивисьон";
            case "copa uruguay":return "Кубок Уругвая";
            case "liga mx":return "Лига MX (Мексика)";
            case "premier league":return "Премьер-лига";
            case "la liga":return "Ла Лига";
            case "bundesliga":return "Бундеслига";
            case "serie a":return "Серия А";
            case "ligue 1":return "Лига 1";
            case "uefa champions league":return "Лига чемпионов УЕФА";
            case "uefa europa league":return "Лига Европы УЕФА";
            default:return name;
        }
    }
}
