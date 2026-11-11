import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
const h={"Access-Control-Allow-Origin":"*","Access-Control-Allow-Headers":"authorization, apikey, content-type","Access-Control-Allow-Methods":"GET, POST, DELETE, OPTIONS","Content-Type":"application/json"};
Deno.serve(async(req:Request)=>{
 if(req.method==="OPTIONS") return new Response("ok",{headers:h});
 if(!["GET","POST","DELETE"].includes(req.method)) return new Response(JSON.stringify({error:"Method not allowed"}),{status:405,headers:h});
 const url=Deno.env.get("SUPABASE_URL"), anon=Deno.env.get("SUPABASE_ANON_KEY"), service=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
 if(!url||!anon||!service) return new Response(JSON.stringify({error:"Server configuration incomplete"}),{status:503,headers:h});
 const auth=req.headers.get("Authorization")??"", token=auth.replace(/^Bearer\s+/i,"");
 if(!token||token===auth) return new Response(JSON.stringify({error:"Authentication required"}),{status:401,headers:h});
 const userClient=createClient(url,anon,{auth:{persistSession:false,autoRefreshToken:false}});
 const {data:u,error:ue}=await userClient.auth.getUser(token);
 if(ue||!u.user) return new Response(JSON.stringify({error:"Invalid session"}),{status:401,headers:h});
 const admin=createClient(url,service,{auth:{persistSession:false,autoRefreshToken:false}}), uid=u.user.id;
 if(req.method==="GET"){
  const {data,error}=await admin.schema("private").rpc("has_twelvedata_key",{p_user_id:uid});
  if(error) return new Response(JSON.stringify({error:"Unable to read key status"}),{status:500,headers:h});
  return new Response(JSON.stringify({configured:data===true}),{headers:h});
 }
 if(req.method==="DELETE"){
  const {error}=await admin.schema("private").rpc("delete_twelvedata_key",{p_user_id:uid});
  if(error) return new Response(JSON.stringify({error:"Unable to remove key"}),{status:500,headers:h});
  return new Response(JSON.stringify({configured:false}),{headers:h});
 }
 let body:{api_key?:unknown}; try{body=await req.json();}catch{return new Response(JSON.stringify({error:"Invalid JSON"}),{status:400,headers:h});}
 const key=typeof body.api_key==="string"?body.api_key.trim():"";
 if(key.length<8||key.length>256) return new Response(JSON.stringify({error:"Enter a valid Twelve Data API key"}),{status:422,headers:h});
 const check=new URL("https://api.twelvedata.com/time_series"); check.searchParams.set("symbol","EUR/USD"); check.searchParams.set("interval","1min"); check.searchParams.set("outputsize","1"); check.searchParams.set("apikey",key);
 try{
  const res=await fetch(check,{signal:AbortSignal.timeout(12000)}), data=await res.json();
  if(!res.ok||data.status==="error"||!Array.isArray(data.values)) return new Response(JSON.stringify({error:typeof data.message==="string"?data.message:"Twelve Data rejected this key"}),{status:422,headers:h});
 }catch{return new Response(JSON.stringify({error:"Could not verify key with Twelve Data"}),{status:502,headers:h});}
 const {error}=await admin.schema("private").rpc("save_twelvedata_key",{p_user_id:uid,p_api_key:key});
 if(error) return new Response(JSON.stringify({error:"Validation passed, secure storage failed"}),{status:500,headers:h});
 return new Response(JSON.stringify({configured:true}),{headers:h});
});