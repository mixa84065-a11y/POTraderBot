package com.potraderbot;
import org.junit.Test;
import static org.junit.Assert.*;
public class SportSStatsProbeTest{
 @Test public void reportsSchemaNotValues()throws Exception{
  String s=SportSStatsProbe.describe("{\"data\":[{\"id\":1,\"name\":\"secret-example\",\"home\":{\"id\":2}}]}");
  assertTrue(s.contains("Записей в ответе: 1"));assertTrue(s.contains("home:object"));assertTrue(s.contains("id:number"));assertFalse(s.contains("secret-example"));
 }
 @Test public void emptyIsNotHistory()throws Exception{assertTrue(SportSStatsProbe.describe("{\"data\":[]}").contains("Записей в ответе: 0"));}
}
