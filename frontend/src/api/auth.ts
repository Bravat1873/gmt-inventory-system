export interface CurrentUser { id:number; username:string; displayName:string }
async function request<T>(url:string, options:RequestInit={}) { const response=await fetch(url,{...options,credentials:'include',headers:{'Content-Type':'application/json',...(options.headers||{})}}); const body=await response.json().catch(()=>null); if(!response.ok||!body?.success) throw new Error(body?.message||'请求失败'); return body.data as T }
export const login=(username:string,password:string)=>request<CurrentUser>('/api/auth/login',{method:'POST',body:JSON.stringify({username,password})})
export const logout=()=>request<void>('/api/auth/logout',{method:'POST'})
export const currentUser=()=>request<CurrentUser>('/api/auth/me')
