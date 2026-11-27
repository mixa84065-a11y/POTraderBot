package com.potraderbot;
import org.junit.Test;
import static org.junit.Assert.*;
public class RecoveryProofTest{
 private final String origin="https://project.supabase.co",email="test@example.com",hash="0123456789abcdef0123456789abcdef0123456789abcdef01234567";
 @Test public void acceptsOtp()throws Exception{assertEquals("012345",RecoveryProof.body(origin,email,"012345").getString("token"));assertEquals("recovery",RecoveryProof.body(origin,email,"012345").getString("type"));}
 @Test public void extractsOnlyRecoveryHash()throws Exception{assertEquals(hash,RecoveryProof.body(origin,email,origin+"/auth/v1/verify?token="+hash+"&type=recovery&redirect_to=https%3A%2F%2Fexample.org").getString("token_hash"));}
 @Test(expected=IllegalArgumentException.class) public void rejectsForeignHost()throws Exception{RecoveryProof.body(origin,email,"https://evil.example/auth/v1/verify?token="+hash+"&type=recovery");}
 @Test(expected=IllegalArgumentException.class) public void rejectsSignup()throws Exception{RecoveryProof.body(origin,email,origin+"/auth/v1/verify?token="+hash+"&type=signup");}
 @Test(expected=IllegalArgumentException.class) public void rejectsDuplicate()throws Exception{RecoveryProof.body(origin,email,origin+"/auth/v1/verify?token="+hash+"&type=recovery&type=signup");}
}
