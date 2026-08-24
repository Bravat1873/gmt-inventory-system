<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { postAction } from '../api/workbench'
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

function amount(value: unknown) { const result = Number(value ?? 0); return Number.isFinite(result) ? result : 0 }
function money(value: number) { return value.toFixed(2) }
const lines = computed(() => Array.isArray(props.order.items) ? props.order.items as OrderLine[] : [])
const calculatedReceivable = computed(() => lines.value.reduce((total, line) => {
  return total + amount(line.quantity) * amount(line.salePrice)
}, 0))
const receivableAmount = computed(() => props.order.receivableAmount == null ? calculatedReceivable.value : amount(props.order.receivableAmount))
const receivedAmount = computed(() => amount(props.order.receivedAmount))
const outstandingAmount = computed(() => Math.max(0, receivableAmount.value - receivedAmount.value))
const afterReceiptAmount = computed(() => Math.max(0, outstandingAmount.value - amount(form.amount)))
watch(outstandingAmount, value => { form.amount = Math.min(value, customerBalance.value || value) }, { immediate: true })
onMounted(async()=>{if(Number(props.order.customerId)>0){try{customerBalance.value=(await loadCustomerFundOverview(Number(props.order.customerId))).balance;balanceLoaded.value=true;form.amount=Math.min(outstandingAmount.value,customerBalance.value)}catch{}}})

function close() { if (!saving.value) emit('close') }
async function save() {
  const value = amount(form.amount)
  if (!form.receivedAt) { error.value = '请选择收款日期'; return }
  if (!form.paymentMethod.trim()) { error.value = '请填写收款方式'; return }
  if (value <= 0) { error.value = '本次收款金额必须大于 0'; return }
  if (balanceLoaded.value && value > customerBalance.value) { error.value = '本次收款金额不能超过客户可用余额'; return }
  if (value > outstandingAmount.value) { error.value = '本次收款金额不能超过未收金额'; return }
  saving.value = true; error.value = ''
  try {
    await postAction(`/api/finance/orders/${Number(props.order.id)}/receipt`, { amount: value, paymentMethod: form.paymentMethod.trim(), receivedAt: form.receivedAt })
    emit('message', '收款登记成功'); emit('saved')
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '收款登记失败' } finally { saving.value = false }
}
</script>

<template>
  <div class="dialog-mask">
    <section class="dialog-card receipt-dialog" role="dialog" aria-modal="true" aria-labelledby="receipt-title">
      <header><h2 id="receipt-title">使用客户余额登记收款</h2><button type="button" :disabled="saving" @click="close">关闭</button></header>
      <form novalidate @submit.prevent="save">
        <div class="receipt-summary"><div><span>客户可用余额</span><strong data-test="customer-balance">¥ {{ money(customerBalance) }}</strong><small>登记后从余额扣减</small></div>
          <div><span>订单总金额</span><strong data-test="receivable-amount">¥ {{ money(receivableAmount) }}</strong><small>订单数量 × 含税单价</small></div>
          <div><span>已收金额</span><strong data-test="received-amount">¥ {{ money(receivedAmount) }}</strong><small>历史登记收款合计</small></div>
          <div><span>未收金额</span><strong>¥ {{ money(outstandingAmount) }}</strong><small>本次登记前</small></div>
        </div>
        <div class="form-grid">
          <label><span>订单编号</span><input :value="String(order.orderNo ?? '')" disabled></label>
          <label><span>本次收款金额</span><input data-test="receipt-amount" v-model.number="form.amount" type="number" min="0.01" :max="outstandingAmount" step="0.01" :disabled="saving || outstandingAmount <= 0"></label>
          <label><span>收款方式</span><input v-model.trim="form.paymentMethod" :disabled="saving"></label><label data-test="receipt-date"><span>收款日期</span><ChineseDatePicker v-model="form.receivedAt" :disabled="saving" placeholder="请选择收款日期" /></label>
          <label><span>登记后未收金额</span><input :value="money(afterReceiptAmount)" disabled></label>
        </div>
        <p v-if="outstandingAmount <= 0" class="receipt-complete">该订单目前没有可登记的未收金额。</p>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <footer><button type="button" class="secondary-action" :disabled="saving" @click="close">取消操作</button><button class="primary-action" :disabled="saving || outstandingAmount <= 0">{{ saving ? '正在保存…' : '确认登记' }}</button></footer>
      </form>
    </section>
  </div>
</template>
