<script setup lang="ts">
import { reactive, ref } from 'vue'
import { updateOrderAllocations, type OrderAllocation } from '../api/workbench'

const props = defineProps<{ allocation: OrderAllocation }>()
const emit = defineEmits<{ close: []; saved: []; message: [text: string, kind?: 'success' | 'error'] }>()
const targets = reactive<Record<number, number>>(Object.fromEntries(props.allocation.items.map(item => [item.lineNo, item.lockedQuantity])))
const saving = ref(false)
const error = ref('')

async function save() {
  for (const item of props.allocation.items) {
    const value = Number(targets[item.lineNo])
    if (!Number.isInteger(value) || value < 0 || value > item.quantity - item.shippedQuantity) {
      error.value = `${item.skuCode} 的分配数量必须是 0 到 ${item.quantity - item.shippedQuantity} 的整数`
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
      <p>可在自动锁定后调整每个产品为客户保留的数量；减少的数量会立即释放回可用库存。</p>
      <div class="table-wrap">
        <table>
          <thead><tr><th>产品</th><th>订单数</th><th>已发货</th><th>实际库存</th><th>当前锁定</th><th>可用库存</th><th>分配给客户</th></tr></thead>
          <tbody><tr v-for="item in allocation.items" :key="item.lineNo">
            <td>{{ item.skuCode }} · {{ item.productName }}</td><td>{{ item.quantity }}</td><td>{{ item.shippedQuantity }}</td>
            <td>{{ item.actualQuantity }}</td><td>{{ item.lockedQuantity }}</td><td>{{ item.availableQuantity }}</td>
            <td><input v-model.number="targets[item.lineNo]" :data-test="`allocation-${item.lineNo}`" type="number" min="0" :max="item.quantity-item.shippedQuantity" step="1" :disabled="saving"></td>
          </tr></tbody>
        </table>
      </div>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <footer><button type="button" class="secondary-action" :disabled="saving" @click="emit('close')">取消</button><button class="primary-action" :disabled="saving" @click="save">{{ saving ? '正在保存…' : '确认分配' }}</button></footer>
    </section>
  </div>
</template>
