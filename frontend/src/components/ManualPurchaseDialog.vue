<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  createManualPurchase,
  loadOrderSkus,
  loadProductSuppliers,
  updateManualPurchase,
  type OrderSku,
  type PurchaseDetail,
  type ProductSupplierOption,
  type SupplierPurchaseInfoOption
} from '../api/workbench'

const props = defineProps<{ purchase?: PurchaseDetail }>()
const emit = defineEmits<{ close: []; saved: []; message: [text: string, kind?: 'success' | 'error'] }>()
const form = reactive({ quantity: 1, expectedArrivalDate: '', deliveryAddress: '', remark: '' })
const products = ref<OrderSku[]>([])
const suppliers = ref<ProductSupplierOption[]>([])
const selectedProduct = ref<OrderSku | null>(null)
const selectedSupplier = ref<ProductSupplierOption | null>(null)
const selectedPurchaseInfo = ref<PurchaseInfo | null>(null)
const productQuery = ref('')
const supplierQuery = ref('')
const productOpen = ref(false)
const supplierOpen = ref(false)
const saving = ref(false)
const loadingProducts = ref(false)
const loadingSuppliers = ref(false)
const supplierRequest = ref(0)
const errors = reactive<Record<string, string>>({})
const dateInput = ref<HTMLInputElement>()
type PurchaseInfo = SupplierPurchaseInfoOption & { snapshot?: boolean }
const currentOriginal = ref<PurchaseLine | null>(null)
const originalSupplier = ref<ProductSupplierOption | null>(null)
interface PurchaseLine { id?: number; receivedQuantity?: number; product: OrderSku; supplier: ProductSupplierOption; purchaseInfo: PurchaseInfo; quantity: number }
const addedLines = ref<PurchaseLine[]>([])
const initializing = ref(false)
const initializationError = ref('')
const lockedSupplier = computed(() => props.purchase?.supplierLocked ? originalSupplier.value : addedLines.value[0]?.supplier)
const hasCurrentLine = computed(() => Boolean(selectedProduct.value || productQuery.value.trim()))
const totalAmount = computed(() => addedLines.value.reduce((sum, line) => sum + line.quantity * line.purchaseInfo.purchasePrice, 0)
  + (selectedPurchaseInfo.value ? Number(form.quantity) * selectedPurchaseInfo.value.purchasePrice : 0))

const visibleProducts = computed(() => {
  const keyword = productQuery.value.trim().toLowerCase()
  if (!keyword) return products.value
  return products.value.filter(item => productSearchText(item).includes(keyword))
})

const visibleSuppliers = computed(() => suppliers.value.filter(supplier => !lockedSupplier.value || supplier.supplierId === lockedSupplier.value.supplierId))

function productOptionLabel(product: OrderSku) {
  return [`产品编号：${product.productCode || '—'}`, `客户料号：${product.customerPartNumber || '—'}`, `型号：${product.model || '—'}`].join('\n')
}

function productSelectedLabel(product: OrderSku) {
  return String(product.productCode ?? '').trim() || '未设置产品编号'
}

function productSearchText(product: OrderSku) {
  return [product.productCode, product.customerPartNumber, product.model]
    .filter(Boolean).join(' ').toLowerCase()
}

function formattedArrivalDate() {
  if (!form.expectedArrivalDate) return '请选择预计到货日期'
  const [year, month, day] = form.expectedArrivalDate.split('-').map(Number)
  return `${year}年${month}月${day}日`
}

function openDatePicker() {
  const input = dateInput.value
  if (!input || saving.value) return
  if (typeof input.showPicker === 'function') input.showPicker()
  else input.focus()
}

function setExpectedArrival(days: number) {
  const date = new Date()
  date.setDate(date.getDate() + days)
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  form.expectedArrivalDate = `${year}-${month}-${day}`
}

async function loadProducts() {
  loadingProducts.value = true
  try {
    products.value = await loadOrderSkus()
  } catch (cause) {
    errors.product = cause instanceof Error ? cause.message : '读取产品失败'
  } finally {
    loadingProducts.value = false
  }
}

async function searchSuppliers() {
  if (!selectedProduct.value) return
  const request = ++supplierRequest.value
  loadingSuppliers.value = true
  try {
    const result = await loadProductSuppliers(selectedProduct.value.id, supplierQuery.value.trim())
    if (request === supplierRequest.value) suppliers.value = result
  } catch (cause) {
    if (request === supplierRequest.value) errors.supplier = cause instanceof Error ? cause.message : '读取供应商失败'
  } finally {
    if (request === supplierRequest.value) loadingSuppliers.value = false
  }
}

function hideLater(field: 'product' | 'supplier') {
  window.setTimeout(() => {
    if (field === 'product') productOpen.value = false
    else supplierOpen.value = false
  }, 120)
}

async function selectProduct(product: OrderSku) {
  selectedProduct.value = product
  productQuery.value = productSelectedLabel(product)
  productOpen.value = false
  selectedSupplier.value = null
  supplierQuery.value = ''
  suppliers.value = []
  selectedPurchaseInfo.value = null
  if (!props.purchase && !addedLines.value.length) form.expectedArrivalDate = ''
  errors.product = ''
  errors.supplier = ''
  errors.purchaseInfo = ''
  await searchSuppliers()
  if (selectedProduct.value?.id !== product.id) return
  if (lockedSupplier.value) {
    const supplier = suppliers.value.find(item => item.supplierId === lockedSupplier.value?.supplierId)
    if (supplier) selectSupplier(supplier)
    else errors.supplier = `该产品没有 ${lockedSupplier.value.supplierName} 的有效采购信息，请选择其他产品或单独开单`
  }
}

function selectSupplier(supplier: ProductSupplierOption) {
  if (lockedSupplier.value && supplier.supplierId !== lockedSupplier.value.supplierId) return
  selectedSupplier.value = supplier
  selectedPurchaseInfo.value = supplier.purchaseInfos[0] ?? null
  supplierQuery.value = supplier.supplierName
  supplierOpen.value = false
  if (!props.purchase && selectedPurchaseInfo.value && !addedLines.value.length) setExpectedArrival(selectedPurchaseInfo.value.leadTimeDays)
  errors.supplier = ''
  errors.purchaseInfo = ''
}

function selectPurchaseInfo() {
  if (!props.purchase && selectedPurchaseInfo.value) setExpectedArrival(selectedPurchaseInfo.value.leadTimeDays)
}

function validate(currentOnly = false) {
  Object.keys(errors).forEach(key => delete errors[key])
  if (initializationError.value) { errors.submit = initializationError.value; return false }
  if (!currentOnly) {
    const invalid = addedLines.value.find(line => !validQuantity(line.quantity, line.purchaseInfo, line.receivedQuantity))
    if (invalid) errors.items = `${productSelectedLabel(invalid.product)}：数量须为整数且不能小于已收货数量；新采购价的最小起订量为 ${invalid.purchaseInfo.moq}`
    if (addedLines.value.length && !hasCurrentLine.value) return !errors.items
  }
  if (!selectedProduct.value) errors.product = '请选择产品'
  if (!selectedSupplier.value) errors.supplier = selectedProduct.value ? '请选择该产品的供应商' : '请先选择产品'
  const quantity = form.quantity
  if (!Number.isInteger(quantity)) errors.quantity = '采购数量须为整数，可填写 0；退货请填写负数'
  if (selectedPurchaseInfo.value && !selectedPurchaseInfo.value.snapshot && quantity > 0 && quantity < selectedPurchaseInfo.value.moq) errors.quantity = `采购数量不能低于最小起订量 ${selectedPurchaseInfo.value.moq}`
  if ((currentOriginal.value?.receivedQuantity ?? 0) > 0 && quantity < currentOriginal.value!.receivedQuantity!) errors.quantity = `采购数量不能小于已收货数量 ${currentOriginal.value!.receivedQuantity}`
  if (!selectedPurchaseInfo.value) errors.purchaseInfo = '请选择供应商采购信息'
  return Object.keys(errors).length === 0
}

function clearCurrentLine() {
  currentOriginal.value = null
  ++supplierRequest.value
  selectedProduct.value = null; selectedSupplier.value = null; selectedPurchaseInfo.value = null
  productQuery.value = ''; supplierQuery.value = ''; suppliers.value = []
  productOpen.value = false; supplierOpen.value = false; loadingSuppliers.value = false
  form.quantity = 1
}

function addLine() {
  if (!validate(true) || !selectedProduct.value || !selectedSupplier.value || !selectedPurchaseInfo.value) return
  addedLines.value.push({ id: currentOriginal.value?.id, receivedQuantity: currentOriginal.value?.receivedQuantity, product: selectedProduct.value, supplier: selectedSupplier.value, purchaseInfo: selectedPurchaseInfo.value, quantity: Number(form.quantity) })
  clearCurrentLine()
}

function validQuantity(quantity: number, info: PurchaseInfo, received = 0) {
  return Number.isInteger(quantity) && (received <= 0 || quantity >= received)
    && (info.snapshot || quantity <= 0 || quantity >= info.moq)
}

function editLine(index: number) {
  if (hasCurrentLine.value) {
    if (!validate(true)) return
    addLine()
  }
  const line = addedLines.value.splice(index, 1)[0]
  currentOriginal.value = line
  selectedProduct.value = line.product; productQuery.value = productSelectedLabel(line.product)
  selectedSupplier.value = line.supplier; supplierQuery.value = line.supplier.supplierName
  suppliers.value = [line.supplier]; selectedPurchaseInfo.value = line.purchaseInfo
  form.quantity = line.quantity
}

function productInputChanged() {
  selectedProduct.value = null; selectedSupplier.value = null; selectedPurchaseInfo.value = null
  supplierQuery.value = ''; suppliers.value = []; ++supplierRequest.value
  loadingSuppliers.value = false
}

function supplierInputChanged() {
  selectedSupplier.value = null; selectedPurchaseInfo.value = null
  void searchSuppliers()
}

function requestClose() { if (!saving.value) emit('close') }

async function save() {
  if (saving.value || initializing.value || !validate()) return
  saving.value = true
  try {
    const headerData = {
      expectedArrivalDate: form.expectedArrivalDate || undefined,
      deliveryAddress: form.deliveryAddress.trim() || undefined,
      remark: form.remark.trim() || undefined
    }
    const lines = [...addedLines.value]
    if (selectedSupplier.value && selectedProduct.value && selectedPurchaseInfo.value) {
      lines.push({ id: currentOriginal.value?.id, receivedQuantity: currentOriginal.value?.receivedQuantity, product: selectedProduct.value, supplier: selectedSupplier.value, purchaseInfo: selectedPurchaseInfo.value, quantity: Number(form.quantity) })
    }
    if (!lines.length) return
    const data = {
      supplierId: lines[0].supplier.supplierId,
      ...(props.purchase ? { version: props.purchase.version } : {}),
      items: lines.map(line => ({ ...(line.id ? { id: line.id, retainPrice: line.purchaseInfo.snapshot === true } : {}), skuId: line.product.id, supplierPurchaseInfoId: line.purchaseInfo.id, quantity: Number(line.quantity) })),
      ...headerData
    }
    const result = props.purchase
      ? await updateManualPurchase(props.purchase.id, data)
      : await createManualPurchase(data)
    const draft = result.status === 'DRAFT'
    emit('message', `采购单 ${String(result.purchaseNo ?? '')}${draft ? (props.purchase ? ' 草稿已修改，请在列表复核' : ' 已保存为草稿，请在列表复核') : (props.purchase ? ' 已修改' : ' 已创建')}`)
    emit('saved')
  } catch (cause) {
    errors.submit = cause instanceof Error ? cause.message : '创建采购单失败'
  } finally {
    saving.value = false
  }
}

async function populatePurchase() {
  const purchase = props.purchase
  if (!purchase) return
  const restored: PurchaseLine[] = []
  for (const item of purchase.items) {
    const product = products.value.find(candidate => candidate.id === item.skuId) ?? {
      ...item, id: item.skuId!, actualQuantity: 0, availableQuantity: 0, inTransitQuantity: 0,
      pendingDeliveryQuantity: 0, supplyDemandSurplus: 0, purchaseShortageQuantity: 0, salesMinimumOrderQuantity: 1
    }
    const options = await loadProductSuppliers(product.id, '')
    const activeSupplier = options.find(candidate => candidate.supplierId === purchase.supplierId)
    const oldQuote = activeSupplier?.purchaseInfos.find(candidate => candidate.id === item.supplierPurchaseInfoId)
    const purchaseInfo: PurchaseInfo = { id: item.supplierPurchaseInfoId ?? 0, purchasePrice: Number(item.purchasePrice ?? oldQuote?.purchasePrice ?? 0), moq: 0, leadTimeDays: 0, updatedAt: '原单价', snapshot: true }
    const supplier: ProductSupplierOption = {
      ...(activeSupplier ?? { id: purchase.supplierId!, supplierId: purchase.supplierId!, supplierName: purchase.supplierName }),
      purchaseInfos: [purchaseInfo, ...(activeSupplier?.purchaseInfos ?? [])], latestPurchaseInfo: activeSupplier?.latestPurchaseInfo ?? purchaseInfo
    }
    originalSupplier.value ??= supplier
    restored.push({ id: item.id, receivedQuantity: item.receivedQuantity, product, supplier, purchaseInfo, quantity: item.quantity })
  }
  const first = restored.shift()
  if (!first) throw new Error('采购单没有可修改的明细')
  currentOriginal.value = first
  selectedProduct.value = first.product
  productQuery.value = productSelectedLabel(first.product)
  selectedSupplier.value = first.supplier
  selectedPurchaseInfo.value = first.purchaseInfo
  suppliers.value = [first.supplier]
  supplierQuery.value = first.supplier.supplierName
  form.quantity = first.quantity
  if (restored.length) {
    addedLines.value = [first, ...restored]
    clearCurrentLine()
  }
  form.expectedArrivalDate = purchase.expectedArrivalDate ?? ''
  form.deliveryAddress = purchase.deliveryAddress ?? ''
  form.remark = purchase.remark ?? ''
}

onMounted(async () => {
  initializing.value = true
  try { await loadProducts(); await populatePurchase() }
  catch (cause) { initializationError.value = cause instanceof Error ? cause.message : '读取采购单失败'; errors.submit = initializationError.value }
  finally { initializing.value = false }
})
</script>

<template>
  <div class="dialog-mask">
    <section class="dialog-card manual-purchase-dialog" role="dialog" aria-modal="true" aria-labelledby="manual-purchase-title">
      <header><h2 id="manual-purchase-title">{{ purchase ? '修改采购' : '新增采购单' }}</h2><button type="button" :disabled="saving" @click="requestClose">关闭</button></header>
      <form novalidate @submit.prevent="save">
        <div class="manual-purchase-scroll"><fieldset :disabled="initializing || Boolean(initializationError)" class="manual-purchase-fields">
        <section v-if="addedLines.length" class="manual-purchase-lines" data-test="manual-purchase-lines">
          <h3>已添加 {{ addedLines.length }} 条明细 · {{ lockedSupplier?.supplierName }}</h3>
          <div v-for="(line, index) in addedLines" :key="index" class="manual-purchase-line" :data-test="`manual-line-${index}`">
            <div><strong>{{ productSelectedLabel(line.product) }}</strong><small>{{ line.product.customerPartNumber || '—' }} · {{ line.product.model || '—' }}</small><small>单价 ¥{{ line.purchaseInfo.purchasePrice }} · 已收货 {{ Math.max(0, line.receivedQuantity ?? 0) }}</small></div>
            <label><span>数量</span><input v-model.number="line.quantity" @input="delete errors.items" type="number" step="1" :data-test="`manual-line-quantity-${index}`" :disabled="saving"></label>
            <div class="manual-purchase-line-actions"><button type="button" class="secondary-action" :disabled="saving" :data-test="`edit-manual-line-${index}`" @click="editLine(index)">修改明细</button>
            <button type="button" class="secondary-action" :disabled="saving || (line.receivedQuantity ?? 0) > 0" :data-test="`remove-manual-line-${index}`" @click="addedLines.splice(index, 1)">移除</button></div>
          </div>
          <p v-if="errors.items" class="field-error" role="alert">{{ errors.items }}</p>
        </section>
        <p class="field-hint">同一采购单可添加多个产品，供应商须相同。数量可填 0，零数量不计金额或在途；新增采购单先保存为草稿，复核后才计入在途。</p>
        <p v-if="purchase" class="field-hint">修改后会重新计算采购金额、未收货在途量及业务进度。已收货明细不能删除或换产品，数量不能小于已收货数量；历史收货和付款记录保留。</p>
        <p v-if="purchase?.supplierLocked" class="field-hint">已有付款、收货或发票记录，供应商保持不变。</p>
        <div class="form-grid">
          <label class="choice-field">
            <span>产品</span>
            <input data-test="product-search" v-model="productQuery" type="search" autocomplete="off" placeholder="输入产品编号、客户料号或型号搜索" :disabled="saving || (currentOriginal?.receivedQuantity ?? 0) > 0" @input="productInputChanged" @focus="productOpen=true" @blur="hideLater('product')">
            <div v-if="productOpen" class="choice-options" role="listbox">
              <span v-if="loadingProducts" class="choice-empty">正在加载产品…</span>
              <button v-for="product in visibleProducts" v-else :key="product.id" :data-test="`product-option-${product.id}`" type="button" @mousedown.prevent @click="selectProduct(product)">
                <strong>{{ productOptionLabel(product) }}</strong><small>{{ product.model || '未设置型号' }}<template v-if="product.configuration"> · {{ product.configuration }}</template></small>
              </button>
              <span v-if="!loadingProducts && visibleProducts.length===0" class="choice-empty">没有匹配的产品。</span>
            </div>
            <small v-if="errors.product" class="field-error">{{ errors.product }}</small>
          </label>
          <div v-if="selectedProduct && products.some(product => product.id === selectedProduct?.id)" class="purchase-supply-demand" data-test="purchase-supply-demand">
            <span>实际库存 <strong>{{ selectedProduct.actualQuantity }}</strong></span>
            <span>在途数量 <strong>{{ selectedProduct.inTransitQuantity }}</strong></span>
            <span>未发货数量 <strong>{{ selectedProduct.pendingDeliveryQuantity }}</strong></span>
            <span :class="{ negative: selectedProduct.supplyDemandSurplus < 0 }">供需余量 <strong>{{ selectedProduct.supplyDemandSurplus }}</strong></span>
            <small v-if="selectedProduct.purchaseShortageQuantity > 0" class="field-error">建议采购 {{ selectedProduct.purchaseShortageQuantity }}</small>
          </div>
          <label class="choice-field">
            <span>供应商</span>
            <input data-test="supplier-search" v-model="supplierQuery" type="search" autocomplete="off" placeholder="请先选择产品" :disabled="saving || !selectedProduct || Boolean(purchase?.supplierLocked)" @focus="supplierOpen=true" @blur="hideLater('supplier')" @input="supplierInputChanged">
            <div v-if="supplierOpen && selectedProduct" class="choice-options" role="listbox">
              <span v-if="loadingSuppliers" class="choice-empty">正在查询供应商…</span>
              <button v-for="supplier in visibleSuppliers" v-else :key="supplier.supplierId" :data-test="`supplier-option-${supplier.supplierId}`" type="button" @mousedown.prevent @click="selectSupplier(supplier)">
                <strong>{{ supplier.supplierName }}</strong><small>{{ supplier.supplierCode || '未设置编码' }} · 最新采购价 {{ supplier.latestPurchaseInfo.purchasePrice }} · 共 {{ supplier.purchaseInfos.length }} 条采购信息</small>
              </button>
              <span v-if="!loadingSuppliers && visibleSuppliers.length===0" class="choice-empty">该产品暂无供应商，请先到供应商管理维护供货关系。</span>
            </div>
            <small v-if="selectedPurchaseInfo && !selectedPurchaseInfo.snapshot" class="field-hint">最小起订量：{{ selectedPurchaseInfo.moq }}，交货天数：{{ selectedPurchaseInfo.leadTimeDays }}</small>
            <small v-if="selectedPurchaseInfo?.snapshot" class="field-hint">当前保留原采购单价，可选择有效报价调整。</small>
            <small v-if="errors.supplier" class="field-error">{{ errors.supplier }}</small>
          </label>
          <label><span>采购数量</span><input v-model.number="form.quantity" @input="delete errors.quantity" type="number" step="1" :aria-invalid="Boolean(errors.quantity)" :disabled="saving"><small v-if="(currentOriginal?.receivedQuantity ?? 0) > 0" class="field-hint">已收货 {{ currentOriginal?.receivedQuantity }}，采购数量不能低于此值。</small><small v-if="errors.quantity" class="field-error">{{ errors.quantity }}</small></label>
          <label><span>采购单价</span><select data-test="purchase-price" v-model="selectedPurchaseInfo" :disabled="saving || !selectedSupplier" @change="selectPurchaseInfo"><option :value="null">请选择采购信息</option><option v-for="(info, index) in selectedSupplier?.purchaseInfos ?? []" :key="index" :value="info">{{ info.updatedAt === '原单价' ? `原采购单价 ¥${info.purchasePrice}` : `¥${info.purchasePrice}｜起订 ${info.moq}｜交货 ${info.leadTimeDays} 天｜${String(info.updatedAt).replace('T',' ').slice(0,16)}` }}</option></select><small v-if="errors.purchaseInfo" class="field-error">{{ errors.purchaseInfo }}</small></label>
          <div class="wide-field manual-purchase-entry-actions"><button type="button" class="secondary-action" data-test="add-purchase-line" :disabled="saving" @click="addLine">添加明细，继续选择产品</button><button v-if="hasCurrentLine && addedLines.length" type="button" class="secondary-action" :disabled="saving || (currentOriginal?.receivedQuantity ?? 0) > 0" @click="clearCurrentLine">清空当前输入</button></div>
          <label class="date-field"><span>预计到货日期</span><div class="date-picker"><button type="button" class="date-display" :class="{ empty: !form.expectedArrivalDate }" data-test="expected-arrival-display" :disabled="saving" @click="openDatePicker"><span>{{ formattedArrivalDate() }}</span><span class="date-display-icon" aria-hidden="true"></span></button><input ref="dateInput" v-model="form.expectedArrivalDate" class="native-date-input" type="date" :disabled="saving"></div></label>
          <label class="wide-field"><span>交货地址</span><textarea v-model="form.deliveryAddress" maxlength="1000" placeholder="填写供应商送货地址" :disabled="saving"></textarea></label>
          <label><span>备注</span><textarea v-model="form.remark" maxlength="500" :disabled="saving"></textarea></label>
        </div>
        <p class="manual-purchase-total" data-test="manual-purchase-total">采购合计：¥ {{ Number(totalAmount || 0).toFixed(2) }}</p>
        <p v-if="purchase && Number(purchase.paidAmount ?? 0) > totalAmount" class="field-error" data-test="purchase-overpaid">已确认付款 ¥{{ Number(purchase.paidAmount).toFixed(2) }}，比修改后的采购金额多 ¥{{ (Number(purchase.paidAmount) - totalAmount).toFixed(2) }}。保存后保留付款记录，差额需后续处理。</p>
        </fieldset></div>
        <p v-if="errors.submit" class="form-error" role="alert">{{ errors.submit }}</p>
        <footer><button type="button" class="secondary-action" :disabled="saving" @click="requestClose">取消操作</button><button class="primary-action" :disabled="saving || initializing || Boolean(initializationError)">{{ saving ? '正在保存…' : (purchase ? '确认修改' : '确认保存') }}</button></footer>
      </form>
    </section>
  </div>
</template>

