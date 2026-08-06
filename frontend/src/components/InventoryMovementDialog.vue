<script setup lang="ts">
import type { InventoryMovement } from '../api/workbench'

defineProps<{ movements: InventoryMovement[] }>()
const emit = defineEmits<{ close: [] }>()
</script>

<template>
  <div class="dialog-mask" @click.self="emit('close')">
    <section class="dialog-card inventory-movement-dialog" role="dialog" aria-modal="true" aria-labelledby="inventory-movement-title">
      <header>
        <div>
          <h2 id="inventory-movement-title">入／出库明细</h2>
          <p>按库存 Excel 的日期列记录</p>
        </div>
        <button type="button" data-test="close-movements" @click="emit('close')">关闭</button>
      </header>
      <div class="movement-table-wrap">
        <table class="movement-table">
          <thead><tr><th>日期</th><th>类型</th><th>数量</th><th>Excel 列</th></tr></thead>
          <tbody v-if="movements.length">
            <tr v-for="movement in movements" :key="`${movement.date}-${movement.sourceColumn}`">
              <td>{{ movement.date }}</td><td><span class="movement-direction" :class="movement.direction === '入库' ? 'inbound' : 'outbound'">{{ movement.direction }}</span></td><td>{{ movement.quantity }}</td><td>{{ movement.sourceColumn }}</td>
            </tr>
          </tbody>
          <tbody v-else><tr><td colspan="4" class="movement-empty">暂无入／出库明细</td></tr></tbody>
        </table>
      </div>
    </section>
  </div>
</template>
