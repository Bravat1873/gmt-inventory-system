<script setup lang="ts">
import { computed } from 'vue'
import { identityValue, productIdentityLines } from '../utils/product-identity'

const props = withDefaults(defineProps<{
  productCode?: string | null
  customerPartNumber?: string | null
  model?: string | null
  compact?: boolean
}>(), { compact: false })

const title = computed(() => productIdentityLines(props).join('\n'))
</script>

<template>
  <div :class="['product-identity-display', { compact }]" :title="title">
    <div><span>产品编号</span><strong class="product-identity-value">{{ identityValue(productCode) }}</strong></div>
    <div><span>客户料号</span><strong class="product-identity-value">{{ identityValue(customerPartNumber) }}</strong></div>
    <div><span>型号</span><strong class="product-identity-value">{{ identityValue(model) }}</strong></div>
  </div>
</template>
