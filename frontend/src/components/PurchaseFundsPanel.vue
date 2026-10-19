<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { UserRole } from '../api/auth'
import { loadModule, loadPurchase, loadFinanceRecords, type PageResult, type FinanceRecord } from '../api/workbench'
import PaymentDialog from './PaymentDialog.vue'
import FinanceReviewDialog from './FinanceReviewDialog.vue'
import { formatDateTimeToSecond } from '../utils/date-time'

const props = defineProps<{ currentUserRole: UserRole }>()
const emit = defineEmits<{ message: [text: string, kind?: 'success' | 'error'] }>()
const canReview = computed(() => ['ADMIN', 'FINANCE'].includes(props.currentUserRole))
const data = ref<PageResult>({ items: [], total: 0, page: 1, pageSize: 10, totalPages: 0 })
const keyword = ref(''), loading = ref(false), error = ref(''), opening = ref(false)
const payment = ref<Record<string, unknown>>(), reviewId = ref<number>()
const history = ref<{ businessNo: string; records: FinanceRecord[] }>()
const money = (value: unknown) => Number(value ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 })
const statusLabel = (value: string | null | undefined) => ({ PENDING: '待复核', APPROVED: '已通过', REJECTED: '已驳回' }[value ?? 'APPROVED'] ?? value)
let generation = 0
async function reload(page = data.value.page) {
  const token = ++generation
  loading.value = true; error.value = ''
  try {
    const result = await loadModule('purchaseFunds', new URLSearchParams({ page: String(page), keyword: keyword.value, sort: 'updatedAt', direction: 'desc' }))
    if (token === generation) data.value = result
  } catch (cause) {
    if (token === generation) { error.value = cause instanceof Error ? cause.message : '读取采购付款失败'; data.value = { items: [], total: 0, page: 1, pageSize: 10, totalPages: 0 } }
  } finally { if (token === generation) loading.value = false }
}
async function openPayment(row: Record<string, unknown>) {
  opening.value = true; error.value = ''
  try { payment.value = { ...await loadPurchase(Number(row.id)) } }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '读取采购单失败' }
  finally { opening.value = false }
}
async function openHistory(row: Record<string, unknown>) {
  opening.value = true; error.value = ''
  try { history.value = { businessNo: String(row.businessNo), records: await loadFinanceRecords('PURCHASE', Number(row.id)) } }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '读取付款记录失败' }
  finally { opening.value = false }
}
async function paymentSaved() { payment.value = undefined; await reload() }
onMounted(() => reload(1))
defineExpose({ reload })
</script>

<template>
  <section class="purchase-funds-panel">
    <p class="field-hint">已复核采购单自动同步至此。已付金额按审核通过的付款累计，待复核和已驳回记录不计入已付金额。</p>
    <form class="purchase-filters" @submit.prevent="reload(1)">
      <label>采购单号 / 供应商<input v-model="keyword" data-test="purchase-funds-keyword" placeholder="输入采购单号或供应商名称"></label>
      <button class="secondary-action" :disabled="loading">查询</button>
      <button class="secondary-action" type="button" :disabled="loading" @click="reload()">刷新</button>
    </form>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div class="purchase-table-scroll" :aria-busy="loading">
      <table><thead><tr><th>采购单号</th><th>供应商</th><th>采购金额</th><th>已付金额</th><th>未付金额</th><th>状态</th><th>修改时间</th><th>操作</th></tr></thead>
        <tbody><tr v-for="row in data.items" :key="Number(row.id)">
          <td>{{ row.businessNo }}</td><td>{{ row.counterparty }}</td><td>¥ {{ money(row.amount) }}</td><td data-test="purchase-paid">¥ {{ money(row.settledAmount) }}</td><td data-test="purchase-unpaid">¥ {{ money(row.outstandingAmount) }}</td><td>{{ row.status }}</td><td>{{ formatDateTimeToSecond(String(row.updatedAt ?? '')) }}</td>
          <td><div class="purchase-actions"><button v-if="canReview && Number(row.outstandingAmount) > 0" data-test="fund-purchase-payment" :disabled="opening || loading" @click="openPayment(row)">登记付款</button><button v-if="canReview && Number(row.pendingReviewCount) > 0" data-test="fund-purchase-review" @click="reviewId=Number(row.id)">复核</button><button data-test="fund-purchase-history" :disabled="opening" @click="openHistory(row)">付款记录</button></div></td>
        </tr><tr v-if="!data.items.length"><td colspan="8" class="empty">{{ loading ? '正在读取采购付款…' : '暂无符合条件的采购单' }}</td></tr></tbody>
      </table>
    </div>
    <footer class="purchase-pagination"><span>共 {{ data.total }} 条 · 第 {{ data.page }} / {{ Math.max(1, data.totalPages) }} 页</span><button :disabled="loading || data.page <= 1" @click="reload(data.page-1)">上一页</button><button :disabled="loading || data.page >= data.totalPages" @click="reload(data.page+1)">下一页</button></footer>
  </section>
  <PaymentDialog v-if="payment" :purchase="payment" @close="payment=undefined" @saved="paymentSaved" @message="(text,kind)=>emit('message',text,kind)" />
  <FinanceReviewDialog v-if="reviewId && canReview" type="PURCHASE" :business-id="reviewId" :can-review-invoices="currentUserRole==='ADMIN'" @close="reviewId=undefined" @saved="reload()" @message="(text,kind)=>emit('message',text,kind)" />
  <div v-if="history" class="dialog-mask"><section class="dialog-card purchase-history" role="dialog" aria-modal="true" aria-labelledby="purchase-history-title">
    <header><div><h2 id="purchase-history-title">采购付款记录</h2><p>{{ history.businessNo }}</p></div><button @click="history=undefined">关闭</button></header>
    <div class="purchase-table-scroll"><table><thead><tr><th>登记金额</th><th>确认金额</th><th>付款方式</th><th>登记时间</th><th>状态</th><th>备注</th></tr></thead><tbody><tr v-for="record in history.records" :key="record.id"><td>¥ {{ money(record.amount) }}</td><td>{{ record.reviewStatus === 'PENDING' || record.reviewStatus === 'REJECTED' ? '—' : '¥ ' + money(record.confirmedAmount ?? record.amount) }}</td><td>{{ record.paymentMethod || '—' }}</td><td>{{ formatDateTimeToSecond(record.occurredAt) }}</td><td>{{ statusLabel(record.reviewStatus) }}</td><td>{{ record.reviewRemark || record.paymentRemark || '—' }}</td></tr><tr v-if="!history.records.length"><td colspan="6" class="empty">暂无付款记录</td></tr></tbody></table></div>
  </section></div>
</template>

<style scoped>
.purchase-filters{display:flex;align-items:end;gap:12px;flex-wrap:wrap;margin:16px 0}.purchase-filters label{display:flex;flex-direction:column;gap:8px}.purchase-filters input{width:min(320px,75vw);padding:9px;border:1px solid #cbd5e1;border-radius:4px}.purchase-table-scroll{overflow:auto;border:1px solid #dce3ec;background:#fff}.purchase-table-scroll table{border-collapse:collapse;min-width:1050px;width:100%;font-size:13px;table-layout:auto}.purchase-table-scroll th,.purchase-table-scroll td{padding:13px 12px;border-bottom:1px solid #e5e9ef;text-align:left;white-space:nowrap;font-variant-numeric:tabular-nums;overflow:visible}.purchase-table-scroll th{background:#f8fafc}.purchase-actions{display:flex;gap:8px}.purchase-actions button,.purchase-pagination button{border:1px solid #cbd5e1;background:#fff;border-radius:4px;padding:6px 10px;cursor:pointer}.purchase-pagination{display:flex;justify-content:flex-end;align-items:center;gap:12px;flex-wrap:wrap;margin-top:16px}.purchase-history{width:min(1120px,94vw)}.empty{text-align:center!important;padding:30px!important;color:#64748b}
</style>
