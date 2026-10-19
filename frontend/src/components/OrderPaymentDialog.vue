<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getOrder, postAction } from '../api/workbench'
import { loadCustomerFundOverview } from '../api/customer-funds'
const props = defineProps<{ order: Record<string, unknown> }>()
const emit = defineEmits<{ close: []; saved: []; message: [text: string, kind?: 'success' | 'error'] }>()
const loading = ref(true), saving = ref(false), error = ref(''), balance = ref(0), outstanding = ref(0)
const paid = ref(false), amount = ref<number | string>(0), received = ref(0)
const money = (value: number) => value.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 })
const insufficient = computed(() => balance.value < Number(amount.value))
const invalidAmount = computed(() => !Number.isFinite(Number(amount.value)) || Number(amount.value) <= 0 || Number(amount.value) > outstanding.value || Math.abs(Number(amount.value) * 10000 - Math.round(Number(amount.value) * 10000)) > 0.000001)
onMounted(async () => {
  try {
    const order = await getOrder(Number(props.order.id))
    outstanding.value = Math.max(0, Math.round((Number(order.receivableAmount ?? order.totalAmount ?? 0) - Number(order.receivedAmount ?? 0)) * 10000) / 10000)
    received.value = Number(order.receivedAmount ?? 0)
    amount.value = outstanding.value
    balance.value = (await loadCustomerFundOverview(Number(order.customerId))).balance
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '读取支付信息失败，请重新打开窗口' }
  finally { loading.value = false }
})
async function save() {
  if (!paid.value) { emit('close'); return }
  if (loading.value || saving.value || error.value || insufficient.value || invalidAmount.value || outstanding.value <= 0) return
  saving.value = true
  try {
    await postAction(`/api/finance/orders/${Number(props.order.id)}/pay`, { expectedAmount: outstanding.value, amount: Number(amount.value) })
    emit('message', Number(amount.value) < outstanding.value ? '本次付款成功，订单仍有未付金额' : '订单已付清，客户可用余额已扣减'); emit('saved')
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '支付失败，请重新打开窗口核对' }
  finally { saving.value = false }
}
</script>
<template>
  <div class="dialog-mask"><section class="dialog-card order-payment-dialog" role="dialog" aria-modal="true" aria-labelledby="order-payment-title">
    <header><h2 id="order-payment-title">订单支付</h2><button type="button" :disabled="saving" @click="emit('close')">关闭</button></header>
    <form @submit.prevent="save">
      <p>{{ order.orderNo }} · {{ order.customerName }}</p>
      <p v-if="loading">正在读取订单金额与客户余额…</p>
      <template v-else>
        <dl><dt>客户可用余额</dt><dd data-test="payment-balance">¥ {{ money(balance) }}</dd><dt>已付金额</dt><dd>¥ {{ money(received) }}</dd><dt>未付金额</dt><dd>¥ {{ money(outstanding) }}</dd></dl>
        <label><span>本次支付金额</span><input data-test="payment-amount" v-model="amount" type="number" min="0.0001" :max="outstanding" step="0.0001" :disabled="saving || !!error || outstanding <= 0" /></label>
        <label><span>支付选择</span><select data-test="order-payment-choice" v-model="paid" :disabled="saving || !!error || outstanding <= 0"><option :value="false">暂不支付</option><option :value="true">确认支付</option></select></label>
        <p class="field-hint">默认支付全部未付金额，也可修改为部分金额。确认后扣减客户可用余额，已付、未付金额同步更新。</p>
        <p v-if="invalidAmount && outstanding > 0" class="form-error" role="alert">请输入大于 0 且不超过未付金额的数值，最多四位小数。</p>
        <p v-if="outstanding <= 0" class="field-hint">该订单已付清，无需重复支付。</p>
        <p v-else-if="insufficient" class="form-error" role="alert">客户余额不足，请在资金管理确认到账后再支付。</p>
      </template>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <footer><button class="secondary-action" type="button" :disabled="saving" @click="emit('close')">取消</button><button class="primary-action" data-test="submit-order-payment" :disabled="loading || saving || (paid && (insufficient || invalidAmount || !!error || outstanding <= 0))">{{ saving ? '正在支付…' : paid ? '确认支付并扣款' : '保持未支付' }}</button></footer>
    </form>
  </section></div>
</template>
<style scoped>
.order-payment-dialog{width:min(560px,94vw)}form{padding:20px 22px}dl{display:grid;grid-template-columns:1fr auto;gap:14px;margin:22px 0}dt{color:#64748b}dd{margin:0;font-variant-numeric:tabular-nums}label{margin-top:14px;display:flex;align-items:center;gap:16px}input,select{min-width:0;padding:9px;border:1px solid #cbd5e1;border-radius:4px;flex:1}footer{display:flex;justify-content:flex-end;gap:12px;margin-top:24px}.field-hint{line-height:1.6}
</style>
