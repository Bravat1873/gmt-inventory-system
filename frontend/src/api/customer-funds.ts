import type { UserRole } from './auth'
export interface FundOverview { customerId:number; balance:number; orderOutstandingAmount:number; coverageRatio:number; pendingAmount:number; insufficient:boolean }
export interface FundRequest { id:number; requestType:string; status:string; amount:number; submittedAt?:string }
export interface FundLedger { id:number; entryType:string; direction:'IN'|'OUT'; amount:number; balanceAfter:number; sourceNo?:string; operatedAt?:string }
export interface FundSummary { period:string; depositAmount:number; receiptAmount:number; refundAmount:number; reversalAmount:number; netChange:number; openingBalance:number; closingBalance:number }
export interface DepositCommand { amount:number; paymentDate:string; paymentMethod:string; referenceNo?:string; remark?:string }
async function request<T>(path:string,init?:RequestInit):Promise<T>{const response=await fetch(path,{...init,credentials:'include',headers:{'Content-Type':'application/json',...(init?.headers??{})}});const payload=await response.json();if(!response.ok||!payload.success)throw new Error(payload.message||'操作失败');return payload.data as T}
export const loadCustomerFundOverview=(id:number)=>request<FundOverview>(`/api/customers/${id}/funds/overview`)
export const loadCustomerFundRequests=(id:number)=>request<FundRequest[]>(`/api/customers/${id}/funds/requests`)
export const loadCustomerFundLedger=(id:number)=>request<FundLedger[]>(`/api/customers/${id}/funds/ledger`)
export const loadCustomerFundSummary=(id:number,period='MONTH')=>request<FundSummary[]>(`/api/customers/${id}/funds/summary?period=${period}`)
export const submitCustomerDeposit=(id:number,data:DepositCommand)=>request<number>(`/api/customers/${id}/funds/deposits`,{method:'POST',body:JSON.stringify(data)})
export const reviewCustomerFundRequest=(id:number,approved:boolean,comment:string)=>request(`/api/customer-funds/requests/${id}/review`,{method:'POST',body:JSON.stringify({approved,comment})})
export function canReviewCustomerFunds(role:UserRole){return role==='ADMIN'||role==='FINANCE'}