import type { OperationRecord } from '../api/operation-records'
import { operationFieldLabel, operationTableLabel, operationValue } from './operation-labels'

const paymentFromOrder = (row: OperationRecord) => /^\/api\/finance\/orders\/\d+\/pay$/.test(row.requestPath)
export const businessModule = (row: OperationRecord) => paymentFromOrder(row) ? '订单管理' : row.moduleName
const mainDocuments: Record<string, string[]> = {
  订单管理: ['订单号'], 采购管理: ['采购单号', '采购建议单号'], 售后管理: ['售后单号'],
  财务管理: ['订单号','采购单号','发票号'], 资金管理: ['资金单号','申请单号','订单号'],
  客户管理: ['客户'], 产品管理: ['产品编号'], 供应商管理: ['供应商'], 用户管理: ['账号']
}
export function primaryDocument(row: OperationRecord) {
  const refs = (row.relatedRecords || '').split(/[；;\n]/).map(s => s.trim()).filter(Boolean)
  const keys = [...(mainDocuments[businessModule(row)] || []), '订单号','采购单号','售后单号','来源单号','发货单号','采购建议单号','文件','产品编号','客户','供应商']
  for (const key of keys) {
    const values = [...new Set(refs.filter(r => r.startsWith(key+'：') || r.startsWith(key+':')).map(r => r.slice(key.length+1).trim()).filter(Boolean))]
    if (values.length) return values.length === 1 ? values[0] : values[0]+' 等 '+values.length+' 项'
  }
  return refs[0] || '—'
}

const statusNames: Record<string,string> = {
  DRAFT:'草稿', PENDING_CUSTOMER_PAYMENT:'正式订单', WAITING_STOCK:'等待齐货', READY_TO_SHIP:'待发货',
  SHIPPED:'已发完货', PENDING:'待复核', APPROVED:'已通过', REJECTED:'已驳回', CANCELLED:'已取消',
  EXECUTING:'执行中', RECEIVED:'已收货', COMPLETED:'已完成', PENDING_SUPPLIER_PAYMENT:'待付款'
}
const verbs = { INSERT:'新增', UPDATE:'修改', DELETE:'删除' }
const money = (value: unknown) => '¥'+String(value).replace(/(\.\d*?[1-9])0+$|\.0+$/, '$1')
const valueText = (key: string, value: unknown) => {
  if (value == null || value === '') return '空'
  if (key === 'status' || key === 'review_status') return statusNames[String(value)] || String(value)
  if (/(price|amount|balance)$/.test(key)) return money(value)
  return operationValue(value)
}
const mainTables: Record<string,string[]> = {
  订单管理:['sales_order','sales_order_item','sales_shipment'], 采购管理:['purchase_order','purchase_order_item'],
  库存管理:['inventory_balance'], 资金管理:['customer_fund_request','customer_fund_account'],
  财务管理:['customer_receipt','supplier_payment','sales_invoice','purchase_invoice'],
  客户管理:['customer'], 产品管理:['sku'], 供应商管理:['supplier'], 售后管理:['after_sales_order','after_sales_refund']
}
export function changeText(row: OperationRecord): string {
  if (row.reversesId) return '撤回操作 #'+row.reversesId+(row.reversalReason ? '（'+row.reversalReason+'）' : '')
  const examples = row.businessExamples || []
  const descriptions: string[] = []
  const main = mainTables[businessModule(row)] || []
  for (const e of examples) {
    const field = (name: string) => e.fields.find(f => f.field === name)
    const amount = field('confirmed_amount')?.after ?? field('amount')?.after
    const payment = e.table === 'customer_receipt' || e.table === 'supplier_payment'
    if (payment && e.type === 'INSERT' && amount != null) {
      const verb = Number(amount)<0 ? '登记退款' : paymentFromOrder(row) ? '订单支付' : e.table === 'supplier_payment' ? '登记付款' : '登记收款'
      descriptions.push(verb+' '+money(String(amount).replace(/^-/,''))+(e.reviewStatus==='PENDING' ? '（待复核）' : ''))
    }
    const shipped = e.table === 'sales_order_item' && e.type === 'UPDATE' && field('shipped_quantity')
    if (shipped) descriptions.push((e.line ? '明细 '+e.line+'：' : '')+'发货数量 '+shipped.before+' → '+shipped.after)
  }
  if (!descriptions.length) {
    const primary = examples.filter(e=>main.includes(e.table))
    for (const e of primary.length ? primary : examples.slice(0,1)) {
      const name = operationTableLabel(e.table)
      const prefix = e.line ? '明细 '+e.line+'：' : ''
      if (e.type==='UPDATE') {
        for (const f of e.fields) descriptions.push(prefix+operationFieldLabel(f.field)+' '+valueText(f.field,f.before)+' → '+valueText(f.field,f.after))
      } else {
        const f=e.fields.find(f=>['total_amount','quantity','amount','refund_amount'].includes(f.field))
        descriptions.push(verbs[e.type]+name+(f ? '，'+operationFieldLabel(f.field)+' '+valueText(f.field,e.type==='DELETE'?f.before:f.after) : ''))
      }
    }
  }
  if (descriptions.length) {
    const entries = [...new Set(descriptions)]
    return entries.slice(0,2).join('；')+(entries.length>2 ? '…' : '')
  }
  const verb = row.action.split(' · ').pop() || row.action
  const groups = (row.changeSummary || []).filter(g=>g.table!=='document_number_sequence')
  const g = groups.find(g=>main.includes(g.table)) || groups[0]
  if (!g) return verb
  const fields=g.fields.map(operationFieldLabel).filter((s,i)=>s!==g.fields[i]).slice(0,2)
  const summary = verbs[g.type]+operationTableLabel(g.table)+' '+g.count+' 条'+(g.type==='UPDATE' && fields.length ? '（'+fields.join('、')+'）' : '')
  return ['新增/执行','修改','删除'].includes(verb) ? summary : verb+' · '+summary
}
