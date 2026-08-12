<script setup lang="ts">
import type { BusinessTrace } from '../api/workbench'
import OverflowText from './OverflowText.vue'

const props = defineProps<{ trace: BusinessTrace }>()
const emit = defineEmits<{ close: [] }>()

const labels: Record<string, string> = {
  skuCode: '物料编号', productName: '物料名称', model: '型号', configuration: '规格型号', unit: '单位',
  quantity: '数量', shippedQuantity: '已发货数量', remainingQuantity: '剩余数量', availableQuantity: '可用库存', lockedQuantity: '锁定数量', uncoveredQuantity: '缺货数量',
  salePrice: '含税单价', receivedQuantity: '已入库数量', purchasePrice: '采购单价'
}
const detailKeys = () => Object.keys(props.trace.details[0] ?? {}).filter(key => labels[key])
function value(value: unknown) { return value == null || value === '' ? '—' : String(value) }
function time(value: unknown) {
  const matched = String(value ?? '').match(/^(\d{4}-\d{2}-\d{2})[T\s]?(\d{2}:\d{2}:\d{2})?/) 
  return matched ? `${matched[1]}${matched[2] ? ` ${matched[2]}` : ''}` : '—'
}
const statuses: Record<string, string> = { DRAFT: '待确认建议', PENDING_CUSTOMER_PAYMENT: '待确认收款', PENDING_SALES_INVOICE: '待开销售发票', WAITING_STOCK: '等待齐货', READY_TO_SHIP: '等待发货', SHIPPED: '已发货', PENDING_SUPPLIER_PAYMENT: '待登记付款', EXECUTING: '采购执行中', RECEIVED: '已入库' }
function header(key: string) { const raw = props.trace.header[key]; return key === 'status' ? (statuses[String(raw)] ?? value(raw)) : value(raw) }
</script>

<template>
  <div class="dialog-mask">
    <section class="dialog-card business-trace-dialog" role="dialog" aria-modal="true" aria-labelledby="business-trace-title">
      <header>
        <div><h2 id="business-trace-title">{{ trace.title }}</h2><p>订单、采购、财务与库存流转的完整记录</p></div>
        <button type="button" @click="emit('close')">关闭</button>
      </header>
      <div class="trace-content">
        <section class="trace-overview">
          <div><span>{{ trace.type === 'order' ? '订单编号' : '采购单号' }}</span><strong>{{ header(trace.type === 'order' ? 'orderNo' : 'purchaseNo') }}</strong></div>
          <div><span>{{ trace.type === 'order' ? '客户' : '供应商' }}</span><strong>{{ header('counterparty') }}</strong></div>
          <div><span>当前状态</span><strong>{{ header('status') }}</strong></div>
          <div><span>金额</span><strong>¥{{ header('totalAmount') }}</strong></div>
        </section>
        <section class="trace-section">
          <h3>业务轨迹</h3>
          <ol v-if="trace.timeline.length" class="trace-timeline">
            <li v-for="(event, index) in trace.timeline" :key="`${event.title}-${index}`">
              <time>{{ time(event.occurredAt) }}</time>
              <div class="trace-node"><strong>{{ event.title }}</strong><p>{{ event.description }}</p></div>
            </li>
          </ol>
          <p v-else class="trace-empty">暂无业务流转记录</p>
        </section>
        <section class="trace-section">
          <h3>{{ trace.type === 'order' ? '订单明细' : '采购明细' }}</h3>
          <div class="trace-table-wrap"><table class="trace-table"><thead><tr><th v-for="key in detailKeys()" :key="key">{{ labels[key] }}</th></tr></thead><tbody><tr v-for="(row, rowIndex) in trace.details" :key="rowIndex"><td v-for="key in detailKeys()" :key="key"><OverflowText :value="value(row[key])" /></td></tr><tr v-if="!trace.details.length"><td class="trace-empty" :colspan="Math.max(1, detailKeys().length)">暂无明细</td></tr></tbody></table></div>
        </section>
      </div>
    </section>
  </div>
</template>
