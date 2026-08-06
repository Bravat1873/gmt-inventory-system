export interface DashboardState {
  database: string
  pendingReceipt: number
  pendingStock: number
  pendingPurchasePayment: number
  pendingShipment: number
}

interface ApiResponse<T> {
  success: boolean
  data: T
  message: string
}

async function request<T>(url: string): Promise<T> {
  const response = await fetch(url)
  if (!response.ok) throw new Error('请求失败')
  const body = await response.json() as ApiResponse<T>
  if (!body.success) throw new Error(body.message || '请求失败')
  return body.data
}

export async function loadDashboard(): Promise<DashboardState> {
  const [health, summary] = await Promise.all([
    request<{ database: string }>('/api/system/health'),
    request<Omit<DashboardState, 'database'>>('/api/dashboard/summary')
  ])
  return { database: health.database, ...summary }
}
