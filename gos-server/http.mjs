import { createServer } from "node:http";
import { createHash, timingSafeEqual } from "node:crypto";
import { analyze } from "./model.mjs";

export function makeServer({ token, maxPerMinute = 30 } = {}) {
  if (typeof token !== "string" || token.length < 32) throw new Error("Set a strong server-only GOS_AUTH_TOKEN");
  if (!Number.isInteger(maxPerMinute) || maxPerMinute < 1 || maxPerMinute > 120) throw new Error("Invalid request limit");
  const digest = (v) => createHash("sha256").update(v).digest();
  const expected = digest("Bearer " + token);
  let window = 0, used = 0;
  const server = createServer(async (req, res) => {
    const send = (status, data) => {
      res.writeHead(status, {"content-type":"application/json; charset=utf-8","cache-control":"no-store","x-content-type-options":"nosniff"});
      res.end(JSON.stringify(data));
    };
    if (req.method !== "POST" || req.url !== "/v1/analyze") { req.resume(); return send(404,{error:"not_found"}); }
    const auth = req.headers.authorization;
    if (typeof auth !== "string" || !timingSafeEqual(digest(auth), expected)) { req.resume(); return send(401,{error:"unauthorized"}); }
    const minute = Math.floor(Date.now()/60000);
    if (minute !== window) { window = minute; used = 0; }
    if (used >= maxPerMinute) { req.resume(); return send(429,{error:"rate_limit"}); }
    used++;
    if ((req.headers["content-type"] || "").split(";")[0].trim() !== "application/json") { req.resume(); return send(415,{error:"json_required"}); }
    const limit = 4096;
    if (Number(req.headers["content-length"]) > limit) { req.resume(); return send(413,{error:"too_large"}); }
    try {
      let chunks = [], size = 0;
      for await (const chunk of req) {
        size += chunk.length;
        if (size > limit) { chunks = []; continue; }
        chunks.push(chunk);
      }
      if (size > limit) return send(413,{error:"too_large"});
      const data = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      const fields = ["n","gf","ga","htN","htFor","htAgainst"];
      if (!data || typeof data !== "object" || Array.isArray(data) || Object.keys(data).length !== 2 ||
          !Object.hasOwn(data,"home") || !Object.hasOwn(data,"away")) return send(400,{error:"invalid_input"});
      for (const side of [data.home,data.away]) {
        if (!side || typeof side !== "object" || Array.isArray(side) ||
            Object.keys(side).length !== fields.length || fields.some(k=>!Object.hasOwn(side,k))) return send(400,{error:"invalid_input"});
      }
      return send(200,analyze(data));
    } catch { if(!res.headersSent) send(400,{error:"invalid_input"}); }
  });
  server.requestTimeout = 10000;
  server.headersTimeout = 5000;
  server.maxConnections = 32;
  return server;
}
