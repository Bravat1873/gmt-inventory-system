<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { updateShipmentQuantities } from '../api/workbench'
import ProductIdentityDisplay from './ProductIdentityDisplay.vue'

type OrderItem = {
  lineNo: number; productCode?: string; customerPartNumber?: string; productName?: string; model?: string; unit?: string
  quantity: number; shippedQuantity: number; availableQuantity?: number; lockedQuantity?: number
}
type ShipmentBatch = {
  shipmentDate?: string; shippedAt?: string; date?: string; createdAt?: string; totalQuantity?: number; quantity?: number
  deliveryAddress?: string; address?: string
}
type ShipmentOrder = {
  id: number; orderNo: string; items: OrderItem[]; deliveryAddress?: string; defaultShipmentAddress?: string
  shipments?: ShipmentBatch[]
}

const props = defineProps<{ order: ShipmentOrder }>()
const emit = defineEmits<{ close: []; saved: []; message: [text: string, kind?: 'success' | 'error'] }>()
const shipmentValues = ref<Record<number, number>>({})
const deliveryAddress = ref('')
const historyExpanded = ref(false)
const saving = ref(false)

function defaultAddress() { return props.order.defaultShipmentAddress || props.order.deliveryAddress || '' }
function reset() {
  shipmentValues.value = Object.fromEntries(props.order.items.map(item => [item.lineNo, 0]))
  deliveryAddress.value = defaultAddress()
  historyExpanded.value = false
}
watch(() => props.order, reset, { immediate: true, deep: true })

function current(item: OrderItem) { return Math.max(0, Number(shipmentValues.value[item.lineNo] ?? 0)) }
function reserved(item: OrderItem) { return Math.max(0, Number(item.lockedQuantity ?? 0)) }
function orderRemaining(item: OrderItem) { return Math.max(0, Number(item.quantity) - Number(item.shippedQuantity ?? 0)) }
function maximumCurrent(item: OrderItem) { return Math.min(orderRemaining(item), reserved(item)) }
function remainingAfter(item: OrderItem) { return Math.max(0, orderRemaining(item) - current(item)) }
function completed(item: OrderItem) { return remainingAfter(item) === 0 }
function historyDateTime(batch: ShipmentBatch) { return batch.shippedAt ?? batch.shipmentDate ?? batch.date ?? batch.createdAt }
function historyDate(batch: ShipmentBatch) {
  const value = historyDateTime(batch)
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—'
}
function historyQuantity(batch: ShipmentBatch) { return Number(batch.totalQuantity ?? batch.quantity ?? 0) }
function historyAddress(batch: ShipmentBatch) { return batch.deliveryAddress || batch.address || '—' }

const totalOrder = computed(() => props.order.items.reduce((sum, item) => sum + Number(item.quantity), 0))
const totalShipped = computed(() => props.order.items.reduce((sum, item) => sum + Number(item.shippedQuantity ?? 0), 0))
const totalCurrent = computed(() => props.order.items.reduce((sum, item) => sum + current(item), 0))

async function save() {
  for (const item of props.order.items) {
    if (!Number.isInteger(current(item)) || current(item) > maximumCurrent(item)) {
      emit('message', `${item.productName || item.customerPartNumber || '订单明细'}本次发货不能超过 ${maximumCurrent(item)}`, 'error')
      return
    }
  }
  if (totalCurrent.value <= 0) return
  if (!deliveryAddress.value.trim()) {
    emit('message', '请填写本批收货地址', 'error')
    return
  }
  saving.value = true
  try {
    await updateShipmentQuantities(props.order.id, deliveryAddress.value.trim(), props.order.items.map(item => ({
      lineNo: item.lineNo,
      shippedQuantity: Number(item.shippedQuantity ?? 0) + current(item)
    })))
    emit('message', '发货数量已保存，库存已按本次差额同步', 'success')
    emit('saved')
  } catch (error) {
    emit('message', error instanceof Error ? error.message : '保存发货数量失败', 'error')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="dialog-mask">
    <section class="dialog-card shipment-dialog" role="dialog" aria-modal="true" aria-labelledby="shipment-title">
      <header class="shipment-dialog-header">
        <div><h2 id="shipment-title">确认发货</h2><span>订单 {{ order.orderNo }}</span></div>
        <button type="button" aria-label="关闭发货弹窗" @click="emit('close')">关闭</button>
      </header>

      <div class="shipment-content">
        <section class="shipment-overview" aria-label="发货汇总">
          <div><span>订单数量</span><strong>{{ totalOrder }}</strong></div>
          <div><span>已发货数量</span><strong>{{ totalShipped }}</strong></div>
          <div><span>本次发货数量</span><strong data-test="shipment-total-current">{{ totalCurrent }}</strong></div>
        </section>

        <div class="shipment-address-bar">
          <label for="shipment-address">本批收货地址</label>
          <textarea id="shipment-address" v-model="deliveryAddress" data-test="shipment-address" rows="2" placeholder="请输入本批收货地址" />
          <button type="button" data-test="use-order-address" :disabled="!defaultAddress()" @click="deliveryAddress = defaultAddress()">使用订单地址</button>
        </div>

        <section v-if="order.shipments?.length" class="shipment-history-section">
          <button type="button" class="shipment-history-toggle" data-test="shipment-history-toggle" :aria-expanded="historyExpanded" aria-controls="shipment-history" @click="historyExpanded = !historyExpanded">
            历史发货（{{ order.shipments.length }}）<span aria-hidden="true">{{ historyExpanded ? '收起' : '展开' }}</span>
          </button>
          <div v-if="historyExpanded" id="shipment-history" data-test="shipment-history" class="shipment-history" aria-label="历史发货批次">
            <div v-for="(batch, index) in order.shipments" :key="`${historyDate(batch)}-${index}`" class="shipment-history-row">
              <time :datetime="historyDateTime(batch)">{{ historyDate(batch) }}</time>
              <strong :aria-label="`本批发货数量 ${historyQuantity(batch)}`">{{ historyQuantity(batch) }}</strong>
              <span :aria-label="`本批收货地址 ${historyAddress(batch)}`">{{ historyAddress(batch) }}</span>
            </div>
          </div>
        </section>

        <div class="shipment-lines">
          <article v-for="item in order.items" :key="item.lineNo" class="shipment-line-card" data-test="shipment-line">
            <div class="shipment-line-heading">
              <ProductIdentityDisplay compact :product-code="item.productCode" :customer-part-number="item.customerPartNumber" :model="item.model" />
              <div class="shipment-line-state">
                <span data-test="available-quantity">未锁定库存数量 {{ Number(item.availableQuantity ?? 0) }}{{ item.unit ? ` ${item.unit}` : '' }}</span>
                <span data-test="shipment-status-dot" class="shipment-status-dot" :class="completed(item) ? 'complete' : 'incomplete'">{{ completed(item) ? '已完成' : '待发货' }}</span>
              </div>
            </div>
            <div class="shipment-line-metrics">
              <span>订单数量<strong>{{ item.quantity }}</strong></span>
              <span>已发货数量<strong>{{ item.shippedQuantity }}</strong></span>
              <span data-test="reserved-quantity">本单锁定数量<strong>{{ reserved(item) }}</strong></span>
              <label>本次发货数量<input v-model.number="shipmentValues[item.lineNo]" type="number" min="0" :max="maximumCurrent(item)" step="1" aria-label="本次发货数量"></label>
              <span data-test="remaining-quantity">发货后未发货数量<strong>{{ remainingAfter(item) }}</strong></span>
            </div>
          </article>
        </div>
      </div>

      <footer class="shipment-footer">
        <div>本次发货合计 <strong>{{ totalCurrent }}</strong></div>
        <div><button class="secondary-action" type="button" @click="emit('close')">取消</button><button class="primary-action" type="button" :disabled="saving || totalCurrent === 0" @click="save">{{ saving ? '发货中…' : '确认发货' }}</button></div>
      </footer>
    </section>
  </div>
</template>
