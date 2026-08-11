export interface PageResult {
  items: Record<string, unknown>[]
  total: number
  page: number
  pageSize: number
  totalPages: number
}

const API_BASE = (import.meta as unknown as { env?: Record<string, string> }).env?.VITE_API_BASE_URL ?? ''

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const formData = init?.body instanceof FormData
  const headers = { ...(formData ? {} : { 'Content-Type': 'application/json' }), ...(init?.headers ?? {}) }
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    credentials: 'include',
    headers
  })
  const payload = await response.json()
  if (!response.ok || !payload.success) throw new Error(payload.message || '操作失败')
  return payload.data as T
}

export function loadModule(module: string, query: URLSearchParams) {
  return request<PageResult>(`/api/workbench/${module}?${query.toString()}`)
}

export interface ProductImage {
  id: number
  productId: number
  originalFilename: string
  contentType: string
  fileSize: number
  primary: boolean
  sortOrder: number
  contentUrl: string
}

export function loadProductImages(productId: number) {
  return request<ProductImage[]>(`/api/products/${productId}/images`)
}

export function uploadProductImages(productId: number, files: File[]) {
  const body = new FormData()
  files.forEach(file => body.append('files', file))
  return request<ProductImage[]>(`/api/products/${productId}/images`, { method: 'POST', body })
}

export function setPrimaryProductImage(productId: number, imageId: number) {
  return request<ProductImage[]>(`/api/products/${productId}/images/${imageId}/primary`, { method: 'PUT' })
}

export function reorderProductImages(productId: number, imageIds: number[]) {
  return request<ProductImage[]>(`/api/products/${productId}/images/order`, {
    method: 'PUT',
    body: JSON.stringify({ imageIds })
  })
}

export function deleteProductImage(productId: number, imageId: number) {
  return request<ProductImage[]>(`/api/products/${productId}/images/${imageId}`, { method: 'DELETE' })
}

export interface InventoryMovement {
  date: string
  direction: '入库' | '出库'
  quantity: number
  sourceColumn: string
}

export function loadInventoryMovements(inventoryId: number) {
  return request<InventoryMovement[]>(`/api/workbench/inventory/${inventoryId}/movements`)
}

export interface BusinessTraceEvent {
  occurredAt: string
  title: string
  description: string
  linkType?: 'order' | 'purchase' | null
  linkId?: number | null
}
export interface BusinessTrace {
  type: 'order' | 'purchase'
  title: string
  header: Record<string, unknown>
  details: Record<string, unknown>[]
  timeline: BusinessTraceEvent[]
}
export function loadBusinessTrace(type: 'order' | 'purchase', id: number) {
  return request<BusinessTrace>(`/api/workbench/traces/${type}/${id}`)
}
export function createEntity(module: string, data: Record<string, unknown>) {
  return request<Record<string, unknown>>(`/api/workbench/${module}`, { method: 'POST', body: JSON.stringify(data) })
}
export function updateEntity(module: string, id: number, data: Record<string, unknown>) {
  return request<Record<string, unknown>>(`/api/workbench/${module}/${id}`, { method: 'PUT', body: JSON.stringify(data) })
}
export function createOrder(data: Record<string, unknown>) {
  return request<Record<string, unknown>>('/api/orders', { method: 'POST', body: JSON.stringify(data) })
}
export function getOrder(id: number) { return request<Record<string, unknown>>(`/api/orders/${id}`) }
export interface OrderSku {
  id: number
  skuCode?: string | null
  productName?: string | null
  model?: string | null
  configuration?: string | null
  productVersion?: string | null
  color?: string | null
  lockBody?: string | null
  unit?: string | null
  primaryImageId?: number | null
  actualQuantity: number
  availableQuantity: number
}
export function loadOrderSkus() { return request<OrderSku[]>('/api/orders/skus') }
export interface OrderCustomer {
  id: number
  customerCode: string
  customerName: string
  contactName?: string
  phone?: string
  address?: string
  businessContactName?: string
  businessContactPhone?: string
  orderContactName?: string
  orderContactPhone?: string
  financeContactName?: string
  financeContactPhone?: string
}
export function loadOrderCustomers() { return request<OrderCustomer[]>('/api/orders/customers') }
export async function loadContractPrice(customerId: number, skuId: number) {
  const data = await request<{ salePrice?: number | null }>(`/api/orders/contract-price?customerId=${customerId}&skuId=${skuId}`)
  return data.salePrice == null ? null : Number(data.salePrice)
}
export interface CustomerContractPrice { skuId:number; salePrice:number; skuCode?:string; productName?:string }
export interface CustomerContract { id?:number; contractNo:string; startDate:string; endDate:string; remark?:string; prices:CustomerContractPrice[] }
export interface CustomerCommand {
  customerCode?:string; customerName:string; address?:string
  businessContactName?:string; businessContactPhone?:string; orderContactName?:string; orderContactPhone?:string
  financeContactName?:string; financeContactPhone?:string; invoiceTitle?:string; taxpayerId?:string
  invoiceAddress?:string; invoicePhone?:string; bankName?:string; bankAccount?:string
  contracts:CustomerContract[]; version?:number
}
export function getCustomer(id:number) { return request<Record<string,unknown>>(`/api/customers/${id}`) }
export function createCustomer(data:CustomerCommand) { return request<Record<string,unknown>>('/api/customers',{method:'POST',body:JSON.stringify(data)}) }
export function updateCustomer(id:number,data:CustomerCommand) { return request<Record<string,unknown>>(`/api/customers/${id}`,{method:'PUT',body:JSON.stringify(data)}) }
export function updateOrder(id: number, data: Record<string, unknown>) {
  return request<Record<string, unknown>>(`/api/orders/${id}`, { method: 'PUT', body: JSON.stringify(data) })
}
export interface ShipmentQuantityItem { lineNo: number; shippedQuantity: number }
export function updateShipmentQuantities(orderId: number, deliveryAddress: string, items: ShipmentQuantityItem[], remark?: string) {
  return request<Record<string, unknown>>(`/api/orders/${orderId}/shipment-quantities`, {
    method: 'PUT', body: JSON.stringify({ deliveryAddress, items, ...(remark === undefined ? {} : { remark }) })
  })
}
export function postAction<T = Record<string, unknown>>(path: string, data: Record<string, unknown> = {}) {
  return request<T>(path, { method: 'POST', body: JSON.stringify(data) })
}

export interface ManualPurchaseData {
  supplierId: number
  skuId: number
  quantity: number
  purchasePrice: number
  expectedArrivalDate?: string
  remark?: string
}

export interface SupplierOption {
  id: number
  supplierCode?: string
  supplierName: string
  contactName?: string
  phone?: string
}

export interface SupplierProductOption {
  id: number
  skuCode?: string
  productName?: string
  model?: string
  configuration?: string
  unit?: string
  purchasePrice: number
  moq: number
  leadTimeDays: number
}

export interface SupplierProductConfig {
  skuId: number
  purchasePrice: number
  moq: number
  leadTimeDays: number
}

export interface SupplierCommand {
  supplierName: string
  manufacturerCategory?: string
  manufacturerType?: string
  supplierLocation?: string
  productAttribute?: string
  shortName?: string
  contactName?: string
  contactTitle?: string
  phone?: string
  address?: string
  currency?: string
  taxRegistrationNo?: string
  bankAddress?: string
  bankAccount?: string
  products: SupplierProductConfig[]
  version?: number
}

export interface ProductSupplierOption extends SupplierOption {
  supplierId: number
  purchasePrice: number
  moq: number
  leadTimeDays: number
}

export function loadProductSuppliers(skuId: number, keyword = '') {
  return request<ProductSupplierOption[]>(`/api/products/${skuId}/suppliers?keyword=${encodeURIComponent(keyword)}`)
}
export function loadSupplierOptions(keyword = '') {
  return request<SupplierOption[]>(`/api/suppliers/options?keyword=${encodeURIComponent(keyword)}`)
}

export function loadSupplierProducts(supplierId: number, keyword = '') {
  return request<SupplierProductOption[]>(`/api/suppliers/${supplierId}/products?keyword=${encodeURIComponent(keyword)}`)
}

export function getSupplier(id: number) {
  return request<Record<string, unknown>>(`/api/suppliers/${id}`)
}

export function createSupplier(data: SupplierCommand) {
  return request<Record<string, unknown>>('/api/suppliers', { method: 'POST', body: JSON.stringify(data) })
}

export function updateSupplier(id: number, data: SupplierCommand) {
  return request<Record<string, unknown>>(`/api/suppliers/${id}`, { method: 'PUT', body: JSON.stringify(data) })
}

export interface PurchasePaymentData {
  amount: number
  paymentMethod: string
  invoiceNo?: string
  invoiceDate?: string
  paymentRemark?: string
}

export function createManualPurchase(data: ManualPurchaseData) {
  return request<Record<string, unknown>>('/api/procurement/manual', {
    method: 'POST',
    body: JSON.stringify(data)
  })
}

export function payPurchaseByNumber(purchaseNo: string, data: PurchasePaymentData) {
  return request<Record<string, unknown>>(
    `/api/procurement/purchases/by-number/${encodeURIComponent(purchaseNo)}/payment`,
    { method: 'POST', body: JSON.stringify(data) }
  )
}

export interface PurchaseReceiptItem {
  id: number
  skuCode?: string
  productName?: string
  quantity: number
  receivedQuantity: number
  remainingQuantity: number
}

export interface PurchaseDetail {
  id: number
  purchaseNo: string
  supplierName: string
  totalAmount: number
  items: PurchaseReceiptItem[]
}

export function loadPurchase(id: number) {
  return request<PurchaseDetail>(`/api/procurement/purchases/${id}`)
}

export function receivePurchase(id: number, items: { purchaseOrderItemId: number; receivedQuantity: number }[]) {
  return request<Record<string, unknown>>(`/api/procurement/purchases/${id}/receive`, {
    method: 'POST', body: JSON.stringify({ items })
  })
}

export interface ProductCodeRule {
  id: number
  category: string
  code: string
  displayName: string
  enabled: boolean
  sortOrder: number
  version: number
  updatedAt?: string
}
export type ProductCodeRuleCommand = Omit<ProductCodeRule, 'id'>
export async function loadProductCodeRules(category?: string) {
  if (category) return request<ProductCodeRule[]>(`/api/product-code-rules?category=${encodeURIComponent(category)}&includeDisabled=true`)
  const categories = ['BRAND','SERIES','BODY_COLOR','LOCK_TYPE','CONNECTIVITY','SALES_CHANNEL','OPERATING_ENTITY','LANGUAGE','DOOR_MODEL','SECURITY_GRADE','BASE_MATERIAL','THICKNESS','FINISH_COLOR']
  const groups = await Promise.all(categories.map(item => request<ProductCodeRule[]>(`/api/product-code-rules?category=${item}&includeDisabled=true`)))
  return groups.flat()
}export function createProductCodeRule(data: ProductCodeRuleCommand) {
  return request<ProductCodeRule>('/api/product-code-rules', { method: 'POST', body: JSON.stringify(data) })
}
export function updateProductCodeRule(id: number, data: ProductCodeRuleCommand) {
  return request<ProductCodeRule>(`/api/product-code-rules/${id}`, { method: 'PUT', body: JSON.stringify(data) })
}
export function deleteProductCodeRule(id: number) {
  return request<void>(`/api/product-code-rules/${id}`, { method: 'DELETE' })
}
export interface OrderAllocationItem {
  lineNo: number; skuCode?: string; productName?: string; quantity: number; shippedQuantity: number
  lockedQuantity: number; uncoveredQuantity: number; actualQuantity: number; availableQuantity: number
}
export interface OrderAllocation { id: number; version: number; status: string; adjustable: boolean; items: OrderAllocationItem[] }
export function loadOrderAllocations(id: number) { return request<OrderAllocation>(`/api/orders/${id}/allocations`) }
export function updateOrderAllocations(id: number, version: number, items: { lineNo: number; lockedQuantity: number }[]) {
  return request<OrderAllocation>(`/api/orders/${id}/allocations`, { method: 'PUT', body: JSON.stringify({ version, items }) })
}