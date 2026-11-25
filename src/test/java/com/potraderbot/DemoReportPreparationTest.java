package com.potraderbot;
import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
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
    @Test public void stopAndCompletionAreDifferentStates(){
        assertTrue(DemoReportPreparation.canRequestStop("RUNNING"));
        for(String s:new String[]{"STOPPING","STOPPED","READY","PAUSED","UNKNOWN"})
            assertFalse(DemoReportPreparation.canRequestStop(s));
        assertFalse(DemoReportPreparation.canPrepare("STOPPING",0));
        assertTrue(DemoReportPreparation.canPrepare("RISK_STOP",0));
        assertFalse(DemoReportPreparation.canPrepare("RISK_STOP",1));
    }
    @Test public void closedHistoricalPositionsDoNotBlockNewTest() throws Exception {
        JSONArray rows=new JSONArray().put(new JSONObject().put("status","CLOSED"));
        assertEquals(0,DemoReportPreparation.openBybitPositions(rows));
        rows.put(new JSONObject().put("status","OPEN"));
        assertEquals(1,DemoReportPreparation.openBybitPositions(rows));
        rows.put(new JSONObject());
        assertEquals(-1,DemoReportPreparation.openBybitPositions(rows));
        assertEquals(-1,DemoReportPreparation.openBybitPositions(null));
    }
    @Test public void autoRefreshKeepsDraftOnlyForSameFinishedRun(){
        assertTrue(DemoReportPreparation.keepDraft("old","old",true));
        assertFalse(DemoReportPreparation.keepDraft("old","new",true));
        assertFalse(DemoReportPreparation.keepDraft("old","old",false));
        assertFalse(DemoReportPreparation.keepDraft(null,"old",true));
        assertTrue(DemoReportPreparation.keepDraft("","",true));
    }
}
