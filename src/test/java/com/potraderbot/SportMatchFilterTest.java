package com.potraderbot;
import org.junit.Test;
import static org.junit.Assert.*;
public class SportMatchFilterTest {
    @Test public void distinguishesStatuses(){
        assertTrue(SportMatchFilter.matches(0,"PST"));
        assertTrue(SportMatchFilter.matches(1,"NS"));
        assertTrue(SportMatchFilter.matches(2,"HT"));
        assertTrue(SportMatchFilter.matches(3,"PEN"));
        assertFalse(SportMatchFilter.matches(2,"FT"));
        assertFalse(SportMatchFilter.matches(3,"CANC"));
        assertFalse(SportMatchFilter.matches(2,"INT"));
    }
    @Test public void checksCrestOrigin(){
        assertTrue(TeamLogoLoader.allowed(42,"https://media.api-sports.io/football/teams/42.png"));
        assertFalse(TeamLogoLoader.allowed(42,"https://media.api-sports.io/football/teams/43.png"));
        assertFalse(TeamLogoLoader.allowed(42,"https://media.api-sports.io.example.org/football/teams/42.png"));
        assertFalse(TeamLogoLoader.allowed(42,"http://media.api-sports.io/football/teams/42.png"));
    }
}
