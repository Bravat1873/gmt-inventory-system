<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { loadUnconfiguredProcurementShortages, type UnconfiguredProcurementShortage } from '../api/workbench'

const emit = defineEmits<{ navigateSupplier: []; message: [text: string, kind?: 'success' | 'error'] }>()
const items = ref<UnconfiguredProcurementShortage[]>([])
const expanded = ref(false)

async function load() {
  try { items.value = await loadUnconfiguredProcurementShortages() }
  catch (cause) { emit('message', cause instanceof Error ? cause.message : '读取待补供应商配置失败', 'error') }
}

onMounted(load)
defineExpose({ reload: load })
</script>

<template>
  <section v-if="items.length" class="procurement-configuration-alert" role="status">
    <div class="procurement-alert-summary">
      <span class="procurement-alert-icon" aria-hidden="true">!</span>
      <div>
        <strong>{{ items.length }} 个缺货产品尚未配置有效供应商采购信息</strong>
        <small>这些产品暂不生成采购建议，其他已配置产品不受影响</small>
      </div>
      <button type="button" class="secondary-action" data-test="toggle-procurement-alert" :aria-expanded="expanded" @click="expanded=!expanded">
        {{ expanded ? '收起明细' : '查看明细' }}
      </button>
    </div>
    <div v-if="expanded" class="procurement-alert-details" data-test="procurement-alert-details">
      <article v-for="item in items" :key="item.skuId">
        <div class="procurement-alert-product">
          <strong>{{ item.customerPartNumber || item.productName || `产品 ${item.skuId}` }}</strong>
          <span v-if="item.productName && item.productName !== item.customerPartNumber">{{ item.productName }}</span>
        </div>
        <span class="procurement-alert-shortage">缺口 {{ item.shortageQuantity }}</span>
        <span class="procurement-alert-orders" :title="item.orderNumbers.join('、')">订单：{{ item.orderNumbers.join('、') || '—' }}</span>
        <button type="button" data-test="open-supplier-management" @click="emit('navigateSupplier')">前往供应商管理</button>
      </article>
    </div>
  </section>
</template>

<style scoped>
.procurement-configuration-alert{flex:0 0 auto;margin:0 20px 16px;border:1px solid #f1c27d;border-radius:8px;background:#fffaf0;color:#3b2f1f;overflow:hidden}
.procurement-alert-summary{min-height:58px;padding:10px 14px;display:grid;grid-template-columns:28px minmax(0,1fr) auto;align-items:center;gap:10px}
.procurement-alert-icon{width:22px;height:22px;border-radius:50%;display:grid;place-items:center;background:#b54708;color:white;font-weight:700}
.procurement-alert-summary div{display:flex;flex-direction:column;gap:3px;min-width:0}.procurement-alert-summary small{color:#7a5a2b}
.procurement-alert-details{max-height:224px;overflow:auto;border-top:1px solid #f1d5a6;background:#fff;padding:0 14px}
.procurement-alert-details article{min-height:52px;display:grid;grid-template-columns:minmax(150px,1fr) 100px minmax(220px,2fr) auto;align-items:center;gap:12px;border-bottom:1px solid #f2ede5}
.procurement-alert-details article:last-child{border-bottom:0}.procurement-alert-product{display:flex;flex-direction:column;min-width:0}.procurement-alert-product span,.procurement-alert-orders{color:#6b6257;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.procurement-alert-shortage{font-weight:600;color:#b42318}.procurement-alert-details button{white-space:nowrap}
@media(max-width:800px){.procurement-alert-details article{grid-template-columns:1fr auto}.procurement-alert-orders{grid-column:1/-1}.procurement-alert-summary{grid-template-columns:28px 1fr}.procurement-alert-summary button{grid-column:2;justify-self:start}}
</style>
