<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { updateShipmentQuantities } from '../api/workbench'

type OrderItem = {
  lineNo: number; skuCode?: string; productName?: string; model?: string; unit?: string
  quantity: number; shippedQuantity: number; availableQuantity?: number; lockedQuantity?: number
}
const props = defineProps<{ order: { id: number; orderNo: string; items: OrderItem[] } }>()
const emit = defineEmits<{ close: []; saved: []; message: [text: string, kind?: 'success' | 'error'] }>()
const values = ref<Record<number, number>>({})
const saving = ref(false)
function reset() { values.value = Object.fromEntries(props.order.items.map(item => [item.lineNo, Number(item.shippedQuantity ?? 0)])) }
watch(() => props.order, reset, { immediate: true, deep: true })
function value(item: OrderItem) { return Math.max(0, Number(values.value[item.lineNo] ?? 0)) }
function remaining(item: OrderItem) { return Math.max(0, Number(item.quantity) - value(item)) }
function completed(item: OrderItem) { return remaining(item) === 0 }
function reserved(item: OrderItem) { return Math.max(0, Number(item.lockedQuantity ?? 0)) }
function maximumShipped(item: OrderItem) { return Math.min(Number(item.quantity), Number(item.shippedQuantity ?? 0) + reserved(item)) }
async function save() {
  for (const item of props.order.items) {
    if (!Number.isInteger(value(item)) || value(item) > maximumShipped(item)) {
      emit('message', `${item.productName || item.skuCode || '订单明细'}本单已备货 ${reserved(item)}，已发货数量不能超过 ${maximumShipped(item)}`, 'error')
      return
    }
  }
  saving.value = true
  try {
    await updateShipmentQuantities(props.order.id, props.order.items.map(item => ({ lineNo: item.lineNo, shippedQuantity: value(item) })))
    emit('message', '发货数量已保存，库存已按本次差额同步', 'success')
    emit('saved')
  } catch (error) { emit('message', error instanceof Error ? error.message : '保存发货数量失败', 'error') }
  finally { saving.value = false }
}
const totalRemaining = computed(() => props.order.items.reduce((sum, item) => sum + remaining(item), 0))
</script>

<template>
  <div class="dialog-mask" @click.self="emit('close')">
    <section class="dialog-card shipment-dialog" role="dialog" aria-modal="true" aria-labelledby="shipment-title">
      <header><div><h2 id="shipment-title">调整发货数量</h2><p>订单 {{ order.orderNo }} · 已发货数量为累计值，保存时仅处理本次增减的库存。</p></div><button type="button" @click="emit('close')">关闭</button></header>
      <div class="shipment-content">
        <div class="shipment-summary">未发货合计 <strong>{{ totalRemaining }}</strong></div>
        <div class="shipment-table-wrap"><table class="shipment-table"><thead><tr><th>物料</th><th>可用库存</th><th>本单已备货</th><th>订单数量</th><th>已发货数量</th><th>剩余数量</th></tr></thead><tbody>
          <tr v-for="item in order.items" :key="item.lineNo">
            <td><strong>{{ item.productName || item.skuCode || '未命名物料' }}</strong><small>{{ item.skuCode }} {{ item.model ? `· ${item.model}` : '' }}</small></td>
            <td data-test="available-quantity">{{ Number(item.availableQuantity ?? 0) }}{{ item.unit ? ` ${item.unit}` : '' }}</td>
            <td data-test="reserved-quantity">{{ reserved(item) }}{{ item.unit ? ` ${item.unit}` : '' }}</td>
            <td>{{ item.quantity }}</td>
            <td><input v-model.number="values[item.lineNo]" type="number" min="0" :max="maximumShipped(item)" step="1" aria-label="已发货数量"></td>
            <td data-test="remaining-quantity"><span data-test="shipment-status-dot" class="shipment-status-dot" :class="completed(item) ? 'complete' : 'incomplete'"></span>{{ remaining(item) }} <span :class="completed(item) ? 'complete-text' : 'incomplete-text'">{{ completed(item) ? '已完成' : '未发完' }}</span></td>
          </tr>
        </tbody></table></div>
      </div>
      <footer><button class="secondary-action" type="button" @click="emit('close')">取消操作</button><button class="primary-action" type="button" :disabled="saving" @click="save">{{ saving ? '保存中…' : '确认保存' }}</button></footer>
    </section>
  </div>
</template>
