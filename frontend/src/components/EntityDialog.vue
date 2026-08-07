<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { createEntity, loadOrderSkus, type OrderSku, updateEntity } from '../api/workbench'
import ChineseDatePicker from './ChineseDatePicker.vue'
import FuzzyPicker, { type FuzzyPickerOption } from './FuzzyPicker.vue'
import type { ModuleKey } from '../modules/module-config'

const props = defineProps<{ module: ModuleKey; row?: Record<string, unknown> }>()
const emit = defineEmits<{ close: []; saved: []; message: [text: string, kind?: 'success' | 'error'] }>()

interface Field {
  key: string
  label: string
  type?: string
  required?: boolean
  multiline?: boolean
  readOnly?: boolean
}

const definitions: Record<string, Field[]> = {
  customer: [
    { key: 'customerCode', label: '客户编码' },
    { key: 'customerName', label: '客户名称', required: true }
  ],
  user: [
    { key: 'username', label: '用户名', required: true },
    { key: 'displayName', label: '姓名', required: true },
    { key: 'phone', label: '联系电话', type: 'tel' }
  ],
  product: [
    { key: 'skuCode', label: '物料编号' },
    { key: 'model', label: '型号', required: true },
    { key: 'color', label: '颜色' },
    { key: 'lockBody', label: '锁体' },
    { key: 'configuration', label: '物料规格', multiline: true },
    { key: 'currentCost', label: '成本单价（含税）', type: 'number' },
    { key: 'factoryPrice', label: '转厂价格', type: 'number' },
    { key: 'priceDifference', label: '差异：转厂价-原成本', type: 'number', readOnly: true },
    { key: 'remark', label: '备注', multiline: true },
  ],
  inventory: [
    { key: 'skuCode', label: '物料编号 SKU', required: true },
    { key: 'model', label: '型号' },
    { key: 'configuration', label: '产品配置', multiline: true },
    { key: 'productVersion', label: '版本' },
    { key: 'color', label: '颜色' },
    { key: 'lockBody', label: '锁体' },
    { key: 'unit', label: '单位' },
    { key: 'actualQuantity', label: '实际库存数量', type: 'number', required: true },
    { key: 'availableQuantity', label: '可用库存数量', type: 'number' },
    { key: 'lockedQuantity', label: '已锁定数量', type: 'number' },
    { key: 'lockedMingAiJunQiao', label: '铭爱钧乔', type: 'number' },
    { key: 'lockedBoLeLongMi', label: '博乐龙米', type: 'number' },
    { key: 'lockedLaos', label: '老挝', type: 'number' },
    { key: 'lockedBeiLang', label: '贝朗', type: 'number' },
    { key: 'lockedMalaysia', label: '马来西亚', type: 'number' },
    { key: 'inTransitQuantity', label: '在途数量', type: 'number', required: true },
    { key: 'sourceSupplierName', label: '供应商' },
    { key: 'inventoryRemark', label: '备注', multiline: true }
  ]
}

const fields = definitions[props.module] ?? []
const initialForm = Object.fromEntries(
  Object.entries(props.row ?? {}).map(([key, value]) => [
    key,
    value == null || typeof value === 'string' || typeof value === 'number' ? value : String(value)
  ])
) as Record<string, string | number | null>
if (props.module === 'inventory' && !props.row?.id) {
  Object.assign(initialForm, {
    actualQuantity: 0,
    availableQuantity: 0,
    lockedQuantity: 0,
    inTransitQuantity: 0,
    lockedMingAiJunQiao: 0,
    lockedBoLeLongMi: 0,
    lockedLaos: 0,
    lockedBeiLang: 0,
    lockedMalaysia: 0
  })
}
const form = reactive(initialForm)
const initialInventorySkuValues = Object.fromEntries(
  ['model', 'configuration', 'productVersion', 'color', 'lockBody', 'unit'].map(key => [key, form[key] ?? null])
)
const saving = ref(false)
type InventoryMovement = { date: string; direction: 'INBOUND' | 'OUTBOUND'; quantity: number | null }
const inventoryMovements = ref<InventoryMovement[]>([])
const inventorySkus = ref<OrderSku[]>([])

const inventorySkuOptions = computed<FuzzyPickerOption[]>(() => inventorySkus.value.map(sku => ({
  id: sku.id,
  label: sku.productName?.trim() || sku.model?.trim() || '未命名产品',
  searchText: [sku.skuCode, sku.productName, sku.model, sku.configuration, sku.productVersion, sku.color, sku.lockBody, sku.unit]
    .filter(Boolean).join(' ')
})))

const inventorySkuId = computed<number | null>({
  get: () => {
    const value = Number(form.skuId)
    return Number.isInteger(value) && value > 0 ? value : null
  },
  set: value => selectInventorySku(value)
})

const priceDifference = computed(() => {
  const currentCost = Number(form.currentCost)
  const factoryPrice = Number(form.factoryPrice)
  if (!Number.isFinite(currentCost) || !Number.isFinite(factoryPrice)) return ''
  return Number((factoryPrice - currentCost).toFixed(4))
})

const lockedQuantity = computed(() => {
  const keys = ['lockedMingAiJunQiao', 'lockedBoLeLongMi', 'lockedLaos', 'lockedBeiLang', 'lockedMalaysia']
  return keys.reduce((total, key) => total + (Number(form[key]) || 0), 0)
})

const availableQuantity = computed(() => {
  const actual = Number(form.actualQuantity)
  const transit = Number(form.inTransitQuantity)
  if (!Number.isFinite(actual) || !Number.isFinite(transit)) return ''
  const movementDelta = inventoryMovements.value.reduce((total, movement) => {
    const quantity = Number(movement.quantity) || 0
    return total + (movement.direction === 'INBOUND' ? quantity : -quantity)
  }, 0)
  return actual + movementDelta + transit - lockedQuantity.value
})

function inventoryNumber(key: string) {
  const value = Number(form[key])
  return Number.isFinite(value) ? Math.max(0, value) : 0
}

function refreshAvailableQuantity() {
  form.availableQuantity = Math.max(0, inventoryNumber('actualQuantity') + inventoryNumber('inTransitQuantity') - inventoryNumber('lockedQuantity'))
}

function refreshActualQuantity() {
  form.actualQuantity = Math.max(0, inventoryNumber('availableQuantity') + inventoryNumber('lockedQuantity') - inventoryNumber('inTransitQuantity'))
}

function syncLockedAllocations(total: number) {
  form.lockedMingAiJunQiao = total
  form.lockedBoLeLongMi = 0
  form.lockedLaos = 0
  form.lockedBeiLang = 0
  form.lockedMalaysia = 0
}

function onInventoryMetricChange(metric: 'actual' | 'available' | 'locked' | 'transit') {
  if (metric === 'available') refreshActualQuantity()
  else if (metric === 'locked') {
    const locked = inventoryNumber('lockedQuantity')
    syncLockedAllocations(locked)
    refreshActualQuantity()
  } else refreshAvailableQuantity()
}

function onInventoryAllocationChange() {
  form.lockedQuantity = lockedQuantity.value
  refreshAvailableQuantity()
}

function selectInventorySku(skuId: number | null) {
  form.skuId = skuId
  const sku = inventorySkus.value.find(item => item.id === skuId)
  if (!sku) return
  form.skuCode = sku.skuCode ?? ''
  form.model = sku.model ?? ''
  form.configuration = sku.configuration ?? ''
  form.productVersion = sku.productVersion ?? ''
  form.color = sku.color ?? ''
  form.lockBody = sku.lockBody ?? ''
  form.unit = sku.unit ?? ''
}

function inventoryFieldTestId(key: string) {
  if (props.module !== 'inventory') return undefined
  const names: Record<string, string> = {
    model: 'inventory-model', configuration: 'inventory-configuration', color: 'inventory-color',
    lockBody: 'inventory-lock-body', unit: 'inventory-unit', actualQuantity: 'inventory-actual-quantity',
    availableQuantity: 'inventory-available-quantity', lockedQuantity: 'inventory-locked-quantity'
  }
  return names[key]
}

function today() {
  const date = new Date()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

function addInventoryMovement() {
  inventoryMovements.value.push({ date: today(), direction: 'INBOUND', quantity: null })
}

function removeInventoryMovement(index: number) {
  inventoryMovements.value.splice(index, 1)
}

function readOnlyValue(field: Field) {
  if (field.key === 'priceDifference') return priceDifference.value
  if (field.key === 'lockedQuantity') return lockedQuantity.value
  if (field.key === 'availableQuantity') return availableQuantity.value
  return form[field.key] ?? ''
}

function createPayload() {
  const body: Record<string, unknown> = {}
  for (const field of fields) {
    if (field.readOnly) continue
    const value = form[field.key]
    body[field.key] = field.type === 'number' && value !== '' && value != null ? Number(value) : value
  }
  if (props.row?.id && typeof form.version === 'number') {
    body.version = form.version
  }
  if (props.module === 'product' && !body.productName) {
    body.productName = body.model || body.configuration || body.skuCode
  }
  if (props.module === 'inventory') {
    if (inventorySkuId.value != null) body.skuId = inventorySkuId.value
    if (props.row?.id) {
      for (const key of Object.keys(initialInventorySkuValues)) {
        if (String(form[key] ?? '') === String(initialInventorySkuValues[key] ?? '')) delete body[key]
      }
    }
    body.inventoryMovements = inventoryMovements.value
      .filter(movement => Number(movement.quantity) > 0)
      .map(movement => ({ ...movement, quantity: Number(movement.quantity) }))
    if ((body.inventoryMovements as InventoryMovement[]).length) delete body.availableQuantity
  }
  return body
}

onMounted(async () => {
  if (props.module !== 'inventory') return
  try {
    inventorySkus.value = (await loadOrderSkus()) ?? []
    if (inventorySkuId.value != null) selectInventorySku(inventorySkuId.value)
  } catch (error) {
    emit('message', error instanceof Error ? error.message : '无法加载产品选项', 'error')
  }
})

async function save() {
  saving.value = true
  try {
    const body = createPayload()
    if (props.row?.id) await updateEntity(props.module, Number(props.row.id), body)
    else await createEntity(props.module, body)
    emit('message', '保存成功')
    emit('saved')
  } catch (error) {
    emit('message', error instanceof Error ? error.message : '保存失败', 'error')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="dialog-mask" @click.self="emit('close')">
    <section class="dialog-card" role="dialog" aria-modal="true">
      <header>
        <h2>{{ row?.id ? '修改' : '新增' }}{{ module === 'customer' ? '客户' : module === 'user' ? '用户' : module === 'product' ? '产品' : '库存' }}</h2>
        <button @click="emit('close')">关闭</button>
      </header>
      <form @submit.prevent="save">
        <div class="form-grid">
          <label v-for="field in fields" :key="field.key">
            <span>{{ field.label }}</span>
            <FuzzyPicker
              v-if="module === 'inventory' && field.key === 'skuCode' && !row?.id"
              v-model="inventorySkuId"
              data-test="inventory-product-picker"
              :options="inventorySkuOptions"
              placeholder="输入物料编号、型号或产品名称搜索"
              :disabled="saving"
            />
            <textarea
              v-else-if="field.multiline && !field.readOnly"
              v-model="form[field.key]"
              :data-test="inventoryFieldTestId(field.key)"
              :required="field.required"
              :disabled="Boolean(row?.id) && ['customerCode', 'username', 'skuCode', 'skuId'].includes(field.key)"
            />
            <textarea
              v-else-if="field.multiline"
              :value="readOnlyValue(field)"
              disabled
            />
            <input
              v-else-if="field.readOnly"
              :data-test="field.key === 'priceDifference' ? 'price-difference' : undefined"
              :value="readOnlyValue(field)"
              :type="field.type ?? 'text'"
              disabled
              step="any"
            />
            <input
              v-else
              v-model="form[field.key]"
              :data-test="inventoryFieldTestId(field.key)"
              :type="field.type ?? 'text'"
              :required="field.required"
              :disabled="Boolean(row?.id) && ['customerCode', 'username', 'skuCode', 'skuId'].includes(field.key)"
              step="any"
              @input="field.key === 'actualQuantity' ? onInventoryMetricChange('actual') : field.key === 'availableQuantity' ? onInventoryMetricChange('available') : field.key === 'lockedQuantity' ? onInventoryMetricChange('locked') : field.key === 'inTransitQuantity' ? onInventoryMetricChange('transit') : ['lockedMingAiJunQiao', 'lockedBoLeLongMi', 'lockedLaos', 'lockedBeiLang', 'lockedMalaysia'].includes(field.key) ? onInventoryAllocationChange() : undefined"
            />
          </label>
        </div>
        <section v-if="module === 'inventory'" class="order-section inventory-movement-section">
          <div class="line-title">
            <h3>入/出库明细</h3>
            <button type="button" data-test="add-inventory-movement" @click="addInventoryMovement">新增明细</button>
          </div>
          <div class="inventory-movement-scroll">
            <div class="inventory-movement-grid inventory-movement-grid-head">
              <span>日期</span><span>类型</span><span>数量</span><span>操作</span>
            </div>
            <p v-if="!inventoryMovements.length" class="inventory-movement-empty">暂未添加明细</p>
            <div v-for="(movement, index) in inventoryMovements" :key="index" class="order-line inventory-movement-grid">
              <label><span class="sr-only">日期</span><ChineseDatePicker v-model="movement.date" /></label>
              <label><span class="sr-only">类型</span><select v-model="movement.direction"><option value="INBOUND">入库</option><option value="OUTBOUND">出库</option></select></label>
              <label><span class="sr-only">数量</span><input v-model.number="movement.quantity" type="number" min="0" step="1" placeholder="填写数量" /></label>
              <button type="button" @click="removeInventoryMovement(index)">删除</button>
            </div>
          </div>
        </section>
        <footer>
          <button type="button" class="secondary-action" @click="emit('close')">取消操作</button>
          <button class="primary-action" :disabled="saving">确认保存</button>
        </footer>
      </form>
    </section>
  </div>
</template>
