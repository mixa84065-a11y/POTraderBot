package com.potraderbot;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class SportArchiveTest{
 @Test public void filterBeforeCutoff()throws Exception{
  JSONObject r=new JSONObject("{\"fixture\":{\"timestamp\":100,\"status\":{\"short\":\"FT\"}},\"teams\":{\"home\":{\"id\":1},\"away\":{\"id\":2}}}");
  JSONArray rows=new JSONArray().put(r);assertEquals(1,SportArchive.forTeams(rows,1,3,200).length());assertEquals(0,SportArchive.forTeams(rows,1,3,100).length());assertEquals(0,SportArchive.forTeams(rows,3,4,200).length());
 }
 @Test public void noSecretInError(){assertFalse(SportArchive.errorCategory("api key secret-example").contains("secret-example"));}
}
