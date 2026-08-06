<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  createManualPurchase,
  loadSupplierOptions,
  loadSupplierProducts,
  type SupplierOption,
  type SupplierProductOption
} from '../api/workbench'

const emit = defineEmits<{ close: []; saved: []; message: [text: string, kind?: 'success' | 'error'] }>()

const form = reactive({ quantity: 1, purchasePrice: '', expectedArrivalDate: '', remark: '' })
const suppliers = ref<SupplierOption[]>([])
const products = ref<SupplierProductOption[]>([])
const selectedSupplier = ref<SupplierOption | null>(null)
const selectedProduct = ref<SupplierProductOption | null>(null)
const supplierQuery = ref('')
const productQuery = ref('')
const supplierOpen = ref(false)
const productOpen = ref(false)
const saving = ref(false)
const loadingSuppliers = ref(false)
const loadingProducts = ref(false)
const errors = reactive<Record<string, string>>({})
const dateInput = ref<HTMLInputElement>()

const visibleSuppliers = computed(() => {
  const keyword = supplierQuery.value.trim().toLowerCase()
  if (!keyword) return suppliers.value
  return suppliers.value.filter(item => [item.supplierName, item.supplierCode, item.contactName, item.phone]
    .filter(Boolean).join(' ').toLowerCase().includes(keyword))
})

const visibleProducts = computed(() => {
  const keyword = productQuery.value.trim().toLowerCase()
  if (!keyword) return products.value
  return products.value.filter(item => productLabel(item).toLowerCase().includes(keyword))
})

function productLabel(product: SupplierProductOption) {
  const code = product.skuCode || product.model || `产品 ${product.id}`
  const name = product.productName || product.configuration || ''
  return name ? `${code} · ${name}` : code
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
  if (form.expectedArrivalDate) return
  const date = new Date()
  date.setDate(date.getDate() + days)
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  form.expectedArrivalDate = `${year}-${month}-${day}`
}

async function searchSuppliers() {
  loadingSuppliers.value = true
  try {
    suppliers.value = await loadSupplierOptions(supplierQuery.value.trim())
  } catch (cause) {
    errors.supplier = cause instanceof Error ? cause.message : '读取供应商失败'
  } finally {
    loadingSuppliers.value = false
  }
}

async function searchProducts() {
  if (!selectedSupplier.value) return
  loadingProducts.value = true
  try {
    products.value = await loadSupplierProducts(selectedSupplier.value.id, productQuery.value.trim())
  } catch (cause) {
    errors.product = cause instanceof Error ? cause.message : '读取供应产品失败'
  } finally {
    loadingProducts.value = false
  }
}

function hideLater(field: 'supplier' | 'product') {
  window.setTimeout(() => {
    if (field === 'supplier') supplierOpen.value = false
    else productOpen.value = false
  }, 120)
}

async function selectSupplier(supplier: SupplierOption) {
  selectedSupplier.value = supplier
  supplierQuery.value = supplier.supplierName
  selectedProduct.value = null
  productQuery.value = ''
  products.value = []
  supplierOpen.value = false
  errors.supplier = ''
  await searchProducts()
}

function selectProduct(product: SupplierProductOption) {
  selectedProduct.value = product
  productQuery.value = productLabel(product)
  productOpen.value = false
  form.purchasePrice = String(product.purchasePrice)
  setExpectedArrival(product.leadTimeDays)
  errors.product = ''
  errors.purchasePrice = ''
}

function validate() {
  Object.keys(errors).forEach(key => delete errors[key])
  if (!selectedSupplier.value) errors.supplier = '请选择供应商'
  if (!selectedProduct.value) errors.product = '请选择该供应商可供的产品'
  const quantity = Number(form.quantity)
  if (!Number.isInteger(quantity) || quantity <= 0) errors.quantity = '采购数量必须是大于 0 的整数'
  if (selectedProduct.value && quantity < selectedProduct.value.moq) {
    errors.quantity = `采购数量不能低于最小起订量 ${selectedProduct.value.moq}`
  }
  const price = Number(form.purchasePrice)
  if (!Number.isFinite(price) || price <= 0) errors.purchasePrice = '采购单价必须是大于 0 的有效数字'
  return Object.keys(errors).length === 0
}

function requestClose() {
  if (!saving.value) emit('close')
}

async function save() {
  if (saving.value || !validate() || !selectedSupplier.value || !selectedProduct.value) return
  saving.value = true
  try {
    const result = await createManualPurchase({
      supplierId: selectedSupplier.value.id,
      skuId: selectedProduct.value.id,
      quantity: Number(form.quantity),
      purchasePrice: Number(form.purchasePrice),
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

onMounted(searchSuppliers)
</script>

<template>
  <div class="dialog-mask" @click.self="requestClose">
    <section class="dialog-card" role="dialog" aria-modal="true" aria-labelledby="manual-purchase-title">
      <header><h2 id="manual-purchase-title">手工采购</h2><button type="button" :disabled="saving" @click="requestClose">关闭</button></header>
      <form novalidate @submit.prevent="save">
        <div class="form-grid">
          <label class="choice-field">
            <span>供应商</span>
            <input data-test="supplier-search" v-model="supplierQuery" type="search" autocomplete="off" placeholder="输入供应商名称、编码或联系人" :disabled="saving" @focus="supplierOpen=true" @blur="hideLater('supplier')" @input="searchSuppliers">
            <div v-if="supplierOpen" class="choice-options" role="listbox">
              <span v-if="loadingSuppliers" class="choice-empty">正在查询供应商…</span>
              <button v-for="supplier in visibleSuppliers" v-else :key="supplier.id" :data-test="`supplier-option-${supplier.id}`" type="button" @mousedown.prevent @click="selectSupplier(supplier)">
                <strong>{{ supplier.supplierName }}</strong><small>{{ supplier.supplierCode || '未设置编码' }}<template v-if="supplier.contactName"> · {{ supplier.contactName }}</template></small>
              </button>
              <span v-if="!loadingSuppliers && visibleSuppliers.length===0" class="choice-empty">没有匹配的供应商，请先到供应商管理维护。</span>
            </div>
            <small v-if="errors.supplier" class="field-error">{{ errors.supplier }}</small>
          </label>
          <label class="choice-field">
            <span>产品</span>
            <input data-test="product-search" v-model="productQuery" type="search" autocomplete="off" placeholder="先选择供应商，再搜索产品" :disabled="saving || !selectedSupplier" @focus="productOpen=true" @blur="hideLater('product')" @input="searchProducts">
            <div v-if="productOpen && selectedSupplier" class="choice-options" role="listbox">
              <span v-if="loadingProducts" class="choice-empty">正在查询产品…</span>
              <button v-for="product in visibleProducts" v-else :key="product.id" :data-test="`product-option-${product.id}`" type="button" @mousedown.prevent @click="selectProduct(product)">
                <strong>{{ productLabel(product) }}</strong><small>采购价 {{ product.purchasePrice }} · 起订 {{ product.moq }} · 交货 {{ product.leadTimeDays }} 天</small>
              </button>
              <span v-if="!loadingProducts && visibleProducts.length===0" class="choice-empty">该供应商暂未维护可供产品。</span>
            </div>
            <small v-if="selectedProduct" class="field-hint">最小起订量：{{ selectedProduct.moq }}，交货天数：{{ selectedProduct.leadTimeDays }}</small>
            <small v-if="errors.product" class="field-error">{{ errors.product }}</small>
          </label>
          <label><span>采购数量</span><input v-model.number="form.quantity" type="number" min="1" step="1" :aria-invalid="Boolean(errors.quantity)" :disabled="saving"><small v-if="errors.quantity" class="field-error">{{ errors.quantity }}</small></label>
          <label><span>采购单价</span><input data-test="purchase-price" v-model="form.purchasePrice" type="number" min="0.0001" step="0.0001" :aria-invalid="Boolean(errors.purchasePrice)" :disabled="saving"><small v-if="errors.purchasePrice" class="field-error">{{ errors.purchasePrice }}</small></label>
          <label class="date-field"><span>预计到货日期</span><div class="date-picker"><button type="button" class="date-display" :class="{ empty: !form.expectedArrivalDate }" data-test="expected-arrival-display" :disabled="saving" @click="openDatePicker"><span>{{ formattedArrivalDate() }}</span><span class="date-display-icon" aria-hidden="true"></span></button><input ref="dateInput" v-model="form.expectedArrivalDate" class="native-date-input" type="date" :disabled="saving"></div></label>
          <label><span>备注</span><textarea v-model="form.remark" maxlength="500" :disabled="saving"></textarea></label>
        </div>
        <p v-if="errors.submit" class="form-error" role="alert">{{ errors.submit }}</p>
        <footer><button type="button" class="secondary-action" :disabled="saving" @click="requestClose">取消操作</button><button class="primary-action" :disabled="saving">{{ saving ? '正在保存…' : '确认保存' }}</button></footer>
      </form>
    </section>
  </div>
</template>
