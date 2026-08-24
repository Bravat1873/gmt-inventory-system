<script setup lang="ts">
type Shipment = { id: number; shipmentNo?: string; shippedAt?: string; totalQuantity?: number }
defineProps<{ orderNo: string; shipments: Shipment[] }>()
const emit = defineEmits<{ close: []; export: [type: 'sales' | 'shipment', shipmentId?: number] }>()
function date(value?: string) { return value ? value.replace('T', ' ').slice(0, 16) : '未记录日期' }
</script>

<template>
  <div class="dialog-mask">
    <section class="dialog-card export-dialog" role="dialog" aria-modal="true" aria-labelledby="document-export-title">
      <header><div><h2 id="document-export-title">导出单据</h2><span>订单 {{ orderNo }}</span></div><button type="button" @click="emit('close')">关闭</button></header>
      <div class="export-options">
        <button type="button" class="export-option" @click="emit('export', 'sales')"><strong>销售订单</strong><span>导出完整销售订单单据</span></button>
        <p v-if="!shipments.length" class="empty-state">当前订单尚无发货记录，发货后可导出对应批次的销售出库单。</p>
        <button v-for="shipment in shipments" :key="shipment.id" type="button" class="export-option" @click="emit('export', 'shipment', shipment.id)">
          <strong>销售出库单</strong><span>{{ shipment.shipmentNo || '发货批次' }} · {{ date(shipment.shippedAt) }} · {{ shipment.totalQuantity ?? 0 }} 件</span>
        </button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.export-dialog{width:min(560px,calc(100vw - 32px));}.export-dialog header{display:flex;justify-content:space-between;align-items:flex-start;padding:20px 24px;border-bottom:1px solid #e5e7eb}.export-dialog h2{margin:0;font-size:20px}.export-dialog header span{display:block;margin-top:6px;color:#64748b}.export-options{display:grid;gap:10px;padding:20px 24px 24px}.export-option{display:grid;gap:6px;text-align:left;padding:16px;border:1px solid #cbd5e1;background:#fff;border-radius:4px}.export-option:hover{border-color:#111827;background:#f8fafc}.export-option span,.empty-state{color:#64748b;font-size:14px;line-height:1.5}.empty-state{margin:0;padding:8px 0}
</style>
