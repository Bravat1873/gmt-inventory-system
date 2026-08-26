<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { loadFinanceRecords, postAction, type FinanceRecord } from '../api/workbench'
import { loadCustomerFundOverview } from '../api/customer-funds'
import ChineseDatePicker from './ChineseDatePicker.vue'

interface OrderLine { quantity?: number; salePrice?: number }
const props = defineProps<{ order: Record<string, unknown> }>()
const emit = defineEmits<{ close: []; saved: []; message: [text: string, kind?: 'success' | 'error'] }>()
const form = reactive({ amount: 0, paymentMethod: '银行转账', receivedAt: new Date().toISOString().slice(0, 10) })
const saving = ref(false)
const error = ref('')
const customerBalance = ref(0)
const balanceLoaded = ref(false)
const records = ref<FinanceRecord[]>([])
const historyLoading = ref(false)

function amount(value: unknown) { const result = Number(value ?? 0); return Number.isFinite(result) ? result : 0 }
function money(value: number) { return value.toFixed(2) }
function date(value: unknown) { return String(value ?? '').replace('T', ' ').slice(0, 19) || '—' }
function reviewLabel(value: FinanceRecord['reviewStatus']) { return ({ PENDING: '待复核', APPROVED: '已通过', REJECTED: '已驳回' } as Record<string, string>)[String(value)] ?? '待复核' }
const lines = computed(() => Array.isArray(props.order.items) ? props.order.items as OrderLine[] : [])
const calculatedReceivable = computed(() => lines.value.reduce((total, line) => {
  return total + amount(line.quantity) * amount(line.salePrice)
}, 0))
const receivableAmount = computed(() => props.order.receivableAmount == null ? calculatedReceivable.value : amount(props.order.receivableAmount))
const receivedAmount = computed(() => amount(props.order.receivedAmount))
const outstandingAmount = computed(() => Math.max(0, receivableAmount.value - receivedAmount.value))
const afterReceiptAmount = computed(() => Math.max(0, outstandingAmount.value - amount(form.amount)))
watch(outstandingAmount, value => { form.amount = Math.min(value, customerBalance.value || value) }, { immediate: true })
async function reloadHistory() {
  historyLoading.value = true
  try { records.value = await loadFinanceRecords('SALES', Number(props.order.id)) }
  catch { records.value = [] }
  finally { historyLoading.value = false }
}
onMounted(async()=>{void reloadHistory();if(Number(props.order.customerId)>0){try{customerBalance.value=(await loadCustomerFundOverview(Number(props.order.customerId))).balance;balanceLoaded.value=true;form.amount=Math.min(outstandingAmount.value,customerBalance.value)}catch{}}})

function close() { if (!saving.value) emit('close') }
async function save() {
  const value = amount(form.amount)
  if (!form.receivedAt) { error.value = '请选择收款日期'; return }
  if (!form.paymentMethod.trim()) { error.value = '请填写收款方式'; return }
  if (value === 0) { error.value = '收款金额不能为 0；发票请单独维护'; return }
  if (value > 0 && balanceLoaded.value && value > customerBalance.value) { error.value = '本次收款金额不能超过客户可用余额'; return }
  if (value > outstandingAmount.value) { error.value = '本次收款金额不能超过未收金额'; return }
  if (value < 0 && Math.abs(value) > receivedAmount.value) { error.value = '退款金额不能超过已收金额'; return }
  saving.value = true; error.value = ''
  try {
    await postAction(`/api/finance/orders/${Number(props.order.id)}/receipt`, { amount: value, paymentMethod: form.paymentMethod.trim(), receivedAt: form.receivedAt })
    await reloadHistory()
    emit('message', '收款登记已提交复核'); emit('saved')
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '收款登记失败' } finally { saving.value = false }
}
</script>

<template>
  <div class="dialog-mask">
    <section class="dialog-card receipt-dialog" role="dialog" aria-modal="true" aria-labelledby="receipt-title">
      <header><h2 id="receipt-title">登记收款或退款</h2><button type="button" :disabled="saving" @click="close">关闭</button></header>
      <div class="receipt-content">
        <section class="fund-history">
          <div class="fund-history-head"><h3>历史记录及复核状态</h3><span>{{ records.length }} 条记录</span></div>
          <div v-if="historyLoading" class="fund-history-empty">正在读取收款记录…</div>
          <div v-else-if="!records.length" class="fund-history-empty">暂无收款记录</div>
          <div v-else class="fund-history-table"><table><thead><tr><th>登记金额</th><th>确认金额</th><th>收款方式</th><th>登记时间</th><th>状态</th><th>备注</th></tr></thead><tbody><tr v-for="record in records" :key="record.id"><td>¥ {{ money(record.amount) }}</td><td>¥ {{ money(Number(record.confirmedAmount ?? record.amount)) }}</td><td>{{ record.paymentMethod || '—' }}</td><td>{{ date(record.occurredAt) }}</td><td><span class="fund-status" :class="String(record.reviewStatus || 'PENDING').toLowerCase()">{{ reviewLabel(record.reviewStatus) }}</span></td><td class="fund-remark">{{ record.reviewRemark || record.paymentRemark || '—' }}</td></tr></tbody></table></div>
        </section>
      <form novalidate @submit.prevent="save">
        <div class="receipt-summary"><div><span>客户可用余额</span><strong data-test="customer-balance">¥ {{ money(customerBalance) }}</strong><small>复核通过后收款扣减、退款回冲</small></div>
          <div><span>订单总金额</span><strong data-test="receivable-amount">¥ {{ money(receivableAmount) }}</strong><small>订单数量 × 含税单价</small></div>
          <div><span>已收金额</span><strong data-test="received-amount">¥ {{ money(receivedAmount) }}</strong><small>已复核收款合计</small></div>
          <div><span>未收金额</span><strong>¥ {{ money(outstandingAmount) }}</strong><small>本次登记前</small></div>
        </div>
        <div class="form-grid">
          <label><span>订单编号</span><input :value="String(order.orderNo ?? '')" disabled></label>
          <label><span>本次收款金额</span><input data-test="receipt-amount" v-model.number="form.amount" type="number" step="0.01" :disabled="saving"></label>
          <label><span>收款方式</span><input v-model.trim="form.paymentMethod" :disabled="saving"></label><label data-test="receipt-date"><span>收款日期</span><ChineseDatePicker v-model="form.receivedAt" :disabled="saving" placeholder="请选择收款日期" /></label>
          <label><span>登记后未收金额</span><input :value="money(afterReceiptAmount)" disabled></label>
        </div>
        <p class="receipt-complete">资金登记需财务复核后才计入已收金额；发票通过订单发票维护单独补录。</p>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <footer><button type="button" class="secondary-action" :disabled="saving" @click="close">取消操作</button><button class="primary-action" :disabled="saving">{{ saving ? '正在保存…' : '提交复核' }}</button></footer>
      </form>
      </div>
    </section>
  </div>
</template>

<style scoped>
.receipt-dialog{width:min(900px,94vw);overflow:hidden}.receipt-content{display:grid;grid-template-columns:1fr;gap:16px;padding:18px 22px 22px;background:#f7f8fa}.receipt-content form{padding:18px;border:1px solid #e1e5ea;border-radius:6px;background:#fff}.fund-history{min-width:0;border:1px solid #e1e5ea;border-radius:6px;background:#fff;overflow:hidden}.fund-history-head{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:16px 18px 0}.fund-history-head h3{margin:0;color:#111827;font-size:16px}.fund-history-head span{color:#667085;font-size:12px}.fund-history-empty{padding:28px 18px;color:#667085;text-align:center}.fund-history-table{max-height:260px;margin:14px 18px 18px;overflow:auto;border:1px solid #dfe3e8;border-radius:6px}.fund-history table{width:100%;border-collapse:collapse;table-layout:auto}.fund-history th,.fund-history td{padding:11px 12px;border-bottom:1px solid #edf0f2;text-align:left;vertical-align:top;white-space:nowrap}.fund-history th{color:#667085;background:#f8fafc;font-size:12px}.fund-history tbody tr:last-child td{border-bottom:0}.fund-status{display:inline-flex;min-height:24px;align-items:center;padding:2px 9px;border-radius:999px;font-size:12px;font-weight:700}.fund-status.pending{color:#8a4b08;background:#fff4d6}.fund-status.approved{color:#167247;background:#e8f7ef}.fund-status.rejected{color:#b42318;background:#fff1f0}.fund-remark{max-width:240px;color:#48515c;white-space:normal;word-break:break-word}@media(max-width:760px){.receipt-content{padding:14px}.receipt-summary{grid-template-columns:1fr}.receipt-content form{padding:14px}}
</style>
