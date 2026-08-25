<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  loadFinanceReviewSummary,
  reviewFinanceInvoice,
  reviewFinanceRecord,
  type FinanceRecord,
  type FinanceReviewSummary,
  type InvoiceData
} from '../api/workbench'

const props = defineProps<{ type: 'SALES' | 'PURCHASE'; businessId: number }>()
const emit = defineEmits<{ close: []; saved: [closeAfter?: boolean]; message: [text: string, kind?: 'success' | 'error'] }>()

const summary = ref<FinanceReviewSummary>()
const loading = ref(true)
const busy = ref('')
const error = ref('')
const remark = ref('')
const moneyDrafts = ref<Record<number, string>>({})
const invoiceAmountDrafts = ref<Record<number, string>>({})
const invoiceNoDrafts = ref<Record<number, string>>({})

function money(value: unknown) { return Number(value ?? 0).toFixed(2) }
function date(value: unknown) { return String(value ?? '').replace('T', ' ').slice(0, 19) || '-' }
function textStatus(value: FinanceRecord['reviewStatus']) {
  return ({ PENDING: '待复核', APPROVED: '已通过', REJECTED: '已驳回' } as Record<string, string>)[String(value)] ?? '已通过'
}
function draftAmount(value: unknown) {
  const number = Math.abs(Number(value ?? 0))
  return Number.isFinite(number) && number > 0 ? String(number) : ''
}
function numberDraft(value: string) {
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined
}
function hydrateDrafts(next: FinanceReviewSummary) {
  for (const record of next.moneyRecords) {
    if (moneyDrafts.value[record.id] === undefined) moneyDrafts.value[record.id] = draftAmount(record.confirmedAmount ?? record.amount)
  }
  for (const invoice of next.invoiceRecords) {
    if (!invoice.id) continue
    if (invoiceAmountDrafts.value[invoice.id] === undefined) invoiceAmountDrafts.value[invoice.id] = draftAmount(invoice.confirmedAmount ?? invoice.taxInclusiveAmount)
    if (invoiceNoDrafts.value[invoice.id] === undefined) invoiceNoDrafts.value[invoice.id] = String(invoice.confirmedInvoiceNo ?? invoice.invoiceNo ?? '')
  }
}
async function reload() {
  loading.value = true
  error.value = ''
  try {
    const data = await loadFinanceReviewSummary(props.type, props.businessId)
    hydrateDrafts(data)
    summary.value = data
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '读取复核汇总失败'
  } finally {
    loading.value = false
  }
}
async function reviewMoney(record: FinanceRecord, approved: boolean) {
  busy.value = `money-${record.id}`
  try {
    await reviewFinanceRecord(props.type === 'SALES' ? 'receipts' : 'payments', record.id, approved, remark.value, numberDraft(moneyDrafts.value[record.id] ?? ''))
    emit('message', approved ? '资金记录已通过' : '资金记录已驳回')
    remark.value = ''
    await reload()
    emit('saved', false)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '资金复核失败'
  } finally {
    busy.value = ''
  }
}
async function reviewInvoice(invoice: InvoiceData, approved: boolean) {
  if (!invoice.id) return
  busy.value = `invoice-${invoice.id}`
  try {
    await reviewFinanceInvoice(props.type, invoice.id, approved, {
      confirmedAmount: numberDraft(invoiceAmountDrafts.value[invoice.id] ?? ''),
      confirmedInvoiceNo: invoiceNoDrafts.value[invoice.id]?.trim() || undefined,
      reviewRemark: remark.value.trim() || undefined
    })
    emit('message', approved ? '发票记录已通过' : '发票记录已驳回')
    remark.value = ''
    await reload()
    emit('saved', false)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '发票复核失败'
  } finally {
    busy.value = ''
  }
}

onMounted(reload)
</script>

<template>
  <div class="dialog-mask">
    <section class="dialog-card finance-review" role="dialog" aria-modal="true">
      <header class="finance-review-header">
        <div>
          <h2>资金与发票复核</h2>
          <p>原登记信息保留，财务确认值用于最终已收、已付和发票核对。</p>
        </div>
        <button class="dialog-close" @click="emit('close')">关闭</button>
      </header>

      <div class="finance-review-body">
        <p v-if="error" class="form-error">{{ error }}</p>
        <div v-if="loading" class="empty review-empty">正在读取复核记录...</div>
        <template v-else-if="summary">
          <div class="review-metrics">
            <div><span>确认款项</span><strong>¥ {{ money(summary.confirmedMoneyAmount) }}</strong></div>
            <div><span>确认发票</span><strong>¥ {{ money(summary.confirmedInvoiceAmount) }}</strong></div>
            <div><span>核对差额</span><strong :class="{ danger: Number(summary.differenceAmount) !== 0 }">¥ {{ money(summary.differenceAmount) }}</strong></div>
          </div>

          <label class="review-remark">
            <span>复核备注</span>
            <textarea v-model="remark" maxlength="500" placeholder="可填写驳回原因或复核说明"></textarea>
          </label>

          <section class="review-section">
            <div class="section-title"><h3>资金记录</h3><span>{{ summary.moneyRecords.length }} 条</span></div>
            <div v-if="!summary.moneyRecords.length" class="empty review-empty">暂无收付款记录</div>
            <div v-else class="review-table-wrap">
              <table>
                <thead><tr><th>登记金额</th><th>确认金额</th><th>方式</th><th>登记时间</th><th>状态</th><th>备注</th><th>操作</th></tr></thead>
                <tbody>
                  <tr v-for="record in summary.moneyRecords" :key="record.id">
                    <td><strong :class="Number(record.amount) < 0 ? 'refund' : 'amount'">¥ {{ money(record.amount) }}</strong></td>
                    <td><input v-if="record.reviewStatus === 'PENDING'" v-model="moneyDrafts[record.id]" :data-test="`money-confirmed-${record.id}`" class="compact-input" type="number" min="0" step="0.01"><span v-else>¥ {{ money(record.confirmedAmount ?? record.amount) }}</span></td>
                    <td>{{ record.paymentMethod || '-' }}</td>
                    <td>{{ date(record.occurredAt) }}</td>
                    <td><span class="status-pill" :class="String(record.reviewStatus || '').toLowerCase()">{{ textStatus(record.reviewStatus) }}</span></td>
                    <td class="remark-cell">{{ record.reviewRemark || record.paymentRemark || '-' }}</td>
                    <td class="review-actions">
                      <template v-if="record.reviewStatus === 'PENDING'">
                        <button class="approve-action" :data-test="`money-approve-${record.id}`" :disabled="busy === `money-${record.id}`" @click="reviewMoney(record, true)">通过</button>
                        <button class="reject-action" :data-test="`money-reject-${record.id}`" :disabled="busy === `money-${record.id}`" @click="reviewMoney(record, false)">驳回</button>
                      </template>
                      <span v-else :data-test="`money-complete-${record.id}`" class="review-complete">{{ textStatus(record.reviewStatus) }}，不可重复操作</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>

          <section class="review-section">
            <div class="section-title"><h3>发票记录</h3><span>{{ summary.invoiceRecords.length }} 条</span></div>
            <div v-if="!summary.invoiceRecords.length" class="empty review-empty">暂无发票记录</div>
            <div v-else class="review-table-wrap">
              <table>
                <thead><tr><th>原发票号码</th><th>确认号码</th><th>原金额</th><th>确认金额</th><th>状态</th><th>备注</th><th>操作</th></tr></thead>
                <tbody>
                  <tr v-for="invoice in summary.invoiceRecords" :key="invoice.id">
                    <td>{{ invoice.invoiceNo }}</td>
                    <td><input v-if="invoice.reviewStatus === 'PENDING' && invoice.id" v-model="invoiceNoDrafts[invoice.id]" :data-test="`invoice-no-confirmed-${invoice.id}`" class="compact-input invoice-no-input"><span v-else>{{ invoice.confirmedInvoiceNo || '-' }}</span></td>
                    <td>¥ {{ money(invoice.taxInclusiveAmount) }}</td>
                    <td><input v-if="invoice.reviewStatus === 'PENDING' && invoice.id" v-model="invoiceAmountDrafts[invoice.id]" :data-test="`invoice-confirmed-${invoice.id}`" class="compact-input" type="number" min="0" step="0.01"><span v-else>¥ {{ money(invoice.confirmedAmount ?? invoice.taxInclusiveAmount) }}</span></td>
                    <td><span class="status-pill" :class="String(invoice.reviewStatus || '').toLowerCase()">{{ textStatus(invoice.reviewStatus) }}</span></td>
                    <td class="remark-cell">{{ invoice.reviewRemark || invoice.remark || '-' }}</td>
                    <td class="review-actions">
                      <template v-if="invoice.reviewStatus === 'PENDING' && invoice.id">
                        <button class="approve-action" :data-test="`invoice-approve-${invoice.id}`" :disabled="busy === `invoice-${invoice.id}`" @click="reviewInvoice(invoice, true)">通过</button>
                        <button class="reject-action" :data-test="`invoice-reject-${invoice.id}`" :disabled="busy === `invoice-${invoice.id}`" @click="reviewInvoice(invoice, false)">驳回</button>
                      </template>
                      <span v-else-if="invoice.id" :data-test="`invoice-complete-${invoice.id}`" class="review-complete">{{ textStatus(invoice.reviewStatus) }}，不可重复操作</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
        </template>
      </div>
    </section>
  </div>
</template>

<style scoped>
.finance-review{width:min(1180px,94vw);max-height:88vh;overflow:hidden}.finance-review-header{align-items:flex-start}.finance-review-header p{margin:5px 0 0;color:#667085;font-size:13px;line-height:1.45}.finance-review-body{max-height:calc(88vh - 78px);overflow:auto;padding:18px 22px 22px;background:#f7f8fa}.review-metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-bottom:14px}.review-metrics div{border:1px solid #e1e5ea;border-radius:6px;background:#fff;padding:13px 14px}.review-metrics span{display:block;color:#667085;font-size:12px;font-weight:700}.review-metrics strong{display:block;margin-top:6px;color:#111827;font-size:22px}.review-metrics strong.danger{color:#b42318}.review-remark{display:block;margin-bottom:14px;padding:14px;border:1px solid #e1e5ea;border-radius:6px;background:#fff}.review-remark span{display:block;margin-bottom:7px;color:#30343a;font-size:13px;font-weight:650}.review-remark textarea{width:100%;min-height:70px;box-sizing:border-box;border:1px solid #c9d0d8;border-radius:5px;padding:10px 11px;background:#fff;font:inherit;line-height:1.45;resize:vertical}.review-remark textarea:focus,.compact-input:focus{border-color:#111;outline:2px solid rgb(0 0 0 / 9%);outline-offset:0}.review-section{margin-top:14px}.section-title{display:flex;align-items:center;justify-content:space-between;margin-bottom:8px}.section-title h3{margin:0;color:#111827;font-size:15px}.section-title span{color:#667085;font-size:12px}.review-table-wrap{overflow:auto;border:1px solid #dfe3e8;border-radius:6px;background:#fff}.finance-review table{width:100%;border-collapse:collapse;table-layout:auto}.finance-review th,.finance-review td{height:auto;padding:11px 12px;border-bottom:1px solid #edf0f2;text-align:left;vertical-align:middle;white-space:nowrap}.finance-review th{color:#667085;background:#f8fafc;font-size:12px;font-weight:700}.finance-review tbody tr:last-child td{border-bottom:0}.compact-input{width:120px;min-height:32px;box-sizing:border-box;border:1px solid #c9d0d8;border-radius:5px;padding:6px 8px;background:#fff;font:inherit}.invoice-no-input{width:160px}.amount{color:#111;font-weight:700}.refund{color:#b42318;font-weight:700}.remark-cell{max-width:220px;color:#48515c;white-space:normal;word-break:break-word}.status-pill{display:inline-flex;align-items:center;min-height:24px;padding:2px 9px;border-radius:999px;font-size:12px;font-weight:700}.status-pill.pending{color:#8a4b08;background:#fff4d6}.status-pill.approved{color:#167247;background:#e8f7ef}.status-pill.rejected{color:#b42318;background:#fff1f0}.review-actions{min-width:150px}.review-actions button{margin-right:6px}.approve-action,.reject-action{min-height:30px;padding:5px 10px;border:1px solid #b7c0ca;border-radius:4px;background:#fff;cursor:pointer}.approve-action{color:#116329;border-color:#9ad0ad;background:#f4fbf6}.approve-action:hover{border-color:#167247;background:#e8f7ef}.reject-action{color:#b42318;border-color:#e3a7a0;background:#fff}.reject-action:hover{background:#fff1f0}.approve-action:disabled,.reject-action:disabled{opacity:.55;cursor:not-allowed}.review-complete{color:#667085;font-size:13px;white-space:nowrap}.review-empty{border:1px dashed #d3d8df;border-radius:6px;background:#fff}.empty{padding:34px;text-align:center;color:#667085}@media (max-width: 760px){.review-metrics{grid-template-columns:1fr}.finance-review{width:96vw}.finance-review-body{padding:14px}}
</style>
