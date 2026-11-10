import type { OperationChange, OperationRecord } from '../api/operation-records'
import { operationFieldLabel, operationTableLabel, operationValue } from './operation-labels'

const paymentFromOrder = (row: OperationRecord) => /^\/api\/finance\/orders\/\d+\/pay$/.test(row.requestPath)
export const businessModule = (row: OperationRecord) => paymentFromOrder(row) ? '订单管理' : row.moduleName
const mainDocuments: Record<string, string[]> = {
  订单管理: ['订单号'], 采购管理: ['采购单号', '采购建议单号'], 售后管理: ['售后单号'],
  财务管理: ['订单号','采购单号','发票号'], 资金管理: ['资金单号','申请单号','订单号'],
  客户管理: ['客户'], 产品管理: ['产品编号'], 供应商管理: ['供应商'], 用户管理: ['账号']
}
export function primaryDocument(row: OperationRecord) {
  if (row.primaryDocument) return row.primaryDocument
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
  订单管理:['sales_order','sales_order_item','sales_shipment'], 采购管理:['purchase_order','purchase_order_item','goods_receipt_item','procurement_suggestion_item'],
  库存管理:['inventory_balance'], 资金管理:['customer_fund_request','customer_fund_account'],
  财务管理:['customer_receipt','supplier_payment','sales_invoice','purchase_invoice'],
  客户管理:['customer'], 产品管理:['sku'], 供应商管理:['supplier'], 售后管理:['after_sales_order','after_sales_refund','after_sales_return_line','after_sales_replacement_line','after_sales_receipt_line','after_sales_shipment_line']
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
    if (shipped) descriptions.push((e.productLabel ? e.productLabel+'：' : e.line ? '明细 '+e.line+'：' : '')+'发货数量 '+shipped.before+' → '+shipped.after)
  }
  if (!descriptions.length) {
    const primary = examples.filter(e=>main.includes(e.table))
    for (const e of primary.length ? primary : examples.slice(0,1)) {
      const name = operationTableLabel(e.table)
      const prefix = e.productLabel ? e.productLabel+'：' : e.line ? '明细 '+e.line+'：' : ''
      if (e.type==='UPDATE') {
        for (const f of e.fields) descriptions.push(prefix+operationFieldLabel(f.field)+' '+valueText(f.field,f.before)+' → '+valueText(f.field,f.after))
      } else {
        const f=e.fields.find(f=>['total_amount','quantity','amount','refund_amount'].includes(f.field))
        descriptions.push(prefix+verbs[e.type]+name+(f ? '，'+operationFieldLabel(f.field)+' '+valueText(f.field,e.type==='DELETE'?f.before:f.after) : ''))
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

type BusinessLine = { id: number; subject: string; parts: string[]; rank: number }
const metadata = /^(id|version|line_no|created_at|updated_at|operated_at|file_hash|storage_key|cell_values|normalized_data|_protected)$|_(id|by)$/
const quantityField = (key: string) => key === 'quantity' || key.endsWith('_quantity')
const readableLabel = (key: string, table: string) =>
  key === 'shipped_quantity' ? '发货数量' : key === 'locked_quantity' && table === 'sales_order_item' ? '订单锁定数量' : operationFieldLabel(key)
const display = (key: string, value: unknown) => /_at$/.test(key) && typeof value === 'string'
  ? value.replace('T',' ').replace(/\.\d+$/,'') : valueText(key,value)
function oneRef(refs: string | undefined, key: string) {
  const values = [...new Set((refs || '').split('；').filter(s=>s.startsWith(key+'：')).map(s=>s.slice(key.length+1)))]
  return values.length === 1 ? values[0] : ''
}

export function businessLines(detail: {operation: OperationRecord; changes: OperationChange[]}): BusinessLine[] {
  const main = mainTables[businessModule(detail.operation)] || []
  const lines: BusinessLine[] = []
  for (const c of detail.changes) {
    if (c.preserved || c.table === 'document_number_sequence') continue
    const table = operationTableLabel(c.table)
    const type = verbs[c.type]
    const line = oneRef(c.relatedInfo,'明细行')
    const warehouse = c.table.startsWith('inventory_') ? oneRef(c.relatedInfo,'仓库') : ''
    const subject = [c.productLabel || (line ? '明细 '+line : ''), warehouse].filter(Boolean).join(' · ') || table
    const rank = c.table === 'sales_order_item' && c.fields.some(f=>f.field==='shipped_quantity') ? 0
      : c.table === 'inventory_balance' || c.table === 'customer_receipt' || c.table === 'supplier_payment' ? 1
      : main.includes(c.table) ? 2 : 3
    const fields = c.fields.filter(f=>!metadata.test(f.field) && operationFieldLabel(f.field)!==f.field)
    let parts: string[] = []
    if (c.type === 'UPDATE') {
      const ordered = [...fields].sort((a,b) => Number(b.field==='shipped_quantity')-Number(a.field==='shipped_quantity'))
      for (const f of ordered) parts.push(readableLabel(f.field,c.table)+' '+display(f.field,f.before)+' → '+display(f.field,f.after))
      if (!parts.length && c.fields.some(f=>!metadata.test(f.field) || (/_id$/.test(f.field) && f.field!=='id'))) parts.push(type+table)
    } else if (c.table === 'inventory_transaction') {
      for (const [key,label] of [['actual','实际库存'],['locked','锁定库存'],['transit','在途库存']]) {
        const get = (suffix: string) => { const f=c.fields.find(f=>f.field===key+'_'+suffix); return c.type==='DELETE' ? f?.before : f?.after }
        const before=get('before'), after=get('after')
        if (before!=null && after!=null && String(before)!==String(after)) parts.push(label+' '+before+' → '+after)
      }
      parts = [type+'库存流水'+(parts.length ? '：'+parts.join('，') : '')]
    } else {
      const values = fields.map(f=>({key:f.field,value:c.type==='DELETE'?f.before:f.after})).filter(f=>f.value!=null && f.value!=='')
      const number = c.fields.find(f=>['shipment_no','order_no','purchase_no','suggestion_no','receipt_no','after_sales_no','invoice_no','payment_no','request_no'].includes(f.field))
      const doc = number && (c.type==='DELETE' ? number.before : number.after)
      const amount = values.find(f=>f.key==='confirmed_amount') ?? values.find(f=>['amount','total_amount','refund_amount'].includes(f.key))
      const quantities = values.filter(f=>quantityField(f.key) && (f.key==='quantity' || Number(f.value)!==0))
      const facts = [...quantities,...(amount ? [amount] : [])].map(f=>readableLabel(f.key,c.table)+' '+display(f.key,f.value))
      parts = [type+table+(doc ? ' '+doc : '')+(facts.length ? '，'+facts.join('，') : '')]
    }
    if (parts.length) lines.push({id:c.id,subject,parts,rank})
  }
  return lines.sort((a,b)=>a.rank-b.rank || a.id-b.id)
}
