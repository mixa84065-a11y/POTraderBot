import {expiredRun} from "./run_duration.ts";
Deno.test("five-hour and default deadlines",()=>{
 const t=Date.parse("2026-11-26T12:00:00Z"),s=new Date(t).toISOString();
 if(expiredRun(s,300,t+17999999)||!expiredRun(s,300,t+18000000)||expiredRun(s,undefined,t+18000000)||!expiredRun(s,undefined,t+172800000)||!expiredRun(undefined,300,t)||!expiredRun(s,0,t))throw Error("duration check failed");
});
