// Cache + COOP/COEP headers (GitHub Pages cannot send them) -> SharedArrayBuffer for multithreaded ffmpeg.wasm
const CACHE='lumora-v1'
self.addEventListener('install',()=>self.skipWaiting())
self.addEventListener('activate',e=>e.waitUntil(self.clients.claim()))
function withHeaders(r){
  if(r.status===0)return r
  const h=new Headers(r.headers)
  h.set('Cross-Origin-Embedder-Policy','require-corp')
  h.set('Cross-Origin-Opener-Policy','same-origin')
  return new Response(r.body,{status:r.status,statusText:r.statusText,headers:h})
}
self.addEventListener('fetch',e=>{
  const req=e.request
  if(req.method!=='GET'||(req.cache==='only-if-cached'&&req.mode!=='same-origin'))return
  e.respondWith((async()=>{
    const c=await caches.open(CACHE)
    const hit=await c.match(req)
    const net=fetch(req).then(r=>{if(r.ok&&new URL(req.url).origin===location.origin)c.put(req,r.clone());return withHeaders(r)}).catch(()=>hit)
    return hit?withHeaders(hit):net
  })())
})
