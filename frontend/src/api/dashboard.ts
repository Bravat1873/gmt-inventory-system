export interface TrendPoint { date: string; orderCount: number; salesAmount: number; purchaseQuantity: number; shipmentQuantity: number; afterSalesCount: number }
export interface ProductException {
  productId: number; category: 'DEMAND' | 'INVENTORY' | 'SUPPLY'; customerPartNumber: string; model: string; productCode: string
  actualQuantity: number; inTransitQuantity: number; pendingDeliveryQuantity: number; supplyDemandSurplus: number; shortageQuantity: number; relatedBusinessCount: number
}
export interface DashboardSnapshot {
  todayOrders: number; todaySalesAmount: number; pendingReceipt: number; pendingStock: number; pendingShipment: number
  shortageProducts: number; shortageQuantity: number; pendingPurchasePayment: number; activeAfterSales: number; days: number
  actualInventory: number; lockedInventory: number; inTransitInventory: number; pendingDeliveryQuantity: number; supplyDemandSurplus: number
  trend: TrendPoint[]; exceptions: ProductException[]; generatedAt: string
}
interface ApiResponse<T> { success: boolean; data: T; message: string }
export async function loadDashboard(days = 30): Promise<DashboardSnapshot> {
  const response = await fetch(`/api/dashboard/snapshot?days=${days}`)
  if (!response.ok) throw new Error('看板数据加载失败')
  const body = await response.json() as ApiResponse<DashboardSnapshot>
  if (!body.success) throw new Error(body.message || '看板数据加载失败')
  return body.data
}
export function subscribeDashboard(days: number, onSnapshot: (value: DashboardSnapshot) => void, onError: () => void) {
  const source = new EventSource(`/api/dashboard/events?days=${days}`)
  source.addEventListener('snapshot', event => onSnapshot(JSON.parse((event as MessageEvent).data)))
  source.onerror = onError
  return () => source.close()
}


