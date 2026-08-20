<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type { UserRole } from '../api/auth'
import { loadModule, type PageResult, type SupplierQuote } from '../api/workbench'
import type { ModuleDefinition } from '../modules/module-config'
import OverflowText from './OverflowText.vue'
import ProcurementConfigurationAlert from './ProcurementConfigurationAlert.vue'

const props = defineProps<{ module: ModuleDefinition; currentUserRole?: UserRole }>()
const emit = defineEmits<{ action: []; import: []; manual: []; edit: [row: Record<string, unknown>]; gallery: [row: Record<string, unknown>]; funds: [row: Record<string, unknown>]; workflow: [row: Record<string, unknown>]; reviewOrder: [row: Record<string, unknown>]; deleteOrder: [row: Record<string, unknown>]; shipment: [row: Record<string, unknown>]; allocation: [row: Record<string, unknown>]; details: [row: Record<string, unknown>]; receipt: [row: Record<string, unknown>]; payment: [row: Record<string, unknown>]; purchaseReceipt: [row: Record<string, unknown>]; afterSalesReceipt: [row: Record<string, unknown>]; afterSalesShipment: [row: Record<string, unknown>]; afterSalesRefund: [row: Record<string, unknown>]; afterSalesCancel: [row: Record<string, unknown>]; navigateSupplier: []; message: [text: string, kind?: 'success' | 'error'] }>()
const keyword = ref('')
const loading = ref(false)
const data = ref<PageResult>({ items: [], total: 0, page: 1, pageSize: 10, totalPages: 0 })
const sort = ref('updatedAt')
const direction = ref<'asc' | 'desc'>('desc')
const procurementAlert = ref<InstanceType<typeof ProcurementConfigurationAlert>>()
const fullIdentifierFields = new Set(['productCode', 'orderNo', 'afterSalesNo', 'purchaseNo', 'businessNo'])

function isFullIdentifier(field: string) { return fullIdentifierFields.has(field) }

function readAddress() { const p = new URLSearchParams(location.search); keyword.value = p.get('keyword') ?? ''; sort.value = p.get('sort') ?? 'updatedAt'; direction.value = p.get('direction') === 'asc' ? 'asc' : 'desc'; return Math.max(1, Number(p.get('page') ?? 1) || 1) }
function writeAddress(page: number) { const p = new URLSearchParams(); p.set('module', props.module.key); p.set('page', String(page)); if (keyword.value) p.set('keyword', keyword.value); p.set('sort', sort.value); p.set('direction', direction.value); history.replaceState(null, '', `${location.pathname}?${p}`) }
async function load(page = readAddress()) { loading.value = true; writeAddress(page); const p = new URLSearchParams({ page: String(page), keyword: keyword.value, sort: sort.value, direction: direction.value }); try { data.value = await loadModule(props.module.key, p) } catch (error) { emit('message', error instanceof Error ? error.message : '读取数据失败', 'error') } finally { loading.value = false } }
function search() { load(1) }
function orderBy(field: string) { if (!field) return; direction.value = sort.value === field && direction.value === 'asc' ? 'desc' : 'asc'; sort.value = field; load(1) }
function text(value: unknown, field?: string) {
  if (value === true) return '启用'; if (value === false) return '停用'; if (value == null || value === '') return '—'
  if (field === 'inventoryAgeDays') return `${Number(value)} 天`
  if (field === 'productType') return ({ SMART_LOCK: '智能锁', ENTRY_DOOR: '入户门' } as Record<string, string>)[String(value)] ?? String(value)
  if (field === 'materialType') return ({ FINISHED_PRODUCT: '成品', PART: '零件' } as Record<string, string>)[String(value)] ?? String(value)
  if (field === 'role') return ({ ADMIN: '管理员', FINANCE: '财务', USER: '普通用户' } as Record<string, string>)[String(value)] ?? String(value)
  if (field === 'createdAt' || field === 'updatedAt' || field === 'oldestStockDate') { const matched = String(value).match(/^(\d{4}-\d{2}-\d{2})[T\s](\d{2}:\d{2}:\d{2})/); if (matched) return `${matched[1]} ${matched[2]}` }
  const statuses: Record<string, string> = { DRAFT: '草稿', PENDING_CUSTOMER_PAYMENT: '正式订单', WAITING_STOCK: '等待齐货', READY_TO_SHIP: '等待发货', SHIPPED: '已发货', PENDING_SUPPLIER_PAYMENT: '待登记付款', EXECUTING: '采购执行中', RECEIVED: '已入库', COMPLETED: '已完成', UNPAID: '未付款', PARTIALLY_PAID: '部分付款', PAID: '已付清', UNRECEIVED: '未收货', PARTIALLY_RECEIVED: '部分收货', RECEIVABLE: '收', PAYABLE: '付' }
  const afterSales: Record<string,string> = { RETURN:'退货', EXCHANGE:'换货', WAITING_RETURN:'待收退货', RETURN_RECEIVED:'已收退货', WAITING_REPLACEMENT:'待发换货', COMPLETED:'已完成', CANCELLED:'已取消' }
  return afterSales[String(value)] ?? statuses[String(value)] ?? String(value)
}
function supplierQuotes(row: Record<string, unknown>) { return Array.isArray(row.supplierQuotes) ? row.supplierQuotes as SupplierQuote[] : [] }
function quotePrice(value: number) { return Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 4 }) }
function supplierQuoteSummary(row: Record<string, unknown>) {
  const quotes = supplierQuotes(row)
  if (!quotes.length) return '—'
  const first = quotes[0]
  const remainder = quotes.length > 1 ? ` + 其余 ${quotes.length - 1} 家` : ''
  return `${first.supplierName}：¥${quotePrice(first.purchasePrice)}${remainder}`
}
function supplierQuoteDetails(row: Record<string, unknown>) {
  return supplierQuotes(row).map(quote => `${quote.supplierName}：¥${quotePrice(quote.purchasePrice)}`).join('\n') || '暂无供应商报价'
}
function shipmentCompleted(row: Record<string, unknown>) {
  return props.module.key === 'purchase'
    ? Number(row.remainingQuantity ?? 1) <= 0 || ['RECEIVED', 'COMPLETED'].includes(String(row.status))
    : String(row.status) === 'SHIPPED'
}
function isReceivable(row: Record<string, unknown>) { return String(row.cashDirection) === 'RECEIVABLE' }
function hasOutstandingAmount(row: Record<string, unknown>) { return Number(row.outstandingAmount ?? 0) > 0 }
const canManual = computed(() => ['customer', 'user', 'product', 'supplier', 'inventory'].includes(props.module.key))
const canUsePrimary = computed(() => Boolean(props.module.actionLabel)
  && (props.module.key !== 'user' || props.currentUserRole === 'ADMIN')
  && (props.module.key !== 'order' || ['ADMIN', 'USER'].includes(props.currentUserRole ?? 'USER'))
  && (props.module.importType !== 'PRODUCT' || props.currentUserRole === 'ADMIN')
  && (props.module.importType !== 'COST' || ['ADMIN', 'FINANCE'].includes(props.currentUserRole ?? 'USER')))
function primary() { if (canUsePrimary.value) emit('action') }
function importOrders() { if (canUsePrimary.value && props.module.importActionLabel) emit('import') }
function canEditRow() { return props.module.key !== 'user' || props.currentUserRole === 'ADMIN' }
function productImageUrl(row: Record<string, unknown>) {
  if (row.primaryImageUrl) return String(row.primaryImageUrl)
  return row.primaryImageId ? `/api/product-images/${Number(row.primaryImageId)}/content` : ''
}
function columnWidth(field: string) {
  if (field === 'productImage') return 84
  if (field === 'supplierQuotes') return 260
  const widths: Record<string, number> = { productCode: 240, afterSalesNo: 240, customerPartNumber: 164, model: 108, configuration: 360, remark: 280, inventoryRemark: 280, customerName: 200, sourceSupplierName: 160, supplierName: 180, contactName: 130, bankAccount: 180, productCount: 110, supplierId: 104, productIds: 126, productSummary: 260, orderNo: 240, purchaseNo: 240, businessNo: 240, businessType: 110, cashDirection: 84, status: 150, createdAt: 170, updatedAt: 170, oldestStockDate: 170, inventoryAgeDays: 90, expectedArrivalDate: 150, totalAmount: 130, amount: 130, settledAmount: 130, outstandingAmount: 130, actualQuantity: 130, movementSummary: 260, availableQuantity: 140, lockedQuantity: 130, inTransitQuantity: 130, pendingDeliveryQuantity: 130, supplyDemandSurplus: 170, productVersion: 100, color: 120, lockBody: 120, unit: 80 }
  return widths[field] ?? 150
}
const actionColumnWidth = computed(() => {
  if (props.module.key === 'customer') return 190
  if (props.module.key === 'order') return 470
  if (props.module.key === 'purchase') return 240
  if (props.module.key === 'afterSales') return 300
  if (props.module.key === 'finance') return 210
  return 110
})
const tableMinWidth = computed(() => Math.max(1050, props.module.fields.reduce((width, field) => width + columnWidth(field), 0) + actionColumnWidth.value))
watch(() => props.module.key, () => { keyword.value = ''; sort.value = 'updatedAt'; direction.value = 'desc'; load(1) })
onMounted(() => load())
defineExpose({ reload: async () => { await load(data.value.page); await procurementAlert.value?.reload() } })
</script>

<template>
  <section class="module-page">
    <header class="module-heading"><h1>{{ module.label }}</h1><div class="heading-actions"><button v-if="canManual && module.importType" class="secondary-action" @click="emit('manual')">手工新增</button><button v-if="canUsePrimary && module.importActionLabel" data-test="import-action" class="secondary-action" @click="importOrders">{{ module.importActionLabel }}</button><button v-if="canUsePrimary" data-test="primary-action" class="primary-action" @click="primary">{{ module.actionLabel }}</button></div></header>
    <div class="list-panel">
      <div class="list-toolbar"><input v-model="keyword" type="search" :placeholder="`搜索${module.label}`" @keyup.enter="search"><button class="secondary-action" @click="search">查询数据</button></div>
      <ProcurementConfigurationAlert v-if="module.key === 'purchase'" ref="procurementAlert" @navigate-supplier="emit('navigateSupplier')" @message="(text, kind) => emit('message', text, kind)" />
      <div class="table-wrap">
        <table :style="{ minWidth: `${tableMinWidth}px` }">
          <colgroup><col v-for="field in module.fields" :key="field" :style="{ width: `${columnWidth(field)}px` }"><col :style="{ width: `${actionColumnWidth}px` }"></colgroup>
          <thead><tr><th v-for="(column, index) in module.columns" :key="column" :class="{ sortable: module.sortable[index] }" @click="orderBy(module.sortable[index])">{{ column }}<span v-if="module.sortable[index] && sort === module.sortable[index]">{{ direction === 'asc' ? ' ↑' : ' ↓' }}</span></th><th>操作</th></tr></thead>
          <tbody>
            <tr v-if="loading"><td :colspan="module.columns.length + 1" class="empty-state">正在读取</td></tr>
            <tr v-else-if="!data.items.length"><td :colspan="module.columns.length + 1" class="empty-state">暂无数据</td></tr>
            <tr v-for="row in data.items" v-else :key="`${String(row.recordType ?? module.key)}-${String(row.id)}`">
              <td v-for="field in module.fields" :key="field" :class="{ 'full-identifier-cell': isFullIdentifier(field) }">
                <button
                  v-if="module.key === 'product' && field === 'productImage'"
                  type="button"
                  class="product-thumbnail"
                  data-test="product-thumbnail"
                  :aria-label="Number(row.imageCount ?? 0) > 0 ? `查看产品图片，共 ${Number(row.imageCount)} 张` : '查看产品图库，暂无图片'"
                  @click="emit('gallery', row)"
                >
                  <img v-if="productImageUrl(row)" :src="productImageUrl(row)" alt="产品主图" width="56" height="56" loading="lazy">
                  <span v-else class="product-thumbnail-empty">暂无图片</span>
                  <span v-if="Number(row.imageCount ?? 0) > 1" class="product-image-count" data-test="product-image-count">{{ Number(row.imageCount) }}</span>
                </button>
                <span v-else-if="module.key === 'product' && field === 'supplierQuotes'" data-test="supplier-quotes" class="supplier-quotes" :title="supplierQuoteDetails(row)">{{ supplierQuoteSummary(row) }}</span>
                <span v-else-if="['order', 'purchase'].includes(module.key) && field === 'status'" class="order-shipment-status"><i class="shipment-status-dot" :class="shipmentCompleted(row) ? 'complete' : 'incomplete'"></i><OverflowText :value="text(row[field], field)" /></span>
                <span v-else-if="module.key === 'finance' && field === 'businessType'" data-test="finance-direction" class="finance-direction" :class="isReceivable(row) ? 'receivable' : 'payable'" :aria-label="isReceivable(row) ? '收款' : '付款'"><i aria-hidden="true"></i><OverflowText :value="text(row[field], field)" /></span>
                <span v-else-if="module.key === 'inventory' && field === 'supplyDemandSurplus'" data-test="supply-demand-surplus" class="supply-demand-surplus" :class="{ negative: Number(row[field]) < 0 }"><OverflowText :value="text(row[field], field)" /><small v-if="Number(row[field]) < 0">采购缺口 {{ Number(row.purchaseShortageQuantity ?? Math.abs(Number(row[field]))) }}</small></span>
                <span v-else-if="isFullIdentifier(field)" class="full-identifier">{{ text(row[field], field) }}</span>
                <OverflowText v-else :value="text(row[field], field)" />
              </td>
              <td class="row-actions"><div class="row-actions-content">
                <button v-if="['order', 'finance'].includes(module.key) || (module.key === 'purchase' && row.recordType === 'PURCHASE')" data-test="view-details" @click="emit('details', row)">查看</button>
                <button v-if="module.key === 'order' && row.status !== 'DRAFT' && row.status !== 'SHIPPED'" @click="emit('receipt', row)">登记收款</button>
                <button v-if="module.key === 'order' && row.status === 'DRAFT'" data-test="review-order" @click="emit('reviewOrder', row)">复核</button>
                <button v-if="module.key === 'order' && ['DRAFT', 'PENDING_CUSTOMER_PAYMENT', 'READY_TO_SHIP', 'WAITING_STOCK'].includes(String(row.status))" data-test="delete-order" class="danger-action" @click="emit('deleteOrder', row)">删除</button>
                <button v-if="module.key === 'order' && ['READY_TO_SHIP', 'WAITING_STOCK'].includes(String(row.status))" data-test="order-allocation" @click="emit('allocation', row)">分配库存</button>
                <button v-if="module.key === 'customer'" data-test="customer-funds" @click="emit('funds', row)">资金管理</button>
                <button v-if="canEditRow() && (['customer', 'user', 'product', 'supplier', 'inventory'].includes(module.key) || (module.key === 'order' && ['DRAFT', 'PENDING_CUSTOMER_PAYMENT', 'READY_TO_SHIP', 'WAITING_STOCK'].includes(String(row.status))))" @click="emit('edit', row)">修改</button>
                <button v-if="module.key === 'order' && row.status !== 'DRAFT' && row.status !== 'SHIPPED'" @click="emit('shipment', row)">发货</button>
                <button v-if="module.key === 'finance' && isReceivable(row) && hasOutstandingAmount(row)" data-test="finance-receipt" @click="emit('receipt', row)">登记收款</button>
                <button v-if="module.key === 'finance' && !isReceivable(row) && hasOutstandingAmount(row)" data-test="finance-payment" @click="emit('payment', row)">登记付款</button>
                <button v-if="module.key === 'purchase' && row.recordType === 'PURCHASE' && hasOutstandingAmount(row)" data-test="purchase-payment" @click="emit('payment', row)">登记付款</button>
                <button v-if="module.key === 'purchase' && row.recordType === 'PURCHASE' && Number(row.remainingQuantity ?? 0) > 0" data-test="purchase-receipt" @click="emit('purchaseReceipt', row)">登记收货</button>
                <button v-if="module.key === 'purchase' && row.recordType === 'SUGGESTION'" data-test="review-procurement" @click="emit('workflow', row)">复核</button>
                <button v-if="module.key === 'afterSales'" @click="emit('edit', row)">修改</button>
                <button v-if="module.key === 'afterSales' && ['WAITING_RETURN','RETURN_RECEIVED'].includes(String(row.status))" @click="emit('afterSalesReceipt', row)">确认收货</button>
                <button v-if="module.key === 'afterSales' && row.afterSalesType === 'RETURN' && row.status === 'RETURN_RECEIVED'" data-test="after-sales-refund" @click="emit('afterSalesRefund', row)">申请退款</button>
                <button v-if="module.key === 'afterSales' && row.status === 'WAITING_REPLACEMENT'" @click="emit('afterSalesShipment', row)">换货发出</button>
                <button v-if="module.key === 'afterSales' && row.status === 'WAITING_RETURN'" @click="emit('afterSalesCancel', row)">取消</button>
              </div></td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer class="pager"><span class="pager-summary">共 {{ data.total }} 条，每页 10 条</span><div class="pager-controls"><button :disabled="data.page <= 1" @click="load(data.page - 1)">上一页</button><span>第 {{ data.page }} / {{ Math.max(1, data.totalPages) }} 页</span><button :disabled="data.page >= data.totalPages" @click="load(data.page + 1)">下一页</button></div></footer>
    </div>
  </section>
</template>


