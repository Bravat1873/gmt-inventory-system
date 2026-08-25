<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import ChineseDatePicker from './ChineseDatePicker.vue'
import { deleteInvoice, loadFinanceRecords, loadInvoices, saveInvoice, type FinanceRecord, type InvoiceData } from '../api/workbench'

const props = defineProps<{ type: 'SALES' | 'PURCHASE'; businessId: number; businessNo: string }>()
const emit = defineEmits<{ close: []; saved: []; message: [text: string, kind?: 'success' | 'error'] }>()
const form = reactive({ invoiceNo: '', invoiceDate: '', taxInclusiveAmount: '', remark: '' })
const invoices = ref<InvoiceData[]>([])
const dialog = ref<HTMLElement>()
const loading = ref(true); const saving = ref(false); const error = ref('')
async function load() { loading.value = true; error.value = ''; try { const [invoiceData, records]: [InvoiceData[], FinanceRecord[]] = await Promise.all([loadInvoices(props.type, props.businessId), loadFinanceRecords(props.type, props.businessId)]); invoices.value = invoiceData; if (form.taxInclusiveAmount === '' && records[0]?.amount != null) form.taxInclusiveAmount = String(records[0].amount) } catch (cause) { error.value = cause instanceof Error ? cause.message : '读取发票失败' } finally { loading.value = false } }
function markClean() { dialog.value?.dispatchEvent(new CustomEvent('dialog-clean', { bubbles: true })) }
function resetForm() { form.invoiceNo = ''; form.invoiceDate = ''; form.taxInclusiveAmount = ''; form.remark = ''; markClean() }
function money(value: unknown) { return value == null || value === '' ? '—' : `¥ ${Number(value).toFixed(2)}` }
function date(value: unknown) { return String(value ?? '').slice(0, 10) || '—' }
function reviewLabel(status: unknown) { if (status === 'APPROVED') return '已通过'; if (status === 'REJECTED') return '已驳回'; return '待复核' }
async function save() { if (!form.invoiceNo.trim()) { error.value = '请填写发票号码'; return } saving.value = true; error.value = ''; try { await saveInvoice(props.type, props.businessId, { invoiceNo: form.invoiceNo.trim(), invoiceDate: form.invoiceDate || undefined, taxInclusiveAmount: form.taxInclusiveAmount === '' ? undefined : Number(form.taxInclusiveAmount), remark: form.remark.trim() || undefined }); emit('message', '发票信息已保存'); resetForm(); await load(); emit('saved') } catch (cause) { error.value = cause instanceof Error ? cause.message : '保存发票失败' } finally { saving.value = false } }
async function remove(invoice: InvoiceData) { if (!invoice.id || !window.confirm(`确认删除发票“${invoice.invoiceNo}”吗？`)) return; saving.value = true; error.value = ''; try { await deleteInvoice(props.type, props.businessId, invoice.id); emit('message', '发票信息已删除'); markClean(); await load(); emit('saved') } catch (cause) { error.value = cause instanceof Error ? cause.message : '删除发票失败' } finally { saving.value = false } }
onMounted(load)
</script>

<template>
  <div class="dialog-mask">
    <section ref="dialog" class="dialog-card invoice-dialog" role="dialog" aria-modal="true">
      <header class="invoice-dialog-header"><div><h2>维护发票</h2><p>发票独立维护，不改变收款、付款、已收或已付金额。</p><p>关联{{ props.type === 'SALES' ? '销售订单' : '采购单' }}：{{ props.businessNo || '—' }}</p></div><button class="dialog-close" :disabled="saving" @click="emit('close')">关闭</button></header>
      <div v-if="loading" class="empty">正在读取发票…</div>
      <div v-else class="invoice-content">
        <form class="invoice-form" @submit.prevent="save">
          <h3>新增发票</h3>
          <label class="invoice-field"><span>发票号码</span><input data-test="invoice-no" v-model="form.invoiceNo" maxlength="100"></label>
          <label class="invoice-field"><span>发票日期</span><ChineseDatePicker v-model="form.invoiceDate" placeholder="请选择发票日期" /></label>
          <label class="invoice-field"><span>发票含税金额</span><input data-test="invoice-amount" v-model="form.taxInclusiveAmount" type="number" step="0.01"></label>
          <label class="invoice-field invoice-remark-field"><span>发票备注</span><textarea v-model="form.remark" maxlength="500"></textarea></label>
          <p v-if="error" class="form-error">{{ error }}</p>
          <footer><button type="button" :disabled="saving" @click="resetForm">清空</button><button class="primary-action" :disabled="saving">{{ saving ? '正在保存…' : '保存发票' }}</button></footer>
        </form>
        <section class="invoice-history">
          <div class="invoice-history-head"><h3>历史发票</h3><span>{{ invoices.length }} 条记录</span></div>
          <div v-if="!invoices.length" class="empty history-empty">暂无发票记录</div>
          <div v-else class="invoice-table-wrap"><table>
            <thead><tr><th>原发票号码</th><th>财务确认号码</th><th>日期</th><th>原金额</th><th>确认金额</th><th>状态</th><th>复核备注</th><th>操作</th></tr></thead>
            <tbody><tr v-for="invoice in invoices" :key="invoice.id ?? invoice.invoiceNo"><td><strong>{{ invoice.invoiceNo }}</strong></td><td>{{ invoice.confirmedInvoiceNo || '—' }}</td><td>{{ date(invoice.invoiceDate) }}</td><td>{{ money(invoice.taxInclusiveAmount) }}</td><td>{{ money(invoice.confirmedAmount ?? invoice.taxInclusiveAmount) }}</td><td><span class="invoice-status" :class="String(invoice.reviewStatus || 'PENDING').toLowerCase()">{{ reviewLabel(invoice.reviewStatus) }}</span></td><td class="invoice-remark">{{ invoice.reviewRemark || invoice.remark || '—' }}</td><td><button :data-test="`delete-invoice-${invoice.id}`" class="danger" :disabled="saving || !invoice.id" @click="remove(invoice)">删除</button></td></tr></tbody>
          </table></div>
        </section>
      </div>
    </section>
  </div>
</template>

<style scoped>
.invoice-dialog{width:min(1040px,94vw);overflow:hidden}.invoice-dialog-header{align-items:flex-start}.invoice-dialog-header p{margin:5px 0 0;color:#667085;font-size:13px;line-height:1.45}
.invoice-content{display:grid;grid-template-columns:minmax(300px,360px) minmax(0,1fr);gap:20px;padding:20px 22px 22px;background:#f7f8fa}
.invoice-dialog h3{margin:0 0 14px;font-size:16px}.invoice-dialog .invoice-form,.invoice-history{border:1px solid #e1e5ea;border-radius:6px;background:#fff;box-shadow:0 1px 2px rgb(16 24 40 / 4%)}
.invoice-dialog .invoice-form{display:grid;grid-template-columns:1fr;align-content:start;gap:14px;padding:18px}.invoice-field{min-width:0}.invoice-dialog label span{display:block;margin-bottom:6px;color:#40444c;font-size:13px;font-weight:650}
.invoice-dialog input,.invoice-dialog textarea{width:100%;box-sizing:border-box;border:1px solid #cbd0d6;border-radius:5px;padding:9px 10px;background:#fff;font:inherit;line-height:1.4}.invoice-dialog input:focus,.invoice-dialog textarea:focus{border-color:#111;outline:2px solid rgb(0 0 0 / 9%);outline-offset:0}
.invoice-dialog textarea{min-height:104px;resize:vertical}.invoice-dialog footer{display:flex;justify-content:flex-end;gap:8px;margin-top:2px;padding-top:14px;border-top:1px solid #e5e7eb}.invoice-dialog .form-error{margin:-4px 0 0}
.invoice-history{min-width:0;overflow:hidden}.invoice-history-head{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:18px 18px 0}.invoice-history-head h3{margin:0}.invoice-history-head span{color:#667085;font-size:12px}.invoice-table-wrap{margin:14px 18px 18px;overflow:auto;border:1px solid #dfe3e8;border-radius:6px}.invoice-history table{width:100%;border-collapse:collapse;table-layout:auto}.invoice-history th,.invoice-history td{height:auto;padding:12px 14px;border-bottom:1px solid #edf0f2;text-align:left;vertical-align:top;white-space:nowrap}.invoice-history th{color:#667085;background:#f8fafc;font-size:12px;font-weight:700}.invoice-history tbody tr:last-child td{border-bottom:0}.invoice-history td{word-break:break-word}.invoice-history td strong{font-weight:700}.invoice-remark{max-width:220px;color:#48515c;white-space:normal}.invoice-status{display:inline-flex;align-items:center;min-height:24px;padding:2px 9px;border-radius:999px;font-size:12px;font-weight:700}.invoice-status.pending{color:#8a4b08;background:#fff4d6}.invoice-status.approved{color:#167247;background:#e8f7ef}.invoice-status.rejected{color:#b42318;background:#fff1f0}.history-empty{margin:14px 18px 18px;border:1px dashed #d0d5dd;border-radius:6px;background:#fff}
.danger{min-height:30px;padding:5px 10px;border:1px solid #e3a7a0;border-radius:4px;color:#b42318;background:#fff;cursor:pointer}.danger:hover{background:#fff1f0}.danger:disabled{opacity:.55;cursor:not-allowed}.empty{padding:34px;text-align:center;color:#667085}
@media(max-width:760px){.invoice-content{grid-template-columns:1fr;padding:16px}.invoice-dialog footer{justify-content:stretch}.invoice-dialog footer button{flex:1}.invoice-history{overflow-x:auto}}
</style>
