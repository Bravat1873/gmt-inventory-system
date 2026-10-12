<script setup lang="ts">
import { computed } from 'vue'
import type { PurchaseReceiptItem } from '../api/workbench'
import ProductIdentityDisplay from './ProductIdentityDisplay.vue'

const props = defineProps<{
  requirements: { salesDeliveryContact?: unknown; salesDeliveryAddress?: unknown; salesOrderRemark?: unknown }
  items?: PurchaseReceiptItem[]
}>()
const fields = computed(() => [
  { label: '销售收货联系人及电话', value: props.requirements.salesDeliveryContact },
  { label: '销售收货地址', value: props.requirements.salesDeliveryAddress },
  { label: '销售订单备注', value: props.requirements.salesOrderRemark }
].filter(field => String(field.value ?? '').trim()))
const lines = computed(() => (props.items ?? []).filter(item => item.remark?.trim()))
</script>

<template>
  <section v-if="fields.length || lines.length" class="purchase-sales-requirements" data-test="purchase-sales-requirements">
    <h3>销售订单要求</h3>
    <div v-for="field in fields" :key="field.label" class="requirement-field">
      <strong>{{ field.label }}</strong><p>{{ field.value }}</p>
    </div>
    <div v-for="item in lines" :key="item.id" class="requirement-field" data-test="purchase-sales-line-remark">
      <strong>销售明细备注</strong>
      <ProductIdentityDisplay compact :product-code="item.productCode" :customer-part-number="item.customerPartNumber" :model="item.model" />
      <p>{{ item.remark }}</p>
    </div>
  </section>
</template>

<style scoped>
.purchase-sales-requirements { margin: 12px 0; padding: 16px; border: 1px solid #dce3ec; border-radius: 8px; background: #f8fafc; min-width: 0; }
h3 { margin: 0 0 12px; font-size: 16px; }
.requirement-field + .requirement-field { margin-top: 12px; }
strong { font-size: 13px; }
p { margin: 5px 0 0; line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
