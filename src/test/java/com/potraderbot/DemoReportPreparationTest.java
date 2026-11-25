package com.potraderbot;
import org.junit.Test;
import static org.junit.Assert.*;
public class DemoReportPreparationTest {
    @Test public void onlyConfirmedTerminalRunCanPrepare(){
        for(String state:new String[]{"RUNNING","READY","STOPPING","PAUSED","ERROR","UNKNOWN",""})
            assertFalse(DemoReportPreparation.canPrepare(state,0));
        assertFalse(DemoReportPreparation.canPrepare("STOPPED",1));
        assertFalse(DemoReportPreparation.canPrepare("STOPPED",-1));
        assertTrue(DemoReportPreparation.canPrepare("STOPPED",0));
        assertTrue(DemoReportPreparation.canPrepare("COMPLETED",0));
    }
    @Test public void autoRefreshKeepsDraftOnlyForSameFinishedRun(){
        assertTrue(DemoReportPreparation.keepDraft("old","old",true));
        assertFalse(DemoReportPreparation.keepDraft("old","new",true));
        assertFalse(DemoReportPreparation.keepDraft("old","old",false));
        assertFalse(DemoReportPreparation.keepDraft(null,"old",true));
        assertTrue(DemoReportPreparation.keepDraft("","",true));
    }
}
