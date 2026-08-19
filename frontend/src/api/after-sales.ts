const API_BASE = (import.meta as unknown as { env?: Record<string, string> }).env?.VITE_API_BASE_URL ?? ''
async function request<T>(path:string,init?:RequestInit):Promise<T>{const response=await fetch(`${API_BASE}${path}`,{...init,credentials:'include',headers:{'Content-Type':'application/json',...(init?.headers??{})}});const payload=await response.json();if(!response.ok||!payload.success)throw new Error(payload.message||'操作失败');return payload.data as T}
export interface AfterSalesOrderOption { id:number; orderNo:string; customerName:string; orderType?:string|null }
export interface AfterSalesOrderLine { salesOrderItemId:number; skuId:number; productCode?:string; customerPartNumber?:string; productName?:string; model?:string; configuration?:string; unit?:string; shippedQuantity:number; availableReturnQuantity:number }
export interface AfterSalesReturnLine extends AfterSalesOrderLine { id?:number; requestedQuantity:number; receivedQuantity?:number; goodQuantity?:number; defectiveQuantity?:number }
export interface AfterSalesReplacementLine { id?:number; productCode?:string; returnLineId?:number; salesOrderItemId?:number; skuId:number; customerPartNumber?:string; productName?:string; model?:string; configuration?:string; unit?:string; plannedQuantity:number; shippedQuantity?:number; availableQuantity?:number }
export interface AfterSalesDetail { id:number; afterSalesNo:string; salesOrderId:number; orderNo:string; customerName:string; orderType?:string|null; afterSalesType:'RETURN'|'EXCHANGE'; status:string; issueDescription:string; applicationDate:string; contactName?:string; contactPhone?:string; deliveryAddress?:string; remark?:string; version:number; returnLines:AfterSalesReturnLine[]; replacementLines:AfterSalesReplacementLine[]; events:Record<string,unknown>[] }
export interface AfterSalesCommand { orderId:number; applicationDate:string; issueDescription:string; contactName?:string; contactPhone?:string; deliveryAddress?:string; remark?:string; version?:number; returnLines:{salesOrderItemId:number;requestedQuantity:number}[]; replacementLines:{salesOrderItemId?:number;returnLineId?:number;skuId:number;plannedQuantity:number}[] }
export const loadAfterSales=(id:number)=>request<AfterSalesDetail>(`/api/after-sales/${id}`)
export const loadAfterSalesOrderOptions=(keyword='')=>request<AfterSalesOrderOption[]>(`/api/after-sales/order-options?keyword=${encodeURIComponent(keyword)}`)
export const loadAfterSalesOrderLines=(id:number)=>request<AfterSalesOrderLine[]>(`/api/after-sales/orders/${id}/lines`)
export const createAfterSales=(data:AfterSalesCommand)=>request<Record<string,unknown>>('/api/after-sales',{method:'POST',body:JSON.stringify(data)})
export const updateAfterSales=(id:number,data:AfterSalesCommand)=>request<Record<string,unknown>>(`/api/after-sales/${id}`,{method:'PUT',body:JSON.stringify(data)})
export const receiveAfterSales=(id:number,data:Record<string,unknown>)=>request<Record<string,unknown>>(`/api/after-sales/${id}/receipts`,{method:'POST',body:JSON.stringify(data)})
export const shipAfterSales=(id:number,data:Record<string,unknown>)=>request<Record<string,unknown>>(`/api/after-sales/${id}/shipments`,{method:'POST',body:JSON.stringify(data)})
export const cancelAfterSales=(id:number,version:number)=>request<Record<string,unknown>>(`/api/after-sales/${id}/cancel?version=${version}`,{method:'POST',body:'{}'})

export interface AfterSalesRefundSuggestion { afterSalesId:number; afterSalesNo:string; customerId:number; suggestedAmount:number }
export const loadAfterSalesRefundSuggestion=(id:number)=>request<AfterSalesRefundSuggestion>(`/api/after-sales/${id}/refund-suggestion`)
export const submitAfterSalesRefund=(id:number,amount:number,suggestedAmount:number,adjustmentReason?:string)=>request<Record<string,unknown>>(`/api/after-sales/${id}/refunds`,{method:'POST',body:JSON.stringify({amount,suggestedAmount,adjustmentReason})})
