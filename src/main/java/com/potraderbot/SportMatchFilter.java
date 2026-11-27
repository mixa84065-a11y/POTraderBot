package com.potraderbot;
final class SportMatchFilter {
    static boolean matches(int mode,String status){
        if(mode==0)return true;
        if(status==null)return false;
        switch(mode){
            case 1:return "NS".equals(status)||"TBD".equals(status);
            case 2:return "1H".equals(status)||"HT".equals(status)||"2H".equals(status)||"ET".equals(status)||"BT".equals(status)||"P".equals(status)||"LIVE".equals(status);
            case 3:return "FT".equals(status)||"AET".equals(status)||"PEN".equals(status)||"AWD".equals(status)||"WO".equals(status);
            default:return false;
        }
    }
}
