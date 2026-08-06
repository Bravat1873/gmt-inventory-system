<script setup lang="ts">
import { reactive, ref } from 'vue'
import { createManualPurchase } from '../api/workbench'

const emit = defineEmits<{ close: []; saved: []; message: [text: string, kind?: 'success' | 'error'] }>()
const form = reactive({ supplierId: '', skuId: '', quantity: 1, purchasePrice: '', expectedArrivalDate: '', remark: '' })
const saving = ref(false)
const error = ref('')
const dateInput = ref<HTMLInputElement>()

function formattedArrivalDate() {
  if (!form.expectedArrivalDate) return '请选择日期'
  const [year, month, day] = form.expectedArrivalDate.split('-').map(Number)
  return `${year}年${month}月${day}日`
}

function openDatePicker() {
  const input = dateInput.value
  if (!input || saving.value) return
  if (typeof input.showPicker === 'function') input.showPicker()
  else input.focus()
}

function validate() {
  const supplierId = Number(form.supplierId)
  const skuId = Number(form.skuId)
  const quantity = Number(form.quantity)
  const purchasePrice = Number(form.purchasePrice)
  if (!Number.isInteger(supplierId) || supplierId <= 0) return '请填写有效的供应商 ID'
  if (!Number.isInteger(skuId) || skuId <= 0) return '请填写有效的产品 ID'
  if (!Number.isInteger(quantity) || quantity <= 0) return '采购数量必须为大于 0 的整数'
  if (!Number.isFinite(purchasePrice) || purchasePrice <= 0) return '采购单价必须为大于 0 的有效数字'
  return ''
}

function requestClose() {
  if (!saving.value) emit('close')
}

async function save() {
  if (saving.value) return
  error.value = validate()
  if (error.value) return
  saving.value = true
  try {
    const result = await createManualPurchase({
      supplierId: Number(form.supplierId), skuId: Number(form.skuId), quantity: Number(form.quantity),
      purchasePrice: Number(form.purchasePrice), expectedArrivalDate: form.expectedArrivalDate || undefined,
      remark: form.remark.trim() || undefined
    })
    emit('message', `采购单 ${String(result.purchaseNo ?? '')} 已创建`)
    emit('saved')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '创建采购单失败'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="dialog-mask" @click.self="requestClose">
    <section class="dialog-card" role="dialog" aria-modal="true" aria-labelledby="manual-purchase-title">
      <header><h2 id="manual-purchase-title">手工采购</h2><button type="button" :disabled="saving" @click="requestClose">关闭</button></header>
      <form novalidate @submit.prevent="save">
        <div class="form-grid">
          <label><span>供应商 ID</span><input v-model="form.supplierId" type="number" min="1" :aria-invalid="Boolean(error)" :disabled="saving"></label>
          <label><span>产品 ID</span><input v-model="form.skuId" type="number" min="1" :aria-invalid="Boolean(error)" :disabled="saving"></label>
          <label><span>采购数量</span><input v-model.number="form.quantity" type="number" min="1" step="1" :aria-invalid="Boolean(error)" :disabled="saving"></label>
          <label><span>采购单价</span><input v-model="form.purchasePrice" type="number" min="0.0001" step="0.0001" :aria-invalid="Boolean(error)" :disabled="saving"></label>
          <label class="date-field"><span>预计到货日期</span><div class="date-picker"><button type="button" class="date-display" :class="{ empty: !form.expectedArrivalDate }" data-test="expected-arrival-display" :disabled="saving" @click="openDatePicker"><span>{{ formattedArrivalDate() }}</span><span class="date-display-icon" aria-hidden="true"></span></button><input ref="dateInput" v-model="form.expectedArrivalDate" class="native-date-input" type="date" :disabled="saving"></div></label>
          <label><span>备注</span><textarea v-model="form.remark" maxlength="500" :disabled="saving"></textarea></label>
        </div>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <footer><button type="button" class="secondary-action" :disabled="saving" @click="requestClose">取消操作</button><button class="primary-action" :disabled="saving">{{ saving ? '正在保存…' : '确认保存' }}</button></footer>
      </form>
    </section>
  </div>
</template>
