<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getOrder, postAction } from '../api/workbench'
import { loadCustomerFundOverview } from '../api/customer-funds'
const props = defineProps<{ order: Record<string, unknown> }>()
const emit = defineEmits<{ close: []; saved: []; message: [text: string, kind?: 'success' | 'error'] }>()
const loading = ref(true), saving = ref(false), error = ref(''), balance = ref(0), outstanding = ref(0)
const paid = ref(false)
const money = (value: number) => value.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 })
const insufficient = computed(() => balance.value < outstanding.value)
onMounted(async () => {
  try {
    const order = await getOrder(Number(props.order.id))
    outstanding.value = Math.max(0, Number(order.receivableAmount ?? order.totalAmount ?? 0) - Number(order.receivedAmount ?? 0))
    balance.value = (await loadCustomerFundOverview(Number(order.customerId))).balance
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '读取支付信息失败，请重新打开窗口' }
  finally { loading.value = false }
})
async function save() {
  if (!paid.value) { emit('close'); return }
  if (loading.value || saving.value || error.value || insufficient.value || outstanding.value <= 0) return
  saving.value = true
  try {
    await postAction(`/api/finance/orders/${Number(props.order.id)}/pay`, { expectedAmount: outstanding.value })
    emit('message', '订单已支付，客户可用余额已扣减'); emit('saved')
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
        <dl><dt>客户可用余额</dt><dd data-test="payment-balance">¥ {{ money(balance) }}</dd><dt>本次支付金额</dt><dd data-test="payment-amount">¥ {{ money(outstanding) }}</dd></dl>
        <label><span>支付选择</span><select data-test="order-payment-choice" v-model="paid" :disabled="saving || !!error || outstanding <= 0"><option :value="false">暂不支付</option><option :value="true">确认支付</option></select></label>
        <p class="field-hint">确认支付后从客户可用余额扣除订单剩余未付金额，财务汇总同步更新。</p>
        <p v-if="outstanding <= 0" class="field-hint">该订单已付清，无需重复支付。</p>
        <p v-else-if="insufficient" class="form-error" role="alert">客户余额不足，请在资金管理确认到账后再支付。</p>
      </template>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <footer><button class="secondary-action" type="button" :disabled="saving" @click="emit('close')">取消</button><button class="primary-action" data-test="submit-order-payment" :disabled="loading || saving || (paid && (insufficient || !!error || outstanding <= 0))">{{ saving ? '正在支付…' : paid ? '确认支付并扣款' : '保持未支付' }}</button></footer>
    </form>
  </section></div>
</template>
<style scoped>
.order-payment-dialog{width:min(560px,94vw)}form{padding:20px 22px}dl{display:grid;grid-template-columns:1fr auto;gap:14px;margin:22px 0}dt{color:#64748b}dd{margin:0;font-variant-numeric:tabular-nums}label{display:flex;align-items:center;gap:16px}select{padding:9px;border:1px solid #cbd5e1;border-radius:4px;flex:1}footer{display:flex;justify-content:flex-end;gap:12px;margin-top:24px}.field-hint{line-height:1.6}
</style>
