import type { UserRole } from './auth'
export type FundSource = 'DOMESTIC' | 'FOREX'
export const fundSourceLabel=(source?:string)=>source==='FOREX'?'外汇':source==='DOMESTIC'?'国内':'未填写'
export interface FundOverview { customerId:number; balance:number; orderOutstandingAmount:number; coverageRatio:number; pendingAmount:number; insufficient:boolean; pendingReceipt?:number; pendingRefund?:number }
export interface FundRequest { id:number; requestType:string; status:string; amount:number; fundSource?:FundSource; operationNo?:string; submittedAt?:string; paymentAt?:string; paymentDate?:string; paymentMethod?:string; referenceNo?:string; confirmedAmount?:number; submittedByName?:string; reviewedByName?:string; reviewedAt?:string; remark?:string; reviewComment?:string }
export interface FundLedger { id:number; entryType:string; direction:'IN'|'OUT'; amount:number; balanceAfter:number; sourceNo?:string; reason?:string; operatedAt?:string }
export interface FundSummary { period:string; depositAmount:number; receiptAmount:number; refundAmount:number; reversalAmount:number; netChange:number; openingBalance:number; closingBalance:number }
export interface FundOrderOption { id:number; orderNo:string; status:string; orderDate?:string }
export interface DepositCommand { fundSource?:FundSource; amount:number; paymentDate:string; paymentMethod:string; referenceNo?:string; remark?:string; orderId?:number; paymentAt?:string }
async function request<T>(path:string,init?:RequestInit):Promise<T>{const response=await fetch(path,{...init,credentials:'include',headers:{'Content-Type':'application/json',...(init?.headers??{})}});const payload=await response.json();if(!response.ok||!payload.success)throw new Error(payload.message||'操作失败');return payload.data as T}
export const loadCustomerFundOverview=(id:number)=>request<FundOverview>(`/api/customers/${id}/funds/overview`)
export const loadCustomerFundRequests=(id:number)=>request<FundRequest[]>(`/api/customers/${id}/funds/requests`)
export const loadCustomerFundLedger=(id:number)=>request<FundLedger[]>(`/api/customers/${id}/funds/ledger`)
export const loadCustomerFundSummary=(id:number,period='MONTH')=>request<FundSummary[]>(`/api/customers/${id}/funds/summary?period=${period}`)
export const loadCustomerFundOrders=(id:number)=>request<FundOrderOption[]>(`/api/customers/${id}/funds/orders`)
export const submitCustomerDeposit=(id:number,data:DepositCommand)=>request<number>(`/api/customers/${id}/funds/deposits`,{method:'POST',body:JSON.stringify(data)})
export const reviewCustomerFundRequest=(id:number,approved:boolean,comment:string,confirmedAmount?:number)=>request(`/api/customer-funds/requests/${id}/review`,{method:'POST',body:JSON.stringify({approved,comment,...(confirmedAmount === undefined ? {} : {confirmedAmount})})})
export function canReviewCustomerFunds(role:UserRole){return role==='ADMIN'||role==='FINANCE'}

export interface FundCustomer { id:number; customerCode:string; customerName:string; enabled:boolean; balance:number }
export interface FundOperation {
  id:number; customerId:number; customerCode:string; customerName:string; operationNo:string; amount:number;
  direction:'IN'|'OUT'; status:string; fundSource?:FundSource; requestType?:string; entryType?:string; confirmedAmount?:number;
  paymentAt?:string; paymentDate?:string; paymentMethod?:string; referenceNo?:string; sourceType?:string; sourceNo?:string;
  submittedAt?:string; submittedByName?:string; reviewedByName?:string; reviewedAt?:string; remark?:string; reviewComment?:string;
  ledgerId?:number; balanceBefore?:number; balanceAfter?:number;
  reversalReason?:string; reversalByName?:string; reversalAt?:string;
}
export interface FundPage { items:FundOperation[]; total:number; page:number; pageSize:number; summary?: {pendingReceipt:number;pendingRefund:number;confirmedReceipt:number;confirmedRefund:number} }
export const loadFundCustomers=()=>request<FundCustomer[]>('/api/customer-funds/customers')
export const loadFundOperations=(params:URLSearchParams,ledger=false)=>request<FundPage>(`/api/customer-funds/${ledger?'ledger':'operations'}?${params}`)
export const reverseCustomerFund=(id:number,reason:string)=>request(`/api/customer-funds/ledger/${id}/reverse`,{method:'POST',body:JSON.stringify({reason})})
