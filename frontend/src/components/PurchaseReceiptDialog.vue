<script setup lang="ts">
import { reactive, ref } from 'vue'
import { receivePurchase, type PurchaseDetail } from '../api/workbench'

const props = defineProps<{ purchase: PurchaseDetail }>()
const emit = defineEmits<{ close: []; saved: []; message: [text: string, kind?: 'success' | 'error'] }>()
const quantities = reactive<Record<number, number>>(Object.fromEntries(props.purchase.items.map(item => [item.id, 0])))
const error = ref('')
const saving = ref(false)

function validationMessage() {
  let hasPositive = false
  for (const item of props.purchase.items) {
    const quantity = Number(quantities[item.id] ?? 0)
    if (!Number.isInteger(quantity) || quantity < 0) return '本次实收数量必须是非负整数'
    if (quantity > item.remainingQuantity) return '本次实收数量不能超过剩余数量'
    if (quantity > 0) hasPositive = true
  }
  return hasPositive ? '' : '本次至少填写一项实收数量'
}

async function save() {
  error.value = validationMessage()
  if (error.value) return
  saving.value = true
  try {
    await receivePurchase(props.purchase.id, props.purchase.items.map(item => ({
      purchaseOrderItemId: item.id,
      receivedQuantity: Number(quantities[item.id] ?? 0)
    })))
    emit('message', '收货登记成功')
    emit('saved')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '收货登记失败'
    emit('message', error.value, 'error')
  } finally { saving.value = false }
}
</script>

<template>
  <div class="dialog-mask" @click.self="emit('close')">
    <section class="dialog-card purchase-receipt-dialog" role="dialog" aria-labelledby="purchase-receipt-title">
      <header><div><h2 id="purchase-receipt-title">登记采购收货</h2><p>{{ purchase.purchaseNo }} · {{ purchase.supplierName }}</p></div><button type="button" :disabled="saving" @click="emit('close')">关闭</button></header>
      <form @submit.prevent="save">
        <div class="order-lines-scroll"><table><thead><tr><th>物料</th><th>采购数量</th><th>累计已收</th><th>剩余数量</th><th>本次实收</th></tr></thead><tbody>
          <tr v-for="item in purchase.items" :key="item.id"><td>{{ item.skuCode }} {{ item.productName }}</td><td>{{ item.quantity }}</td><td>{{ item.receivedQuantity }}</td><td>{{ item.remainingQuantity }}</td><td><input data-test="received-now" v-model.number="quantities[item.id]" type="number" min="0" :max="item.remainingQuantity" step="1" :disabled="saving || item.remainingQuantity <= 0"></td></tr>
        </tbody></table></div>
        <p v-if="error" class="dialog-error" role="alert">{{ error }}</p>
        <footer><button type="button" class="secondary-action" :disabled="saving" @click="emit('close')">取消</button><button class="primary-action" :disabled="saving">确认收货</button></footer>
      </form>
    </section>
  </div>
</template>
