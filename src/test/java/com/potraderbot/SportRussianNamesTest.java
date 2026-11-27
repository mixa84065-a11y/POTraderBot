package com.potraderbot;
import org.junit.Test;
import static org.junit.Assert.*;
public class SportRussianNamesTest {
 @Test public void localizesKnownNamesAndPreservesUnknown(){assertEquals("Пачука",SportRussianNames.team("CF Pachuca"));assertEquals("Unknown FC",SportRussianNames.team("Unknown FC"));assertEquals("Кубок Уругвая",SportRussianNames.league("Copa Uruguay"));}
}
