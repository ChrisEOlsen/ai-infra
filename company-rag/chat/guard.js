// Secure the pinned frontend's browser integration without putting credentials in it.
(() => {
 const original = window.fetch.bind(window);
 window.fetch = async (input, init = {}) => {
   const target = new URL(input instanceof Request ? input.url : input, location.href);
   if(target.origin !== location.origin) throw new Error('External browser integrations are disabled');
   const headers = new Headers(input instanceof Request ? input.headers : init.headers);
   const token=document.cookie.split('; ').find(x=>x.startsWith('XSRF-TOKEN='))?.slice(11);
   if(token) headers.set('X-XSRF-TOKEN',decodeURIComponent(token));
   const response = await original(input,{...init,headers,credentials:'same-origin'});
   if(response.status===401){location.replace('/oauth2/authorization/keycloak');throw new Error('Session expired')}
   return response;
 };
 // Recheck the session on restored tabs and while the page is open.
 const verify=()=>original('/api/me',{cache:'no-store'}).then(r=>{if(r.status===401)location.replace('/oauth2/authorization/keycloak')}).catch(()=>{});
 addEventListener('pageshow',verify);setInterval(verify,30000);
 const navigation=()=>{
   const actions=document.createElement('nav');actions.setAttribute('aria-label','Company account');
   actions.style.cssText='position:fixed;top:12px;right:16px;z-index:100;display:flex;gap:8px;align-items:center';
   const style='display:grid;place-items:center;width:40px;height:40px;border:0;border-radius:8px;background:transparent;color:inherit;cursor:pointer';
   if(window.__RAG_ADMIN__){const a=document.createElement('a');a.href='/admin/';a.setAttribute('aria-label','Document administration');a.style.cssText=style;a.innerHTML='<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M3 5h7l2 3h9v12H3V5Z"/></svg>';if(matchMedia('(hover:hover)').matches)a.title='Document administration';actions.append(a)}
   const button=document.createElement('button');button.type='button';button.setAttribute('aria-label','Sign out');button.style.cssText=style;button.innerHTML='<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M9 4H4v16h5m5-12 4 4-4 4m-6-4h10"/></svg>';if(matchMedia('(hover:hover)').matches)button.title='Sign out';
   button.onclick=()=>{const f=document.createElement('form');f.method='POST';f.action='/logout';const input=document.createElement('input');input.type='hidden';input.name='_csrf';input.value=decodeURIComponent(document.cookie.split('; ').find(x=>x.startsWith('XSRF-TOKEN='))?.slice(11)||'');f.append(input);document.body.append(f);f.submit()};actions.append(button);document.body.append(actions);
 };
 if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',navigation);else navigation();
 if('serviceWorker' in navigator)navigator.serviceWorker.getRegistrations().then(rs=>rs.forEach(r=>r.unregister()));
})();
