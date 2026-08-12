<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { postAction } from '../api/workbench'
import ChineseDatePicker from './ChineseDatePicker.vue'

const props = defineProps<{ purchase: Record<string, unknown> }>()
const emit = defineEmits<{ close: []; saved: []; message: [text: string, kind?: 'success' | 'error'] }>()
const form = reactive({ amount: '', paymentMethod: '银行转账', invoiceNo: '', invoiceDate: '', paymentRemark: '' })
const saving = ref(false)
const error = ref('')

function numeric(value: unknown) { const result = Number(value ?? 0); return Number.isFinite(result) ? result : 0 }
function money(value: number) { return value.toFixed(2) }
const purchaseNo = computed(() => String(props.purchase.purchaseNo ?? ''))
const totalAmount = computed(() => numeric(props.purchase.totalAmount))
const settledAmount = computed(() => numeric(props.purchase.settledAmount))
const outstandingAmount = computed(() => numeric(props.purchase.outstandingAmount || Math.max(0, totalAmount.value - settledAmount.value)))
watch(outstandingAmount, value => { form.amount = value > 0 ? String(value) : '' }, { immediate: true })

function validate() {
  if (!Number.isInteger(Number(props.purchase.id)) || Number(props.purchase.id) <= 0) return '未找到可登记付款的采购单'
  if (!Number.isFinite(Number(form.amount)) || Number(form.amount) <= 0) return '付款金额必须为大于 0 的有效数字'
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
      invoiceNo: form.invoiceNo.trim() || undefined, invoiceDate: form.invoiceDate || undefined,
      paymentRemark: form.paymentRemark.trim() || undefined
    })
    emit('message', '付款登记成功')
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
    <section class="dialog-card" role="dialog" aria-modal="true" aria-labelledby="payment-title">
      <header><h2 id="payment-title">登记付款</h2><button type="button" :disabled="saving" @click="requestClose">关闭</button></header>
      <form novalidate @submit.prevent="save">
        <div class="payment-summary">
          <div><span>采购总金额</span><strong>¥ {{ money(totalAmount) }}</strong></div>
          <div><span>已付金额</span><strong>¥ {{ money(settledAmount) }}</strong></div>
          <div><span>未付金额</span><strong class="payment-outstanding">¥ {{ money(outstandingAmount) }}</strong></div>
        </div>
        <div class="form-grid">
          <label><span>采购单号</span><input data-test="payment-purchase-no" :value="purchaseNo" disabled></label>
          <label><span>本次付款金额</span><input data-test="payment-amount" v-model="form.amount" type="number" min="0.01" :max="outstandingAmount" step="0.01" :aria-invalid="Boolean(error)" :disabled="saving || outstandingAmount <= 0"></label>
          <label><span>付款方式</span><input v-model="form.paymentMethod" :aria-invalid="Boolean(error)" :disabled="saving"></label>
          <label><span>发票号码</span><input v-model="form.invoiceNo" :disabled="saving"></label>
          <label><span>发票日期</span><ChineseDatePicker v-model="form.invoiceDate" :disabled="saving" placeholder="请选择发票日期" /></label>
          <label><span>付款备注</span><textarea v-model="form.paymentRemark" maxlength="500" :disabled="saving"></textarea></label>
        </div>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <p v-if="outstandingAmount <= 0" class="receipt-complete">该采购单已结清，无需重复登记付款。</p>
        <footer><button type="button" class="secondary-action" :disabled="saving" @click="requestClose">取消操作</button><button class="primary-action" :disabled="saving || outstandingAmount <= 0">{{ saving ? '正在保存…' : '确认保存' }}</button></footer>
      </form>
    </section>
  </div>
</template>
