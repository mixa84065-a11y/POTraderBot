import { makeServer } from "./http.mjs";
const port=Number(process.env.PORT||8080);
if(!Number.isInteger(port)||port<1||port>65535)throw new Error("Invalid PORT");
const server=makeServer({token:process.env.GOS_AUTH_TOKEN});
server.listen(port,"127.0.0.1",()=>console.log("GoS private HTTP adapter listening on localhost"));
