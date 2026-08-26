<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { loadFinanceRecords, postAction, type FinanceRecord } from '../api/workbench'

const props = defineProps<{ purchase: Record<string, unknown> }>()
const emit = defineEmits<{ close: []; saved: []; message: [text: string, kind?: 'success' | 'error'] }>()
const form = reactive({ amount: '', paymentMethod: '银行转账', paymentRemark: '' })
const saving = ref(false)
const error = ref('')
const records = ref<FinanceRecord[]>([])
const historyLoading = ref(false)

function numeric(value: unknown) { const result = Number(value ?? 0); return Number.isFinite(result) ? result : 0 }
function money(value: number) { return value.toFixed(2) }
function date(value: unknown) { return String(value ?? '').replace('T', ' ').slice(0, 19) || '—' }
function reviewLabel(value: FinanceRecord['reviewStatus']) { return ({ PENDING: '待复核', APPROVED: '已通过', REJECTED: '已驳回' } as Record<string, string>)[String(value)] ?? '待复核' }
const purchaseNo = computed(() => String(props.purchase.purchaseNo ?? ''))
const totalAmount = computed(() => numeric(props.purchase.totalAmount))
const paidAmount = computed(() => numeric(props.purchase.paidAmount ?? props.purchase.settledAmount))
const outstandingAmount = computed(() => numeric(props.purchase.outstandingAmount ?? Math.max(0, totalAmount.value - paidAmount.value)))
watch(outstandingAmount, value => { form.amount = value > 0 ? String(value) : '' }, { immediate: true })
async function reloadHistory() {
  historyLoading.value = true
  try { records.value = await loadFinanceRecords('PURCHASE', Number(props.purchase.id)) }
  catch { records.value = [] }
  finally { historyLoading.value = false }
}
onMounted(reloadHistory)

function validate() {
  if (!Number.isInteger(Number(props.purchase.id)) || Number(props.purchase.id) <= 0) return '未找到可登记付款的采购单'
  if (!Number.isFinite(Number(form.amount)) || Number(form.amount) <= 0) return '付款金额必须大于 0；发票请单独维护'
  if (Number(form.amount) > outstandingAmount.value) return '本次付款金额不能超过未付金额'
  if (!form.paymentMethod.trim()) return '请填写付款方式'
  return ''
}

function requestClose() {
  if (!saving.value) emit('close')
}

async function save() {
  if (saving.value) return
  error.value = validate()
  if (error.value) return
  saving.value = true
  try {
    await postAction(`/api/procurement/purchases/${Number(props.purchase.id)}/payment`, {
      amount: Number(form.amount), paymentMethod: form.paymentMethod.trim(),
      paymentRemark: form.paymentRemark.trim() || undefined
    })
    await reloadHistory()
    emit('message', '付款登记已提交复核')
    emit('saved')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '付款登记失败'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="dialog-mask">
    <section class="dialog-card payment-dialog" role="dialog" aria-modal="true" aria-labelledby="payment-title">
      <header><h2 id="payment-title">登记付款</h2><button type="button" :disabled="saving" @click="requestClose">关闭</button></header>
      <div class="payment-content">
        <section class="fund-history">
          <div class="fund-history-head"><h3>历史记录及复核状态</h3><span>{{ records.length }} 条记录</span></div>
          <div v-if="historyLoading" class="fund-history-empty">正在读取付款记录…</div>
          <div v-else-if="!records.length" class="fund-history-empty">暂无付款记录</div>
          <div v-else class="fund-history-table"><table><thead><tr><th>登记金额</th><th>确认金额</th><th>付款方式</th><th>登记时间</th><th>状态</th><th>备注</th></tr></thead><tbody><tr v-for="record in records" :key="record.id"><td>¥ {{ money(record.amount) }}</td><td>¥ {{ money(Number(record.confirmedAmount ?? record.amount)) }}</td><td>{{ record.paymentMethod || '—' }}</td><td>{{ date(record.occurredAt) }}</td><td><span class="fund-status" :class="String(record.reviewStatus || 'PENDING').toLowerCase()">{{ reviewLabel(record.reviewStatus) }}</span></td><td class="fund-remark">{{ record.reviewRemark || record.paymentRemark || '—' }}</td></tr></tbody></table></div>
        </section>
      <form novalidate @submit.prevent="save">
        <div class="payment-summary">
          <div><span>采购总金额</span><strong>¥ {{ money(totalAmount) }}</strong></div>
          <div><span>已付金额</span><strong>¥ {{ money(paidAmount) }}</strong></div>
          <div><span>未付金额</span><strong class="payment-outstanding">¥ {{ money(outstandingAmount) }}</strong></div>
        </div>
        <div class="form-grid">
          <label><span>采购单号</span><input data-test="payment-purchase-no" :value="purchaseNo" disabled></label>
          <label><span>本次付款金额</span><input data-test="payment-amount" v-model="form.amount" type="number" min="0" :max="outstandingAmount" step="0.01" :aria-invalid="Boolean(error)" :disabled="saving"></label>
          <label><span>付款方式</span><input v-model="form.paymentMethod" :aria-invalid="Boolean(error)" :disabled="saving"></label>
          <label><span>付款备注</span><textarea v-model="form.paymentRemark" maxlength="500" :disabled="saving"></textarea></label>
        </div>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <p class="receipt-complete">资金登记需财务复核后才计入已付金额；发票通过采购发票维护单独补录。</p>
        <footer><button type="button" class="secondary-action" :disabled="saving" @click="requestClose">取消操作</button><button class="primary-action" :disabled="saving">{{ saving ? '正在保存…' : '提交复核' }}</button></footer>
      </form>
      </div>
    </section>
  </div>
</template>

<style scoped>
.payment-dialog{width:min(900px,94vw);overflow:hidden}.payment-content{display:grid;grid-template-columns:1fr;gap:16px;padding:18px 22px 22px;background:#f7f8fa}.payment-content form{padding:18px;border:1px solid #e1e5ea;border-radius:6px;background:#fff}.fund-history{min-width:0;border:1px solid #e1e5ea;border-radius:6px;background:#fff;overflow:hidden}.fund-history-head{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:16px 18px 0}.fund-history-head h3{margin:0;color:#111827;font-size:16px}.fund-history-head span{color:#667085;font-size:12px}.fund-history-empty{padding:28px 18px;color:#667085;text-align:center}.fund-history-table{max-height:260px;margin:14px 18px 18px;overflow:auto;border:1px solid #dfe3e8;border-radius:6px}.fund-history table{width:100%;border-collapse:collapse;table-layout:auto}.fund-history th,.fund-history td{padding:11px 12px;border-bottom:1px solid #edf0f2;text-align:left;vertical-align:top;white-space:nowrap}.fund-history th{color:#667085;background:#f8fafc;font-size:12px}.fund-history tbody tr:last-child td{border-bottom:0}.fund-status{display:inline-flex;min-height:24px;align-items:center;padding:2px 9px;border-radius:999px;font-size:12px;font-weight:700}.fund-status.pending{color:#8a4b08;background:#fff4d6}.fund-status.approved{color:#167247;background:#e8f7ef}.fund-status.rejected{color:#b42318;background:#fff1f0}.fund-remark{max-width:240px;color:#48515c;white-space:normal;word-break:break-word}@media(max-width:760px){.payment-content{padding:14px}.payment-summary{grid-template-columns:1fr}.payment-content form{padding:14px}}
</style>
