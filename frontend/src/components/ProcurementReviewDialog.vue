<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { confirmProcurementSuggestion, loadProcurementSuggestion, rejectProcurementSuggestion, updateProcurementSuggestion, type ProcurementSuggestionDetail } from '../api/workbench'

const props = defineProps<{ suggestionId: number }>()
const emit = defineEmits<{ close: []; saved: []; message: [text: string, kind?: 'success' | 'error'] }>()
const detail = ref<ProcurementSuggestionDetail | null>(null)
const loading = ref(true)
const saving = ref(false)
const rejectReason = ref('')
const errors = reactive<Record<string, string>>({})
const total = computed(() => detail.value?.items.reduce((sum, item) => sum + Number(item.suggestedQuantity) * Number(item.purchasePrice), 0) ?? 0)
const money = (value: number) => Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

async function load() {
  loading.value = true
  try { detail.value = await loadProcurementSuggestion(props.suggestionId) }
  catch (cause) { errors.submit = cause instanceof Error ? cause.message : '读取待确认采购失败' }
  finally { loading.value = false }
}
function validate() {
  delete errors.items
  const invalid = detail.value?.items.find(item => !Number.isInteger(Number(item.suggestedQuantity)) || Number(item.suggestedQuantity) < Number(item.minimumOrderQuantity))
  if (invalid) errors.items = `${invalid.skuCode || invalid.productName || '采购明细'}：采购数量不能低于最小起购量 ${invalid.minimumOrderQuantity}`
  return !invalid
}
async function saveEdits() {
  if (!detail.value || !validate()) return false
  const result = await updateProcurementSuggestion(detail.value.id, detail.value.version, detail.value.items.map(item => ({ id: item.id, quantity: Number(item.suggestedQuantity), expectedArrivalDate: item.expectedArrivalDate || null })))
  detail.value.version = result.version
  return true
}
async function confirm() {
  if (saving.value || !detail.value) return
  saving.value = true; delete errors.submit
  try {
    if (!await saveEdits()) return
    const result = await confirmProcurementSuggestion(detail.value.id)
    emit('message', `已生成采购单 ${String(result.purchaseNo ?? '')}`); emit('saved')
  } catch (cause) { errors.submit = cause instanceof Error ? cause.message : '确认采购失败' }
  finally { saving.value = false }
}
async function reject() {
  if (saving.value || !detail.value) return
  if (!rejectReason.value.trim()) { errors.reason = '请填写无需采购原因'; return }
  saving.value = true; delete errors.reason; delete errors.submit
  try { await rejectProcurementSuggestion(detail.value.id, detail.value.version, rejectReason.value.trim()); emit('message', '已标记为无需采购'); emit('saved') }
  catch (cause) { errors.submit = cause instanceof Error ? cause.message : '标记无需采购失败' }
  finally { saving.value = false }
}
onMounted(load)
</script>

<template>
  <div class="dialog-mask procurement-review-mask" data-test="procurement-review-mask">
    <section class="dialog-card procurement-review-dialog" role="dialog" aria-modal="true" aria-labelledby="procurement-review-title">
      <header><div><h2 id="procurement-review-title">待确认采购复核</h2><small v-if="detail">{{ detail.suggestionNo }}</small></div><button type="button" :disabled="saving" @click="emit('close')">关闭</button></header>
      <div v-if="loading" class="dialog-loading">正在读取采购建议…</div>
      <template v-else-if="detail">
        <div class="procurement-review-summary"><div><span>供应商</span><strong>{{ detail.supplierName }}</strong></div><div><span>建议明细</span><strong>{{ detail.items.length }} 项</strong></div><div><span>预计总额</span><strong>¥ {{ money(total) }}</strong></div></div>
        <div class="procurement-review-table-wrap"><table class="procurement-review-table">
          <thead><tr><th>物料</th><th>采购缺口</th><th>最小起购量</th><th>采购数量</th><th>采购单价</th><th>预计金额</th><th>预计到货日期</th></tr></thead>
          <tbody><tr v-for="item in detail.items" :key="item.id"><td><strong>{{ item.skuCode || '—' }}</strong><small>{{ item.productName || '—' }}</small></td><td>{{ item.shortageQuantity }}</td><td>{{ item.minimumOrderQuantity }}</td><td><input v-model.number="item.suggestedQuantity" :data-test="`review-quantity-${item.id}`" type="number" step="1" :min="item.minimumOrderQuantity" :disabled="saving"></td><td>¥ {{ money(item.purchasePrice) }}</td><td>¥ {{ money(item.suggestedQuantity * item.purchasePrice) }}</td><td><input v-model="item.expectedArrivalDate" type="date" :disabled="saving"></td></tr></tbody>
        </table></div>
        <p v-if="errors.items" class="form-error" role="alert">{{ errors.items }}</p>
        <label class="procurement-reject-reason"><span>无需采购原因（仅标记无需采购时填写）</span><textarea v-model="rejectReason" maxlength="500" :disabled="saving" placeholder="例如：客户取消、已有替代库存"></textarea><small v-if="errors.reason" class="field-error">{{ errors.reason }}</small></label>
      </template>
      <p v-if="errors.submit" class="form-error" role="alert">{{ errors.submit }}</p>
      <footer><button type="button" class="secondary-action" :disabled="saving" @click="emit('close')">取消操作</button><button v-if="detail" type="button" class="danger-action" data-test="reject-procurement" :disabled="saving" @click="reject">标记无需采购</button><button v-if="detail" type="button" class="primary-action" data-test="confirm-procurement" :disabled="saving" @click="confirm">{{ saving ? '正在处理…' : '确认并生成采购单' }}</button></footer>
    </section>
  </div>
</template>

<style scoped>
.procurement-review-dialog{width:min(1180px,calc(100vw - 48px));max-height:calc(100vh - 48px);display:flex;flex-direction:column}.procurement-review-dialog>header>div{display:flex;align-items:baseline;gap:14px}.procurement-review-dialog>header small{color:#6b7280}.dialog-loading{padding:56px;text-align:center;color:#6b7280}.procurement-review-summary{display:grid;grid-template-columns:2fr 1fr 1fr;gap:12px;padding:18px 24px}.procurement-review-summary>div{border:1px solid #e5e7eb;border-radius:8px;padding:12px 14px;display:flex;flex-direction:column;gap:5px}.procurement-review-summary span{color:#6b7280;font-size:13px}.procurement-review-table-wrap{margin:0 24px;overflow:auto;border:1px solid #e5e7eb;border-radius:8px}.procurement-review-table{width:100%;min-width:980px;border-collapse:collapse}.procurement-review-table th,.procurement-review-table td{padding:10px 12px;border-bottom:1px solid #eceff3;text-align:left;white-space:nowrap}.procurement-review-table td:first-child{white-space:normal;min-width:180px}.procurement-review-table td:first-child small{display:block;color:#6b7280;margin-top:3px}.procurement-review-table input{width:132px;box-sizing:border-box}.procurement-reject-reason{margin:18px 24px 0;display:flex;flex-direction:column;gap:8px}.procurement-reject-reason textarea{min-height:68px;resize:vertical}.danger-action{color:#b42318;border-color:#f0b4ae;background:#fff}.form-error{margin:12px 24px 0;color:#b42318}.procurement-review-dialog>footer{display:flex;justify-content:flex-end;gap:10px;padding:18px 24px}@media(max-width:760px){.procurement-review-summary{grid-template-columns:1fr}.procurement-review-dialog{width:calc(100vw - 20px);max-height:calc(100vh - 20px)}}
</style>
