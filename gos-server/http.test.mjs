import test from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import { makeServer } from "./http.mjs";
const token="test-only-not-a-production-token-123456";
const summary={n:10,gf:15,ga:10,htN:10,htFor:5,htAgainst:3};
test("private adapter authentication, schema and limit",async()=>{
 const server=makeServer({token,maxPerMinute:3});server.listen(0,"127.0.0.1");await once(server,"listening");
 const url="http://127.0.0.1:"+server.address().port+"/v1/analyze";
 const request=(body,auth)=>fetch(url,{method:"POST",headers:{"content-type":"application/json",...(auth?{authorization:"Bearer "+auth}:{})},body:JSON.stringify(body)});
 try {
   assert.equal((await request({home:summary,away:summary})).status,401);
   const good=await request({home:summary,away:summary},token);assert.equal(good.status,200);assert.equal((await good.json()).model,"poisson-v1");
   assert.equal((await request({home:summary,away:summary,apiKey:"not-accepted"},token)).status,400);
   assert.equal((await request({home:{...summary,n:99},away:summary},token)).status,400);
   assert.equal((await request({home:summary,away:summary},token)).status,429);
 } finally {server.closeAllConnections();await new Promise(r=>server.close(r));}
});
