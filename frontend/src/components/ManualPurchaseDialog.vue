<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  createManualPurchase,
  loadOrderSkus,
  loadProductSuppliers,
  updateManualPurchase,
  updatePurchaseHeader,
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
const selectedPurchaseInfo = ref<SupplierPurchaseInfoOption | null>(null)
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
const systemPurchase = computed(() => props.purchase?.manualEntry === false)
interface PurchaseLine { product: OrderSku; supplier: ProductSupplierOption; purchaseInfo: SupplierPurchaseInfoOption; quantity: number }
const addedLines = ref<PurchaseLine[]>([])
const initializing = ref(false)
const initializationError = ref('')
const lockedSupplier = computed(() => addedLines.value[0]?.supplier)
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
  if (!addedLines.value.length) form.expectedArrivalDate = ''
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
  if (selectedPurchaseInfo.value && !addedLines.value.length) setExpectedArrival(selectedPurchaseInfo.value.leadTimeDays)
  errors.supplier = ''
  errors.purchaseInfo = ''
}

function selectPurchaseInfo() {
  if (selectedPurchaseInfo.value) setExpectedArrival(selectedPurchaseInfo.value.leadTimeDays)
}

function validate(currentOnly = false) {
  Object.keys(errors).forEach(key => delete errors[key])
  if (systemPurchase.value) return true
  if (initializationError.value) { errors.submit = initializationError.value; return false }
  if (!currentOnly) {
    const invalid = addedLines.value.find(line => !Number.isInteger(Number(line.quantity)) || Number(line.quantity) === 0 || (line.quantity > 0 && line.quantity < line.purchaseInfo.moq))
    if (invalid) errors.items = `${productSelectedLabel(invalid.product)}：数量须为非零整数，正数不能低于最小起订量 ${invalid.purchaseInfo.moq}`
    if (addedLines.value.length && !hasCurrentLine.value) return !errors.items
  }
  if (!selectedProduct.value) errors.product = '请选择产品'
  if (!selectedSupplier.value) errors.supplier = selectedProduct.value ? '请选择该产品的供应商' : '请先选择产品'
  const quantity = Number(form.quantity)
  if (!Number.isInteger(quantity) || quantity === 0) errors.quantity = '采购数量不能为 0；退货请填写负数'
  if (selectedPurchaseInfo.value && quantity > 0 && quantity < selectedPurchaseInfo.value.moq) errors.quantity = `采购数量不能低于最小起订量 ${selectedPurchaseInfo.value.moq}`
  if (!selectedPurchaseInfo.value) errors.purchaseInfo = '请选择供应商采购信息'
  return Object.keys(errors).length === 0
}

function clearCurrentLine() {
  ++supplierRequest.value
  selectedProduct.value = null; selectedSupplier.value = null; selectedPurchaseInfo.value = null
  productQuery.value = ''; supplierQuery.value = ''; suppliers.value = []
  productOpen.value = false; supplierOpen.value = false; loadingSuppliers.value = false
  form.quantity = 1
}

function addLine() {
  if (!validate(true) || !selectedProduct.value || !selectedSupplier.value || !selectedPurchaseInfo.value) return
  addedLines.value.push({ product: selectedProduct.value, supplier: selectedSupplier.value, purchaseInfo: selectedPurchaseInfo.value, quantity: Number(form.quantity) })
  clearCurrentLine()
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
    let result: Record<string, unknown>
    if (systemPurchase.value && props.purchase) {
      result = await updatePurchaseHeader(props.purchase.id, headerData)
    } else {
      const lines = [...addedLines.value]
      if (selectedSupplier.value && selectedProduct.value && selectedPurchaseInfo.value) {
        lines.push({ product: selectedProduct.value, supplier: selectedSupplier.value, purchaseInfo: selectedPurchaseInfo.value, quantity: Number(form.quantity) })
      }
      if (!lines.length) return
      const data = {
        supplierId: lines[0].supplier.supplierId,
        items: lines.map(line => ({ skuId: line.product.id, supplierPurchaseInfoId: line.purchaseInfo.id, quantity: Number(line.quantity) })),
        ...headerData
      }
      result = props.purchase
        ? await updateManualPurchase(props.purchase.id, data)
        : await createManualPurchase(data)
    }
    emit('message', `采购单 ${String(result.purchaseNo ?? '')}${props.purchase ? ' 已修改' : ' 已创建'}`)
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
    const product = products.value.find(candidate => candidate.id === item.skuId)
    if (!product) throw new Error('采购单产品不存在或已停用，无法修改')
    const options = await loadProductSuppliers(product.id, '')
    const supplier = options.find(candidate => candidate.supplierId === purchase.supplierId)
    const purchaseInfo = supplier?.purchaseInfos.find(candidate => candidate.id === item.supplierPurchaseInfoId)
    if (!supplier || !purchaseInfo) throw new Error('原供应商采购信息已停用，无法修改')
    restored.push({ product, supplier, purchaseInfo, quantity: item.quantity })
  }
  const first = restored.shift()
  if (!first) throw new Error('采购单没有可修改的明细')
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
  if (systemPurchase.value && props.purchase) {
    form.expectedArrivalDate = props.purchase.expectedArrivalDate ?? ''
    form.deliveryAddress = props.purchase.deliveryAddress ?? ''
    form.remark = props.purchase.remark ?? ''
    return
  }
  initializing.value = true
  try { await loadProducts(); await populatePurchase() }
  catch (cause) { initializationError.value = cause instanceof Error ? cause.message : '读取采购单失败'; errors.submit = initializationError.value }
  finally { initializing.value = false }
})
</script>

<template>
  <div class="dialog-mask">
    <section class="dialog-card manual-purchase-dialog" role="dialog" aria-modal="true" aria-labelledby="manual-purchase-title">
      <header><h2 id="manual-purchase-title">{{ systemPurchase ? '修改系统采购' : (purchase ? '修改采购' : '手工采购') }}</h2><button type="button" :disabled="saving" @click="requestClose">关闭</button></header>
      <form novalidate @submit.prevent="save">
        <div class="manual-purchase-scroll"><fieldset :disabled="initializing || Boolean(initializationError)" class="manual-purchase-fields">
        <section v-if="!systemPurchase && addedLines.length" class="manual-purchase-lines" data-test="manual-purchase-lines">
          <h3>已添加 {{ addedLines.length }} 条明细 · {{ lockedSupplier?.supplierName }}</h3>
          <div v-for="(line, index) in addedLines" :key="index" class="manual-purchase-line" :data-test="`manual-line-${index}`">
            <div><strong>{{ productSelectedLabel(line.product) }}</strong><small>{{ line.product.customerPartNumber || '—' }} · {{ line.product.model || '—' }}</small><small>单价 ¥{{ line.purchaseInfo.purchasePrice }} · 最小起订量 {{ line.purchaseInfo.moq }}</small></div>
            <label><span>数量</span><input v-model.number="line.quantity" type="number" step="1" :data-test="`manual-line-quantity-${index}`" :disabled="saving"></label>
            <button type="button" class="secondary-action" :disabled="saving" :data-test="`remove-manual-line-${index}`" @click="addedLines.splice(index, 1)">移除</button>
          </div>
          <p v-if="errors.items" class="field-error" role="alert">{{ errors.items }}</p>
        </section>
        <p v-if="!systemPurchase" class="field-hint">同一采购单可添加多个产品，供应商须相同。当前填写的产品也会一并保存。</p>
        <p v-if="systemPurchase" class="field-hint system-purchase-hint">系统采购的产品、数量和供应商由采购建议锁定；可修改预计到货日期、交货地址和备注。</p>
        <div class="form-grid">
          <template v-if="!systemPurchase">
          <label class="choice-field">
            <span>产品</span>
            <input data-test="product-search" v-model="productQuery" type="search" autocomplete="off" placeholder="输入产品编号、客户料号或型号搜索" :disabled="saving" @input="productInputChanged" @focus="productOpen=true" @blur="hideLater('product')">
            <div v-if="productOpen" class="choice-options" role="listbox">
              <span v-if="loadingProducts" class="choice-empty">正在加载产品…</span>
              <button v-for="product in visibleProducts" v-else :key="product.id" :data-test="`product-option-${product.id}`" type="button" @mousedown.prevent @click="selectProduct(product)">
                <strong>{{ productOptionLabel(product) }}</strong><small>{{ product.model || '未设置型号' }}<template v-if="product.configuration"> · {{ product.configuration }}</template></small>
              </button>
              <span v-if="!loadingProducts && visibleProducts.length===0" class="choice-empty">没有匹配的产品。</span>
            </div>
            <small v-if="errors.product" class="field-error">{{ errors.product }}</small>
          </label>
          <div v-if="selectedProduct" class="purchase-supply-demand" data-test="purchase-supply-demand">
            <span>实际库存 <strong>{{ selectedProduct.actualQuantity }}</strong></span>
            <span>在途数量 <strong>{{ selectedProduct.inTransitQuantity }}</strong></span>
            <span>未发货数量 <strong>{{ selectedProduct.pendingDeliveryQuantity }}</strong></span>
            <span :class="{ negative: selectedProduct.supplyDemandSurplus < 0 }">供需余量 <strong>{{ selectedProduct.supplyDemandSurplus }}</strong></span>
            <small v-if="selectedProduct.purchaseShortageQuantity > 0" class="field-error">建议采购 {{ selectedProduct.purchaseShortageQuantity }}</small>
          </div>
          <label class="choice-field">
            <span>供应商</span>
            <input data-test="supplier-search" v-model="supplierQuery" type="search" autocomplete="off" placeholder="请先选择产品" :disabled="saving || !selectedProduct" @focus="supplierOpen=true" @blur="hideLater('supplier')" @input="supplierInputChanged">
            <div v-if="supplierOpen && selectedProduct" class="choice-options" role="listbox">
              <span v-if="loadingSuppliers" class="choice-empty">正在查询供应商…</span>
              <button v-for="supplier in visibleSuppliers" v-else :key="supplier.supplierId" :data-test="`supplier-option-${supplier.supplierId}`" type="button" @mousedown.prevent @click="selectSupplier(supplier)">
                <strong>{{ supplier.supplierName }}</strong><small>{{ supplier.supplierCode || '未设置编码' }} · 最新采购价 {{ supplier.latestPurchaseInfo.purchasePrice }} · 共 {{ supplier.purchaseInfos.length }} 条采购信息</small>
              </button>
              <span v-if="!loadingSuppliers && visibleSuppliers.length===0" class="choice-empty">该产品暂无供应商，请先到供应商管理维护供货关系。</span>
            </div>
            <small v-if="selectedPurchaseInfo" class="field-hint">最小起订量：{{ selectedPurchaseInfo.moq }}，交货天数：{{ selectedPurchaseInfo.leadTimeDays }}</small>
            <small v-if="errors.supplier" class="field-error">{{ errors.supplier }}</small>
          </label>
          <label><span>采购数量</span><input v-model.number="form.quantity" type="number" step="1" :aria-invalid="Boolean(errors.quantity)" :disabled="saving"><small v-if="errors.quantity" class="field-error">{{ errors.quantity }}</small></label>
          <label><span>采购单价</span><select data-test="purchase-price" v-model="selectedPurchaseInfo" :disabled="saving || !selectedSupplier" @change="selectPurchaseInfo"><option :value="null">请选择采购信息</option><option v-for="info in selectedSupplier?.purchaseInfos ?? []" :key="info.id" :value="info">¥{{ info.purchasePrice }}｜起订 {{ info.moq }}｜交货 {{ info.leadTimeDays }} 天｜{{ String(info.updatedAt).replace('T',' ').slice(0,16) }}</option></select><small v-if="errors.purchaseInfo" class="field-error">{{ errors.purchaseInfo }}</small></label>
          <div class="wide-field manual-purchase-entry-actions"><button type="button" class="secondary-action" data-test="add-purchase-line" :disabled="saving" @click="addLine">添加明细，继续选择产品</button><button v-if="hasCurrentLine && addedLines.length" type="button" class="secondary-action" :disabled="saving" @click="clearCurrentLine">清空当前输入</button></div>
          </template>
          <label class="date-field"><span>预计到货日期</span><div class="date-picker"><button type="button" class="date-display" :class="{ empty: !form.expectedArrivalDate }" data-test="expected-arrival-display" :disabled="saving" @click="openDatePicker"><span>{{ formattedArrivalDate() }}</span><span class="date-display-icon" aria-hidden="true"></span></button><input ref="dateInput" v-model="form.expectedArrivalDate" class="native-date-input" type="date" :disabled="saving"></div></label>
          <label class="wide-field"><span>交货地址</span><textarea v-model="form.deliveryAddress" maxlength="1000" placeholder="填写供应商送货地址" :disabled="saving"></textarea></label>
          <label><span>备注</span><textarea v-model="form.remark" maxlength="500" :disabled="saving"></textarea></label>
        </div>
        <p v-if="!systemPurchase" class="manual-purchase-total" data-test="manual-purchase-total">采购合计：¥ {{ Number(totalAmount || 0).toFixed(2) }}</p>
        </fieldset></div>
        <p v-if="errors.submit" class="form-error" role="alert">{{ errors.submit }}</p>
        <footer><button type="button" class="secondary-action" :disabled="saving" @click="requestClose">取消操作</button><button class="primary-action" :disabled="saving || initializing || Boolean(initializationError)">{{ saving ? '正在保存…' : (purchase ? '确认修改' : '确认保存') }}</button></footer>
      </form>
    </section>
  </div>
</template>

