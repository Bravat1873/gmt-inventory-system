<script setup lang="ts">
import { reactive, ref } from 'vue'
import { updateOrderAllocations, type OrderAllocation } from '../api/workbench'
import ProductIdentityDisplay from './ProductIdentityDisplay.vue'

const props = defineProps<{ allocation: OrderAllocation }>()
const emit = defineEmits<{ close: []; saved: []; message: [text: string, kind?: 'success' | 'error'] }>()
const targets = reactive<Record<number, number>>(Object.fromEntries(props.allocation.items.map(item => [item.lineNo, item.lockedQuantity])))
const saving = ref(false)
const error = ref('')

async function save() {
  for (const item of props.allocation.items) {
    const value = Number(targets[item.lineNo])
    if (!Number.isInteger(value) || value < 0 || value > item.quantity - item.shippedQuantity) {
      error.value = `${item.customerPartNumber} 的分配数量必须是 0 到 ${item.quantity - item.shippedQuantity} 的整数`
      return
    }
  }
  saving.value = true; error.value = ''
  try {
    await updateOrderAllocations(props.allocation.id, props.allocation.version,
      props.allocation.items.map(item => ({ lineNo: item.lineNo, lockedQuantity: Number(targets[item.lineNo]) })))
    emit('message', '库存分配已更新'); emit('saved')
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '库存分配失败' }
  finally { saving.value = false }
}
</script>

<template>
  <div class="dialog-mask">
    <section class="dialog-card allocation-dialog" role="dialog" aria-modal="true">
      <header><h2>手动分配库存</h2><button type="button" :disabled="saving" @click="emit('close')">关闭</button></header>
      <p>可在自动锁定后调整每个产品为客户保留的数量；减少的数量会立即释放回未锁定库存。</p>
      <div class="allocation-product-panels">
        <article v-for="item in allocation.items" :key="item.lineNo" class="allocation-product-panel">
          <header class="allocation-product-panel-header">
            <div class="allocation-product-identity"><ProductIdentityDisplay compact :product-code="item.productCode" :customer-part-number="item.customerPartNumber" :model="item.model" /></div>
            <span class="allocation-line-number">明细 {{ item.lineNo }}</span>
          </header>
          <div class="allocation-metrics">
            <div><span>订单数量</span><strong>{{ item.quantity }}</strong></div>
            <div><span>已发货数量</span><strong>{{ item.shippedQuantity }}</strong></div>
            <div><span>实际库存数量</span><strong>{{ item.actualQuantity }}</strong></div>
            <div><span>本单锁定数量</span><strong>{{ item.lockedQuantity }}</strong></div>
            <div><span>未锁定库存数量</span><strong>{{ item.availableQuantity }}</strong></div>
          </div>
          <label class="allocation-target-field"><span>本次分配给客户</span><input v-model.number="targets[item.lineNo]" :data-test="`allocation-${item.lineNo}`" type="number" min="0" :max="item.quantity-item.shippedQuantity" step="1" :disabled="saving"><small>可分配 0 至 {{ item.quantity - item.shippedQuantity }}</small></label>
        </article>
      </div>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <footer><button type="button" class="secondary-action" :disabled="saving" @click="emit('close')">取消</button><button class="primary-action" :disabled="saving" @click="save">{{ saving ? '正在保存…' : '确认分配' }}</button></footer>
    </section>
  </div>
</template>

