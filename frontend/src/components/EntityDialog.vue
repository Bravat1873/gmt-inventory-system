<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { createEntity, loadOrderSkus, loadProductCodeRules, type ProductCodeRule, type OrderSku, updateEntity, uploadProductImages } from '../api/workbench'
import ChineseDatePicker from './ChineseDatePicker.vue'
import FuzzyPicker, { type FuzzyPickerOption } from './FuzzyPicker.vue'
import ProductImagePicker from './ProductImagePicker.vue'
import type { ModuleKey } from '../modules/module-config'
import type { UserRole } from '../api/auth'

const props = withDefaults(defineProps<{ module: ModuleKey; row?: Record<string, unknown>; currentUserRole?: UserRole }>(), {
  currentUserRole: 'USER'
})
const emit = defineEmits<{ close: []; saved: [closeDialog?: boolean]; message: [text: string, kind?: 'success' | 'error'] }>()

interface Field {
  key: string
  label: string
  type?: string
  required?: boolean
  multiline?: boolean
  readOnly?: boolean
  optionCategory?: string
}

const definitions: Record<string, Field[]> = {
  customer: [
    { key: 'customerCode', label: '客户编码' },
    { key: 'customerName', label: '客户名称', required: true },
    { key: 'address', label: '客户地址', multiline: true },
    { key: 'businessContactName', label: '业务联系人' },
    { key: 'businessContactPhone', label: '业务联系人电话', type: 'tel' },
    { key: 'orderContactName', label: '订单联系人' },
    { key: 'orderContactPhone', label: '订单联系人电话', type: 'tel' },
    { key: 'financeContactName', label: '财务联系人' },
    { key: 'financeContactPhone', label: '财务联系人电话', type: 'tel' },
    { key: 'invoiceTitle', label: '发票抬头' },
    { key: 'taxpayerId', label: '纳税人识别号' },
    { key: 'invoiceAddress', label: '开票地址', multiline: true },
    { key: 'invoicePhone', label: '开票电话', type: 'tel' },
    { key: 'bankName', label: '开户银行' },
    { key: 'bankAccount', label: '银行账号' }
  ],
  user: [
    { key: 'username', label: '用户名', required: true },
    { key: 'displayName', label: '姓名', required: true },
    { key: 'phone', label: '联系电话', type: 'tel' },
    { key: 'password', label: '密码', type: 'text' },
    { key: 'role', label: '角色' }
  ],
  product: [
    { key: 'productCode', label: '产品编号', readOnly: true },
    { key: 'codeSuffix', label: '编码后缀' },
    { key: 'eanCode', label: 'EAN码' },
    { key: 'customerCode', label: '客户料号' },
    { key: 'productType', label: '产品分类', required: true, optionCategory: 'PRODUCT_TYPE' },
    { key: 'materialType', label: '物料类型', required: true },
    { key: 'brandRuleId', label: '品牌', required: true, optionCategory: 'BRAND' },
    { key: 'seriesRuleId', label: '系列', optionCategory: 'SERIES' },
    { key: 'bodyColorRuleId', label: '物料颜色', optionCategory: 'BODY_COLOR' },
    { key: 'lockTypeRuleId', label: '锁体类型', optionCategory: 'LOCK_TYPE' },
    { key: 'connectivityRuleId', label: '联网方式', optionCategory: 'CONNECTIVITY' },
    { key: 'salesChannelRuleId', label: '销售渠道', optionCategory: 'SALES_CHANNEL' },
    { key: 'operatingEntityRuleId', label: '运营主体', optionCategory: 'OPERATING_ENTITY' },
    { key: 'languageRuleId', label: '语言', optionCategory: 'LANGUAGE' },
    { key: 'doorModelRuleId', label: '成品型号', optionCategory: 'DOOR_MODEL' },
    { key: 'securityGradeRuleId', label: '安全等级', optionCategory: 'SECURITY_GRADE' },
    { key: 'baseMaterialRuleId', label: '主基材料', optionCategory: 'BASE_MATERIAL' },
    { key: 'thicknessRuleId', label: '成品厚度', optionCategory: 'THICKNESS' },
    { key: 'finishColorRuleId', label: '花色', optionCategory: 'FINISH_COLOR' },
    { key: 'model', label: '型号', required: true },
    { key: 'configuration', label: '物料规格', multiline: true, readOnly: true },
    { key: 'productConfiguration', label: '产品配置', multiline: true },
    { key: 'salesMinimumOrderQuantity', label: '销售最小起订量', type: 'number', required: true },
    { key: 'remark', label: '备注', multiline: true },
  ],
  inventory: [
    { key: 'skuCode', label: '产品编号', required: true },
    { key: 'model', label: '型号', readOnly: true },
    { key: 'productType', label: '产品类型', optionCategory: 'PRODUCT_TYPE' },
    { key: 'productConfiguration', label: '产品配置', multiline: true, readOnly: true },
    { key: 'configuration', label: '物料规格', multiline: true, readOnly: true },
    { key: 'unit', label: '单位' },
    { key: 'actualQuantity', label: '实际库存数量', type: 'number', required: true },
    { key: 'availableQuantity', label: '未锁定库存数量', type: 'number' },
    { key: 'lockedQuantity', label: '已锁定数量', type: 'number' },
    { key: 'inTransitQuantity', label: '在途数量', type: 'number', required: true },
    { key: 'pendingDeliveryQuantity', label: '未发货数量', type: 'number', readOnly: true },
    { key: 'supplyDemandBalance', label: '供需余量', type: 'number', readOnly: true },
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
  })
}
if (props.module === 'product' && !props.row?.id) {
  initialForm.salesMinimumOrderQuantity = 1
}
if (props.module === 'user' && !props.row?.id) {
  initialForm.role = 'USER'
}
const form = reactive(initialForm)
const canEditProductPrice = computed(() => ['ADMIN', 'FINANCE'].includes(props.currentUserRole))
const productSupplierQuotes = computed(() => Array.isArray(props.row?.supplierQuotes)
  ? props.row.supplierQuotes as Array<{ supplierId: number; supplierName: string; purchasePrice: number }>
  : [])
function supplierQuotePrice(value: number) { return Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 4 }) }
const canManageUserRoles = computed(() => props.currentUserRole === 'ADMIN')
const roleOptions: { value: UserRole; label: string }[] = [
  { value: 'ADMIN', label: '管理员' },
  { value: 'FINANCE', label: '财务' },
  { value: 'USER', label: '普通用户' }
]

function fieldAutocomplete(field: Field): string | undefined {
  if (props.module !== 'user') return undefined
  return field.key === 'password' ? 'new-password' : 'off'
}
const initialInventorySkuValues = Object.fromEntries(
  ['model', 'configuration', 'productVersion', 'color', 'lockBody', 'unit'].map(key => [key, form[key] ?? null])
)
const saving = ref(false)
const pendingImages = ref<File[]>([])
const productCodeRules = ref<ProductCodeRule[]>([])
type InventoryMovement = { date: string; direction: 'INBOUND' | 'OUTBOUND'; quantity: number | null }
const inventoryMovements = ref<InventoryMovement[]>([])
const inventorySkus = ref<OrderSku[]>([])

const inventorySkuOptions = computed<FuzzyPickerOption[]>(() => inventorySkus.value.map(sku => ({
  id: sku.id,
  label: sku.productName?.trim() || sku.model?.trim() || '未命名产品',
  searchText: [sku.productCode, sku.skuCode, sku.productName, sku.model, sku.configuration, sku.productVersion, sku.color, sku.lockBody, sku.unit]
    .filter(Boolean).join(' ')
})))

const inventorySkuId = computed<number | null>({
  get: () => {
    const value = Number(form.skuId)
    return Number.isInteger(value) && value > 0 ? value : null
  },
  set: value => selectInventorySku(value)
})

function selectedRuleName(category: string, value: unknown) {
  const id = Number(value)
  return productCodeRules.value.find(rule => rule.category === category && rule.id === id)?.displayName?.trim() ?? ''
}

const materialSpecification = computed(() => [
  selectedRuleName('BRAND', form.brandRuleId),
  String(form.model ?? '').trim(),
  selectedRuleName('BODY_COLOR', form.bodyColorRuleId),
  selectedRuleName('LOCK_TYPE', form.lockTypeRuleId),
  selectedRuleName('LANGUAGE', form.languageRuleId)
].filter(Boolean).join(' / '))
const priceDifference = computed(() => {
  const currentCost = Number(form.currentCost)
  const factoryPrice = Number(form.factoryPrice)
  if (!Number.isFinite(currentCost) || !Number.isFinite(factoryPrice)) return ''
  return Number((factoryPrice - currentCost).toFixed(4))
})

type LockedAllocation = { lockSource: string; quantity: number | null }
const lockedAllocations = ref<LockedAllocation[]>(Array.isArray(props.row?.lockedAllocations)
  ? (props.row.lockedAllocations as Array<Record<string, unknown>>).map(item => ({ lockSource: String(item.lockSource ?? ''), quantity: Number(item.quantity ?? 0) }))
  : [])
const lockedQuantity = computed(() => inventoryNumber('lockedQuantity'))

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


function onInventoryMetricChange(metric: 'actual' | 'available' | 'locked' | 'transit') {
  if (metric === 'available') refreshActualQuantity()
  else if (metric === 'locked') refreshActualQuantity()
  else refreshAvailableQuantity()
}


function selectInventorySku(skuId: number | null) {
  form.skuId = skuId
  const sku = inventorySkus.value.find(item => item.id === skuId)
  if (!sku) return
  form.skuCode = sku.productCode ?? sku.skuCode ?? ''
  form.model = sku.model ?? ''
  form.productType = sku.productType ?? ''
  form.productConfiguration = sku.productConfiguration ?? ''
  form.configuration = sku.configuration ?? ''
  form.productVersion = sku.productVersion ?? ''
  form.color = sku.color ?? ''
  form.lockBody = sku.lockBody ?? ''
  form.unit = sku.unit ?? ''
}

function inventoryFieldTestId(key: string) {
  if (props.module === 'product') {
    if (key === 'currentCost') return 'product-current-cost'
    if (key === 'factoryPrice') return 'product-factory-price'
    if (key === 'salesMinimumOrderQuantity') return 'sales-minimum-order-quantity'
  }
  if (props.module === 'user') {
    if (key === 'username') return 'user-username'
    if (key === 'displayName') return 'user-display-name'
    if (key === 'password') return 'user-password'
  }
  if (props.module !== 'inventory') return undefined
  const names: Record<string, string> = {
    model: 'inventory-model', productType: 'inventory-product-type', productConfiguration: 'inventory-product-configuration', configuration: 'inventory-configuration', color: 'inventory-color',
    lockBody: 'inventory-lock-body', unit: 'inventory-unit', actualQuantity: 'inventory-actual-quantity',
    availableQuantity: 'inventory-available-quantity', lockedQuantity: 'inventory-locked-quantity',
    pendingDeliveryQuantity: 'inventory-pending-delivery-quantity', supplyDemandBalance: 'inventory-supply-demand-balance'
  }
  return names[key]
}

function fieldLabel(field: Field) {
  if (props.module === 'user' && field.key === 'password') {
    return props.row?.id ? '重置密码（留空表示不修改）' : '初始密码'
  }
  return field.label
}

function fieldVisible(field: Field) {
  if (props.module !== 'product' || !field.optionCategory || ['PRODUCT_TYPE', 'BRAND'].includes(field.optionCategory)) return true
  const smart = ['SERIES','BODY_COLOR','LOCK_TYPE','CONNECTIVITY','SALES_CHANNEL','OPERATING_ENTITY','LANGUAGE']
  return form.productType === 'ENTRY_DOOR' ? !smart.includes(field.optionCategory) : smart.includes(field.optionCategory)
}
function fieldRequired(field: Field) {
  if (props.module === 'user' && field.key === 'password') return !props.row?.id
  return field.required
}

function today() {
  const date = new Date()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

function addLockedAllocation() { lockedAllocations.value.push({ lockSource: '', quantity: 0 }) }
function removeLockedAllocation(index: number) { lockedAllocations.value.splice(index, 1) }
function validateLockedAllocations() {
  const sources = new Set<string>()
  let total = 0
  for (const allocation of lockedAllocations.value) {
    const source = allocation.lockSource.trim()
    const quantity = Number(allocation.quantity)
    if (!source) return '地点名称不能为空'
    if (sources.has(source)) return '地点名称不能重复'
    if (!Number.isInteger(quantity) || quantity < 0) return '地点锁定数量必须是非负整数'
    sources.add(source); total += quantity
  }
  return total > inventoryNumber('lockedQuantity') ? '地点锁定数量合计不能超过已锁定数量' : ''
}
function addInventoryMovement() {
  inventoryMovements.value.push({ date: today(), direction: 'INBOUND', quantity: null })
}

function removeInventoryMovement(index: number) {
  inventoryMovements.value.splice(index, 1)
}

function readOnlyValue(field: Field) {
  if (props.module === 'product' && field.key === 'configuration') return materialSpecification.value
  if (field.key === 'productType') return form.productType === 'SMART_LOCK' ? '智能锁' : form.productType === 'ENTRY_DOOR' ? '入户门' : ''
  if (field.key === 'priceDifference') return priceDifference.value
  if (field.key === 'lockedQuantity') return lockedQuantity.value
  if (field.key === 'availableQuantity') return availableQuantity.value
  return form[field.key] ?? ''
}

function createPayload() {
  const body: Record<string, unknown> = {}
  for (const field of fields) {
    if (field.readOnly) continue
    if (props.module === 'product' && ['currentCost', 'factoryPrice'].includes(field.key) && !canEditProductPrice.value) continue
    const value = form[field.key]
    if (field.key === 'role' && (!canManageUserRoles.value || value == null || value === '')) continue
    if (field.key === 'password' && props.row?.id && (value == null || String(value).trim() === '')) continue
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
    body.lockedAllocations = lockedAllocations.value.map(allocation => ({ lockSource: allocation.lockSource.trim(), quantity: Number(allocation.quantity) }))
    body.inventoryMovements = inventoryMovements.value
      .filter(movement => Number(movement.quantity) > 0)
      .map(movement => ({ ...movement, quantity: Number(movement.quantity) }))
    if ((body.inventoryMovements as InventoryMovement[]).length) delete body.availableQuantity
  }
  return body
}

onMounted(async () => {
  if (props.module === 'product') {
    try { productCodeRules.value = await loadProductCodeRules() } catch { productCodeRules.value = [] }
    if (!form.productType && !props.row?.id) form.productType = 'SMART_LOCK'
    if (!form.materialType && !props.row?.id) form.materialType = 'FINISHED_PRODUCT'
    return
  }
  if (props.module !== 'inventory') return
  try {
    inventorySkus.value = (await loadOrderSkus()) ?? []
    if (inventorySkuId.value != null) selectInventorySku(inventorySkuId.value)
  } catch (error) {
    emit('message', error instanceof Error ? error.message : '无法加载产品选项', 'error')
  }
})

async function save() {
  if (props.module === 'inventory') {
    const error = validateLockedAllocations()
    if (error) { emit('message', error, 'error'); return }
  }
  saving.value = true
  try {
    const body = createPayload()
    const savedEntity = props.row?.id
      ? await updateEntity(props.module, Number(props.row.id), body)
      : await createEntity(props.module, body)
    if (props.module === 'user') form.password = ''
    if (props.module === 'product' && pendingImages.value.length) {
      try {
        const productId = Number(savedEntity.id ?? props.row?.id)
        await uploadProductImages(productId, pendingImages.value)
        pendingImages.value = []
      } catch {
        emit('saved', false)
        emit('message', '产品已保存，部分图片上传失败，请重试', 'error')
        return
      }
    }
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
  <div class="dialog-mask">
    <section class="dialog-card" role="dialog" aria-modal="true">
      <header>
        <h2>{{ row?.id ? '修改' : '新增' }}{{ module === 'customer' ? '客户' : module === 'user' ? '用户' : module === 'product' ? '产品' : '库存' }}</h2>
        <button @click="emit('close')">关闭</button>
      </header>
      <form autocomplete="off" @submit.prevent="save">
        <div class="form-grid">
          <template v-for="field in fields" :key="field.key">
          <label v-if="fieldVisible(field)">
            <span>{{ fieldLabel(field) }}</span>
            <select
              v-if="field.optionCategory"
              v-model="form[field.key]"
              :data-test="inventoryFieldTestId(field.key)"
              :required="fieldRequired(field)"
            >
              <option value="">请选择</option>
              <option v-if="field.optionCategory === 'PRODUCT_TYPE'" value="SMART_LOCK">智能锁</option>
              <option v-if="field.optionCategory === 'PRODUCT_TYPE'" value="ENTRY_DOOR">入户门</option>
              <option v-for="rule in productCodeRules.filter(item => item.category === field.optionCategory && item.enabled)" :key="rule.id" :value="rule.id">{{ rule.displayName }}（{{ rule.code }}）</option>
            </select>
            <select
              v-else-if="field.key === 'materialType'"
              v-model="form[field.key]"
              data-test="product-material-type"
              required
            >
              <option value="FINISHED_PRODUCT">成品</option>
              <option value="PART">零件</option>
            </select>
            <select
              v-else-if="field.key === 'role'"
              v-model="form[field.key]"
              data-test="user-role"
              :disabled="!canManageUserRoles"
            >
              <option v-for="option in roleOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
            </select>
            <FuzzyPicker
              v-else-if="module === 'inventory' && field.key === 'skuCode' && !row?.id"
              v-model="inventorySkuId"
              data-test="inventory-product-picker"
              :options="inventorySkuOptions"
              placeholder="输入产品编号、型号或产品名称搜索"
              :disabled="saving"
            />
            <textarea
              v-else-if="field.multiline && !field.readOnly"
              v-model="form[field.key]"
              :data-test="field.key === 'customerCode' ? 'customer-code' : inventoryFieldTestId(field.key)"
              :required="fieldRequired(field)"
              :disabled="Boolean(row?.id) && ((module === 'customer' && field.key === 'customerCode') || ['username', 'skuCode', 'skuId'].includes(field.key))"
            />
            <textarea
              v-else-if="field.multiline"
              :value="readOnlyValue(field)"
              :data-test="inventoryFieldTestId(field.key)"
              disabled
            />
            <input
              v-else-if="field.readOnly"
              :data-test="field.key === 'priceDifference' ? 'price-difference' : inventoryFieldTestId(field.key)"
              :value="readOnlyValue(field)"
              :type="field.type ?? 'text'"
              disabled
              step="any"
            />
            <input
              v-else
              v-model="form[field.key]"
              :data-test="field.key === 'eanCode' ? 'product-ean-code' : field.key === 'codeSuffix' ? 'product-code-suffix' : field.key === 'customerCode' ? 'customer-code' : inventoryFieldTestId(field.key)"
              :list="field.key === 'codeSuffix' ? 'product-code-suffix-options' : undefined"
              :type="field.type ?? 'text'"
              :autocomplete="fieldAutocomplete(field)"
              :maxlength="field.key === 'eanCode' ? 12 : undefined"
              :pattern="field.key === 'eanCode' ? '69[0-9]{10}' : undefined"
              :min="field.key === 'salesMinimumOrderQuantity' ? 1 : undefined"
              :required="fieldRequired(field)"
              :disabled="(module === 'product' && ['currentCost', 'factoryPrice'].includes(field.key) && !canEditProductPrice) || (Boolean(row?.id) && ((module === 'customer' && field.key === 'customerCode') || ['username', 'skuCode', 'skuId'].includes(field.key)))"
              step="any"
              @input="field.key === 'actualQuantity' ? onInventoryMetricChange('actual') : field.key === 'availableQuantity' ? onInventoryMetricChange('available') : field.key === 'lockedQuantity' ? onInventoryMetricChange('locked') : field.key === 'inTransitQuantity' ? onInventoryMetricChange('transit') : undefined"
            />
            <datalist v-if="module === 'product' && field.key === 'codeSuffix'" id="product-code-suffix-options">
              <option v-for="rule in productCodeRules.filter(item => item.category === 'SUFFIX' && item.enabled)" :key="rule.id" :value="rule.code">{{ rule.displayName }}</option>
            </datalist>
          </label>
          </template>
        </div>
<section v-if="module === 'product' && row?.id" class="product-supplier-quotes-section">
          <h3>供应商报价</h3>
          <div data-test="product-supplier-quotes" class="product-supplier-quotes">
            <span v-for="quote in productSupplierQuotes" :key="quote.supplierId">{{ quote.supplierName }}：¥{{ supplierQuotePrice(quote.purchasePrice) }}</span>
            <span v-if="!productSupplierQuotes.length">—</span>
          </div>
          <small>供应商和采购价格请在供应商管理中维护</small>
        </section>
        <ProductImagePicker
          v-if="module === 'product'"
          v-model="pendingImages"
          :product-id="row?.id ? Number(row.id) : undefined"
          @message="(text, kind) => emit('message', text, kind)"
          @changed="emit('saved', false)"
        />
        <section v-if="module === 'inventory'" class="order-section locked-allocation-section">
          <div class="line-title"><div><h3>地点锁定分配</h3><p>按地点维护锁定数量，地点可随时新增；合计不能超过已锁定数量。</p></div><button type="button" data-test="add-locked-allocation" @click="addLockedAllocation">新增地点</button></div>
          <p v-if="!lockedAllocations.length" class="inventory-movement-empty">暂无地点分配</p>
          <div v-for="(allocation, index) in lockedAllocations" :key="index" class="locked-allocation-row" data-test="locked-allocation-row">
            <label><span>地点名称</span><input v-model="allocation.lockSource" data-test="locked-allocation-source" placeholder="例如：新加坡"></label>
            <label><span>锁定数量</span><input v-model.number="allocation.quantity" data-test="locked-allocation-quantity" type="number" min="0" step="1"></label>
            <button type="button" data-test="remove-locked-allocation" @click="removeLockedAllocation(index)">删除</button>
          </div>
        </section>        <section v-if="module === 'inventory'" class="order-section inventory-movement-section">
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
