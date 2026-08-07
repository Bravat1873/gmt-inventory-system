<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { loadModule, type PageResult } from '../api/workbench'
import type { ModuleDefinition } from '../modules/module-config'

const props = defineProps<{ module: ModuleDefinition }>()
const emit = defineEmits<{ action: []; manual: []; edit: [row: Record<string, unknown>]; workflow: [row: Record<string, unknown>]; shipment: [row: Record<string, unknown>]; details: [row: Record<string, unknown>]; receipt: [row: Record<string, unknown>]; payment: [row: Record<string, unknown>]; purchaseReceipt: [row: Record<string, unknown>]; message: [text: string, kind?: 'success' | 'error'] }>()
const keyword = ref('')
const loading = ref(false)
const data = ref<PageResult>({ items: [], total: 0, page: 1, pageSize: 10, totalPages: 0 })
const sort = ref('updatedAt')
const direction = ref<'asc' | 'desc'>('desc')

function readAddress() { const p = new URLSearchParams(location.search); keyword.value = p.get('keyword') ?? ''; sort.value = p.get('sort') ?? 'updatedAt'; direction.value = p.get('direction') === 'asc' ? 'asc' : 'desc'; return Math.max(1, Number(p.get('page') ?? 1) || 1) }
function writeAddress(page: number) { const p = new URLSearchParams(); p.set('module', props.module.key); p.set('page', String(page)); if (keyword.value) p.set('keyword', keyword.value); p.set('sort', sort.value); p.set('direction', direction.value); history.replaceState(null, '', `${location.pathname}?${p}`) }
async function load(page = readAddress()) { loading.value = true; writeAddress(page); const p = new URLSearchParams({ page: String(page), keyword: keyword.value, sort: sort.value, direction: direction.value }); try { data.value = await loadModule(props.module.key, p) } catch (error) { emit('message', error instanceof Error ? error.message : '读取数据失败', 'error') } finally { loading.value = false } }
function search() { load(1) }
function orderBy(field: string) { if (!field) return; direction.value = sort.value === field && direction.value === 'asc' ? 'desc' : 'asc'; sort.value = field; load(1) }
function text(value: unknown, field?: string) {
  if (value === true) return '启用'; if (value === false) return '停用'; if (value == null || value === '') return '—'
  if (field === 'createdAt' || field === 'updatedAt') { const matched = String(value).match(/^(\d{4}-\d{2}-\d{2})[T\s](\d{2}:\d{2}:\d{2})/); if (matched) return `${matched[1]} ${matched[2]}` }
  const statuses: Record<string, string> = { DRAFT: '草稿', PENDING_CUSTOMER_PAYMENT: '确认订单并分配库存', WAITING_STOCK: '等待齐货', READY_TO_SHIP: '等待发货', SHIPPED: '已发货', PENDING_SUPPLIER_PAYMENT: '待登记付款', EXECUTING: '采购执行中', RECEIVED: '已入库', COMPLETED: '已完成', UNPAID: '未付款', PARTIALLY_PAID: '部分付款', PAID: '已付清', UNRECEIVED: '未收货', PARTIALLY_RECEIVED: '部分收货', RECEIVABLE: '收', PAYABLE: '付' }
  return statuses[String(value)] ?? String(value)
}
function shipmentCompleted(row: Record<string, unknown>) {
  return props.module.key === 'purchase'
    ? Number(row.remainingQuantity ?? 1) <= 0 || ['RECEIVED', 'COMPLETED'].includes(String(row.status))
    : String(row.status) === 'SHIPPED'
}
function isReceivable(row: Record<string, unknown>) { return String(row.cashDirection) === 'RECEIVABLE' }
function hasOutstandingAmount(row: Record<string, unknown>) { return Number(row.outstandingAmount ?? 0) > 0 }
const canManual = computed(() => ['customer', 'user', 'product', 'inventory'].includes(props.module.key))
function columnWidth(field: string) {
  const widths: Record<string, number> = { skuCode: 164, model: 108, configuration: 360, remark: 280, inventoryRemark: 280, customerName: 200, sourceSupplierName: 160, supplierName: 180, contactName: 130, bankAccount: 180, productCount: 110, supplierId: 104, productIds: 126, productSummary: 260, orderNo: 160, purchaseNo: 160, businessNo: 160, businessType: 110, cashDirection: 84, status: 150, createdAt: 170, updatedAt: 170, expectedArrivalDate: 150, totalAmount: 130, amount: 130, settledAmount: 130, outstandingAmount: 130, actualQuantity: 130, movementSummary: 260, availableQuantity: 130, lockedQuantity: 130, lockedMingAiJunQiao: 104, lockedBoLeLongMi: 104, lockedLaos: 88, lockedBeiLang: 88, lockedMalaysia: 104, inTransitQuantity: 130, productVersion: 100, color: 120, lockBody: 120, unit: 80 }
  return widths[field] ?? 150
}
const actionColumnWidth = computed(() => {
  if (props.module.key === 'order') return 292
  if (props.module.key === 'inventory') return 190
  if (props.module.key === 'purchase') return 240
  if (props.module.key === 'finance') return 210
  return 110
})
const tableMinWidth = computed(() => Math.max(1050, props.module.fields.reduce((width, field) => width + columnWidth(field), 0) + actionColumnWidth.value))
watch(() => props.module.key, () => { keyword.value = ''; sort.value = 'updatedAt'; direction.value = 'desc'; load(1) })
onMounted(() => load())
defineExpose({ reload: () => load(data.value.page) })
</script>

<template>
  <section class="module-page">
    <header class="module-heading"><h1>{{ module.label }}</h1><div class="heading-actions"><button v-if="canManual && module.importType" class="secondary-action" @click="emit('manual')">手工新增</button><button v-if="module.actionLabel" data-test="primary-action" class="primary-action" @click="emit('action')">{{ module.actionLabel }}</button></div></header>
    <div class="list-panel">
      <div class="list-toolbar"><input v-model="keyword" type="search" :placeholder="`搜索${module.label}`" @keyup.enter="search"><button class="secondary-action" @click="search">查询数据</button></div>
      <div class="table-wrap">
        <table :style="{ minWidth: `${tableMinWidth}px` }">
          <colgroup><col v-for="field in module.fields" :key="field" :style="{ width: `${columnWidth(field)}px` }"><col :style="{ width: `${actionColumnWidth}px` }"></colgroup>
          <thead><tr><th v-for="(column, index) in module.columns" :key="column" :class="{ sortable: module.sortable[index] }" @click="orderBy(module.sortable[index])">{{ column }}<span v-if="module.sortable[index] && sort === module.sortable[index]">{{ direction === 'asc' ? ' ↑' : ' ↓' }}</span></th><th>操作</th></tr></thead>
          <tbody>
            <tr v-if="loading"><td :colspan="module.columns.length + 1" class="empty-state">正在读取</td></tr>
            <tr v-else-if="!data.items.length"><td :colspan="module.columns.length + 1" class="empty-state">暂无数据</td></tr>
            <tr v-for="row in data.items" v-else :key="`${String(row.recordType ?? module.key)}-${String(row.id)}`">
              <td v-for="field in module.fields" :key="field" :title="text(row[field], field)"><span v-if="['order', 'purchase'].includes(module.key) && field === 'status'" class="order-shipment-status"><i class="shipment-status-dot" :class="shipmentCompleted(row) ? 'complete' : 'incomplete'"></i><span class="cell-content">{{ text(row[field], field) }}</span></span><span v-else-if="module.key === 'finance' && field === 'businessType'" data-test="finance-direction" class="finance-direction" :class="isReceivable(row) ? 'receivable' : 'payable'" :aria-label="isReceivable(row) ? '收款' : '付款'"><i aria-hidden="true"></i><span class="cell-content">{{ text(row[field], field) }}</span></span><span v-else class="cell-content">{{ text(row[field], field) }}</span></td>
              <td class="row-actions">
                <button v-if="module.key === 'inventory'" data-test="inventory-details" @click="emit('details', row)">查看明细</button>
                <button v-if="['order', 'finance'].includes(module.key) || (module.key === 'purchase' && row.recordType === 'PURCHASE')" data-test="view-details" @click="emit('details', row)">查看</button>
                <button v-if="module.key === 'order' && row.status !== 'DRAFT' && row.status !== 'SHIPPED'" @click="emit('receipt', row)">登记收款</button>
                <button v-if="['customer', 'user', 'product', 'supplier', 'inventory'].includes(module.key) || (module.key === 'order' && ['DRAFT', 'PENDING_CUSTOMER_PAYMENT'].includes(String(row.status)))" @click="emit('edit', row)">修改</button>
                <button v-if="module.key === 'order' && row.status !== 'DRAFT' && row.status !== 'SHIPPED'" @click="emit('shipment', row)">发货</button>
                <button v-if="module.key === 'finance' && isReceivable(row) && hasOutstandingAmount(row)" data-test="finance-receipt" @click="emit('receipt', row)">登记收款</button>
                <button v-if="module.key === 'finance' && !isReceivable(row) && hasOutstandingAmount(row)" data-test="finance-payment" @click="emit('payment', row)">登记付款</button>
                <button v-if="module.key === 'purchase' && row.recordType === 'PURCHASE' && hasOutstandingAmount(row)" data-test="purchase-payment" @click="emit('payment', row)">登记付款</button>
                <button v-if="module.key === 'purchase' && row.recordType === 'PURCHASE' && Number(row.remainingQuantity ?? 0) > 0" data-test="purchase-receipt" @click="emit('purchaseReceipt', row)">登记收货</button>
                <button v-if="module.key === 'purchase' && row.recordType === 'SUGGESTION'" @click="emit('workflow', row)">确认建议</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer class="pager"><span class="pager-summary">共 {{ data.total }} 条，每页 10 条</span><div class="pager-controls"><button :disabled="data.page <= 1" @click="load(data.page - 1)">上一页</button><span>第 {{ data.page }} / {{ Math.max(1, data.totalPages) }} 页</span><button :disabled="data.page >= data.totalPages" @click="load(data.page + 1)">下一页</button></div></footer>
    </div>
  </section>
</template>
