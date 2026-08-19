<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  createManualPurchase,
  loadOrderSkus,
  loadProductSuppliers,
  type OrderSku,
  type ProductSupplierOption,
  type SupplierPurchaseInfoOption
} from '../api/workbench'

const emit = defineEmits<{ close: []; saved: []; message: [text: string, kind?: 'success' | 'error'] }>()
const form = reactive({ quantity: 1, expectedArrivalDate: '', remark: '' })
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

const visibleProducts = computed(() => {
  const keyword = productQuery.value.trim().toLowerCase()
  if (!keyword) return products.value
  return products.value.filter(item => productSearchText(item).includes(keyword))
})

const visibleSuppliers = computed(() => suppliers.value)

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
  form.expectedArrivalDate = ''
  errors.product = ''
  errors.supplier = ''
  errors.purchaseInfo = ''
  await searchSuppliers()
}

function selectSupplier(supplier: ProductSupplierOption) {
  selectedSupplier.value = supplier
  selectedPurchaseInfo.value = supplier.purchaseInfos[0] ?? null
  supplierQuery.value = supplier.supplierName
  supplierOpen.value = false
  if (selectedPurchaseInfo.value) setExpectedArrival(selectedPurchaseInfo.value.leadTimeDays)
  errors.supplier = ''
  errors.purchaseInfo = ''
}

function selectPurchaseInfo() {
  if (selectedPurchaseInfo.value) setExpectedArrival(selectedPurchaseInfo.value.leadTimeDays)
}

function validate() {
  Object.keys(errors).forEach(key => delete errors[key])
  if (!selectedProduct.value) errors.product = '请选择产品'
  if (!selectedSupplier.value) errors.supplier = selectedProduct.value ? '请选择该产品的供应商' : '请先选择产品'
  const quantity = Number(form.quantity)
  if (!Number.isInteger(quantity) || quantity <= 0) errors.quantity = '采购数量必须是大于 0 的整数'
  if (selectedPurchaseInfo.value && quantity < selectedPurchaseInfo.value.moq) errors.quantity = `采购数量不能低于最小起订量 ${selectedPurchaseInfo.value.moq}`
  if (!selectedPurchaseInfo.value) errors.purchaseInfo = '请选择供应商采购信息'
  return Object.keys(errors).length === 0
}

function requestClose() { if (!saving.value) emit('close') }

async function save() {
  if (saving.value || !validate() || !selectedSupplier.value || !selectedProduct.value || !selectedPurchaseInfo.value) return
  saving.value = true
  try {
    const result = await createManualPurchase({
      supplierId: selectedSupplier.value.supplierId,
      skuId: selectedProduct.value.id,
      supplierPurchaseInfoId: selectedPurchaseInfo.value.id,
      quantity: Number(form.quantity),
      expectedArrivalDate: form.expectedArrivalDate || undefined,
      remark: form.remark.trim() || undefined
    })
    emit('message', `采购单 ${String(result.purchaseNo ?? '')} 已创建`)
    emit('saved')
  } catch (cause) {
    errors.submit = cause instanceof Error ? cause.message : '创建采购单失败'
  } finally {
    saving.value = false
  }
}

onMounted(loadProducts)
</script>

<template>
  <div class="dialog-mask">
    <section class="dialog-card" role="dialog" aria-modal="true" aria-labelledby="manual-purchase-title">
      <header><h2 id="manual-purchase-title">手工采购</h2><button type="button" :disabled="saving" @click="requestClose">关闭</button></header>
      <form novalidate @submit.prevent="save">
        <div class="form-grid">
          <label class="choice-field">
            <span>产品</span>
            <input data-test="product-search" v-model="productQuery" type="search" autocomplete="off" placeholder="输入产品编号、客户料号或型号搜索" :disabled="saving" @focus="productOpen=true" @blur="hideLater('product')">
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
            <input data-test="supplier-search" v-model="supplierQuery" type="search" autocomplete="off" placeholder="请先选择产品" :disabled="saving || !selectedProduct" @focus="supplierOpen=true" @blur="hideLater('supplier')" @input="searchSuppliers">
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
          <label><span>采购数量</span><input v-model.number="form.quantity" type="number" min="1" step="1" :aria-invalid="Boolean(errors.quantity)" :disabled="saving"><small v-if="errors.quantity" class="field-error">{{ errors.quantity }}</small></label>
          <label><span>采购单价</span><select data-test="purchase-price" v-model="selectedPurchaseInfo" :disabled="saving || !selectedSupplier" @change="selectPurchaseInfo"><option :value="null">请选择采购信息</option><option v-for="info in selectedSupplier?.purchaseInfos ?? []" :key="info.id" :value="info">¥{{ info.purchasePrice }}｜起订 {{ info.moq }}｜交货 {{ info.leadTimeDays }} 天｜{{ String(info.updatedAt).replace('T',' ').slice(0,16) }}</option></select><small v-if="errors.purchaseInfo" class="field-error">{{ errors.purchaseInfo }}</small></label>
          <label class="date-field"><span>预计到货日期</span><div class="date-picker"><button type="button" class="date-display" :class="{ empty: !form.expectedArrivalDate }" data-test="expected-arrival-display" :disabled="saving" @click="openDatePicker"><span>{{ formattedArrivalDate() }}</span><span class="date-display-icon" aria-hidden="true"></span></button><input ref="dateInput" v-model="form.expectedArrivalDate" class="native-date-input" type="date" :disabled="saving"></div></label>
          <label><span>备注</span><textarea v-model="form.remark" maxlength="500" :disabled="saving"></textarea></label>
        </div>
        <p v-if="errors.submit" class="form-error" role="alert">{{ errors.submit }}</p>
        <footer><button type="button" class="secondary-action" :disabled="saving" @click="requestClose">取消操作</button><button class="primary-action" :disabled="saving">{{ saving ? '正在保存…' : '确认保存' }}</button></footer>
      </form>
    </section>
  </div>
</template>

