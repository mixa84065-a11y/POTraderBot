import { analyze } from "./model.mjs";
const headers={"Content-Type":"application/json; charset=utf-8","Cache-Control":"no-store","X-Content-Type-Options":"nosniff"};
const send=(status:number,data:unknown)=>new Response(JSON.stringify(data),{status,headers});
let minute=0,used=0;
export async function handler(req:Request):Promise<Response>{
 if(req.method!=="POST"&&req.method!=="GET")return send(405,{error:"method"});
 const auth=req.headers.get("Authorization")||"";
 if(!/^Bearer [A-Za-z0-9_.-]{20,4096}$/.test(auth))return send(401,{error:"unauthorized"});
 const now=Math.floor(Date.now()/60000);if(now!==minute){minute=now;used=0;}if(used>=30)return send(429,{error:"rate_limit"});used++;
 // Verify a real signed-in user, not an anon/publishable or service key.
 const url=Deno.env.get("SUPABASE_URL");
 const key=Deno.env.get("SUPABASE_ANON_KEY")||JSON.parse(Deno.env.get("SUPABASE_PUBLISHABLE_KEYS")||"{}").default;
 if(!url||!key)return send(503,{error:"auth_unavailable"});
 try{
  const check=await fetch(url+"/auth/v1/user",{headers:{Authorization:auth,apikey:key},signal:AbortSignal.timeout(5000)});
  if(!check.ok)return send(check.status>=500?503:401,{error:"unauthorized"});
  const user=await check.json();
  if(typeof user.id!=="string"||user.role!=="authenticated"||user.is_anonymous===true)return send(401,{error:"unauthorized"});
 }catch{return send(503,{error:"auth_unavailable"});}
 if(req.method==="GET")return send(200,{service:"gos",version:"1",model:"poisson-v1",archive:"not_connected",validated:false});
 if((req.headers.get("content-type")||"").split(";")[0].trim()!=="application/json")return send(415,{error:"json_required"});
 if(Number(req.headers.get("content-length"))>4096)return send(413,{error:"too_large"});
 try{
  const reader=req.body?.getReader();if(!reader)return send(400,{error:"invalid_input"});
  let size=0;const chunks:Uint8Array[]=[];
  while(true){const {done,value}=await reader.read();if(done)break;size+=value.length;if(size>4096){await reader.cancel();return send(413,{error:"too_large"});}chunks.push(value);}
  const bytes=new Uint8Array(size);let offset=0;for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.length;}
  const data=JSON.parse(new TextDecoder().decode(bytes));
  const keys=["n","gf","ga","htN","htFor","htAgainst"];
  if(!data||typeof data!=="object"||Array.isArray(data)||Object.keys(data).length!==2||!Object.hasOwn(data,"home")||!Object.hasOwn(data,"away"))return send(400,{error:"invalid_input"});
  for(const side of [data.home,data.away])if(!side||typeof side!=="object"||Array.isArray(side)||Object.keys(side).length!==keys.length||keys.some(k=>!Object.hasOwn(side,k)))return send(400,{error:"invalid_input"});
  return send(200,analyze(data));
 }catch{return send(400,{error:"invalid_input"});}
}
Deno.serve(handler);
