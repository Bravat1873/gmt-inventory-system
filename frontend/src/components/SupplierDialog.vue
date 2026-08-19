<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  createSupplier,
  getSupplier,
  loadOrderSkus,
  updateSupplier,
  type OrderSku,
  type SupplierCommand,
  type SupplierProductConfig
} from '../api/workbench'
import FuzzyPicker, { type FuzzyPickerOption } from './FuzzyPicker.vue'

const props = defineProps<{ row?: Record<string, unknown> }>()
const emit = defineEmits<{ close: []; saved: []; message: [text: string, kind?: 'success' | 'error'] }>()

type ProductRow = SupplierProductConfig & {
  customerPartNumber: string
  model: string
  productCode: string
}

const form = reactive({
  manufacturerCategory: '',
  manufacturerType: '',
  supplierLocation: '',
  productAttribute: '',
  shortName: '',
  supplierName: '',
  contactName: '',
  contactTitle: '',
  phone: '',
  address: '',
  currency: '',
  taxRegistrationNo: '',
  bankAddress: '',
  bankAccount: ''
})
const products = ref<ProductRow[]>([])
const skus = ref<OrderSku[]>([])
const pickedSku = ref<number | null>(null)
const version = ref<number>()
const loading = ref(true)
const saving = ref(false)
const error = ref('')

const availableSkus = computed(() => skus.value.filter(sku => !products.value.some(item => item.skuId === sku.id)))
const availableSkuOptions = computed<FuzzyPickerOption[]>(() => availableSkus.value.map(sku => ({
  id: sku.id,
  label: labelOf(sku),
  selectedLabel: String(sku.productCode ?? '').trim() || '未设置产品编号',
  searchText: [sku.productCode, sku.customerPartNumber, sku.model].filter(Boolean).join(' ')
})))

function labelOf(sku: OrderSku) {
  return [`产品编号：${sku.productCode || '—'}`, `客户料号：${sku.customerPartNumber || '—'}`, `型号：${sku.model || '—'}`].join('\n')
}

function requestClose() {
  if (!saving.value) emit('close')
}

function addProduct() {
  const skuId = pickedSku.value
  if (skuId == null) return
  const sku = skus.value.find(item => item.id === skuId)
  if (!sku) return
  products.value.push({ skuId, customerPartNumber: sku.customerPartNumber || '', model: sku.model || '', productCode: sku.productCode || '', purchaseInfos: [blankPurchaseInfo()] })
  pickedSku.value = null
}

function removeProduct(index: number) {
  products.value.splice(index, 1)
}

function addPurchaseInfo(product: ProductRow) {
  product.purchaseInfos.push(blankPurchaseInfo())
}

function removePurchaseInfo(product: ProductRow, index: number) {
  if (product.purchaseInfos.length > 1) product.purchaseInfos.splice(index, 1)
}

function trimmedOrUndefined(value: string) {
  return value.trim() || undefined
}

function preservedOrUndefined(value: string) {
  return value === '' ? undefined : value
}

function blankPurchaseInfo() {
  return { purchasePrice: null, moq: null, leadTimeDays: null }
}

function numberOrNull(value: unknown) {
  if (value == null || value === '') return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

function invalidOptionalNumber(value: unknown, integer: boolean, minimum: number) {
  if (value == null || value === '') return false
  const number = Number(value)
  return !Number.isFinite(number) || (integer && !Number.isInteger(number)) || number < minimum
}

function payload(): SupplierCommand {
  return {
    manufacturerCategory: trimmedOrUndefined(form.manufacturerCategory),
    manufacturerType: trimmedOrUndefined(form.manufacturerType),
    supplierLocation: trimmedOrUndefined(form.supplierLocation),
    productAttribute: trimmedOrUndefined(form.productAttribute),
    shortName: trimmedOrUndefined(form.shortName),
    supplierName: form.supplierName.trim(),
    contactName: trimmedOrUndefined(form.contactName),
    contactTitle: trimmedOrUndefined(form.contactTitle),
    phone: preservedOrUndefined(form.phone),
    address: trimmedOrUndefined(form.address),
    currency: trimmedOrUndefined(form.currency),
    taxRegistrationNo: preservedOrUndefined(form.taxRegistrationNo),
    bankAddress: trimmedOrUndefined(form.bankAddress),
    bankAccount: preservedOrUndefined(form.bankAccount),
    products: products.value.map(({ skuId, purchaseInfos }) => ({
      skuId,
      purchaseInfos: purchaseInfos.map(info => ({ ...info, purchasePrice: numberOrNull(info.purchasePrice), moq: numberOrNull(info.moq), leadTimeDays: numberOrNull(info.leadTimeDays) }))
    })),
    version: version.value
  }
}

async function save() {
  if (saving.value) return
  error.value = ''
  if (!form.supplierName.trim()) {
    error.value = '请填写供应商名称'
    return
  }
  if (products.value.some(item => item.purchaseInfos.some(info => invalidOptionalNumber(info.purchasePrice, false, 0) || invalidOptionalNumber(info.moq, true, 1) || invalidOptionalNumber(info.leadTimeDays, true, 0)))) {
    error.value = '采购单价不能为负数，最小起订量须为正整数，交货天数须为非负整数'
    return
  }
  saving.value = true
  try {
    if (props.row?.id) await updateSupplier(Number(props.row.id), payload())
    else await createSupplier(payload())
    emit('message', props.row?.id ? '供应商已修改' : '供应商已新增')
    emit('saved')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存供应商失败'
  } finally {
    saving.value = false
  }
}

async function initialise() {
  try {
    skus.value = await loadOrderSkus()
    if (!props.row?.id) return
    const detail = await getSupplier(Number(props.row.id))
    form.manufacturerCategory = String(detail.manufacturerCategory ?? '')
    form.manufacturerType = String(detail.manufacturerType ?? '')
    form.supplierLocation = String(detail.supplierLocation ?? '')
    form.productAttribute = String(detail.productAttribute ?? '')
    form.shortName = String(detail.shortName ?? '')
    form.supplierName = String(detail.supplierName ?? '')
    form.contactName = String(detail.contactName ?? '')
    form.contactTitle = String(detail.contactTitle ?? '')
    form.phone = String(detail.phone ?? '')
    form.address = String(detail.address ?? '')
    form.currency = String(detail.currency ?? '')
    form.taxRegistrationNo = String(detail.taxRegistrationNo ?? '')
    form.bankAddress = String(detail.bankAddress ?? '')
    form.bankAccount = String(detail.bankAccount ?? '')
    version.value = Number(detail.version)
    const configured = Array.isArray(detail.products) ? detail.products : []
    products.value = configured.map(item => {
      const product = item as Record<string, unknown>
      const sku = skus.value.find(value => value.id === Number(product.skuId))
      return {
        skuId: Number(product.skuId),
        customerPartNumber: String(product.customerPartNumber ?? sku?.customerPartNumber ?? ''),
        model: String(product.model ?? sku?.model ?? ''),
        productCode: String(product.productCode ?? sku?.productCode ?? ''),
        purchaseInfos: ((Array.isArray(product.purchaseInfos) && product.purchaseInfos.length ? product.purchaseInfos : [{ purchasePrice: product.purchasePrice, moq: product.moq, leadTimeDays: product.leadTimeDays }])).map(value => {
          const info = value as Record<string, unknown>
          return { id: info.id == null ? undefined : Number(info.id), purchasePrice: numberOrNull(info.purchasePrice), moq: numberOrNull(info.moq), leadTimeDays: numberOrNull(info.leadTimeDays), updatedAt: info.updatedAt == null ? undefined : String(info.updatedAt), version: info.version == null ? undefined : Number(info.version) }
        })
      }
    })
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '读取供应商资料失败'
  } finally {
    loading.value = false
  }
}

onMounted(initialise)
</script>

<template>
  <div class="dialog-mask">
    <section class="dialog-card supplier-dialog" role="dialog" aria-modal="true" aria-labelledby="supplier-dialog-title">
      <header><h2 id="supplier-dialog-title">{{ row?.id ? '修改供应商' : '新增供应商' }}</h2><button type="button" :disabled="saving" @click="requestClose">关闭</button></header>
      <form novalidate @submit.prevent="save">
        <div class="form-grid supplier-profile-grid">
          <label><span>厂商分类</span><input data-test="manufacturer-category" v-model="form.manufacturerCategory" :disabled="loading || saving" placeholder="选填"></label>
          <label><span>厂商类型</span><input data-test="manufacturer-type" v-model="form.manufacturerType" :disabled="loading || saving" placeholder="选填"></label>
          <label><span>供应商地点</span><input data-test="supplier-location" v-model="form.supplierLocation" :disabled="loading || saving" placeholder="选填"></label>
          <label><span>产品属性</span><input data-test="product-attribute" v-model="form.productAttribute" :disabled="loading || saving" placeholder="选填"></label>
          <label><span>简称</span><input data-test="short-name" v-model="form.shortName" :disabled="loading || saving" placeholder="选填"></label>
          <label><span>供应商名称</span><input data-test="supplier-name" v-model="form.supplierName" :disabled="loading || saving" placeholder="请输入供应商名称" required></label>
          <label><span>联系人</span><input data-test="contact-name" v-model="form.contactName" :disabled="loading || saving" placeholder="请输入联系人"></label>
          <label><span>职称</span><input data-test="contact-title" v-model="form.contactTitle" :disabled="loading || saving" placeholder="选填"></label>
          <label><span>联系方式</span><input data-test="phone" v-model="form.phone" :disabled="loading || saving" placeholder="请输入联系电话"></label>
          <label><span>供应商地址</span><input data-test="address" v-model="form.address" :disabled="loading || saving" placeholder="选填"></label>
          <label><span>币种</span><input data-test="currency" v-model="form.currency" :disabled="loading || saving" placeholder="例如 CNY"></label>
          <label><span>税务登记号</span><input data-test="tax-registration-no" v-model="form.taxRegistrationNo" :disabled="loading || saving" placeholder="选填"></label>
          <label><span>开户地址</span><input data-test="bank-address" v-model="form.bankAddress" :disabled="loading || saving" placeholder="选填"></label>
          <label><span>开户账户</span><input data-test="bank-account" v-model="form.bankAccount" :disabled="loading || saving" placeholder="选填"></label>
        </div>

        <section class="supplier-products-section">
          <div class="supplier-products-heading"><div><h3>供应产品</h3><p>同一产品可维护多条采购单价、起订量和交货天数，采购时默认使用最新记录。</p></div><div class="supplier-product-add"><FuzzyPicker data-test="supplier-product-picker" v-model="pickedSku" :options="availableSkuOptions" placeholder="输入产品编号、客户料号或型号搜索" :disabled="loading || saving" empty-text="没有可添加的产品" /><button data-test="add-supplier-product" type="button" class="secondary-action" :disabled="pickedSku == null || saving" @click="addProduct">添加产品</button></div></div>
          <div v-if="products.length" class="supplier-product-panels" data-test="supplier-products-scroll">
            <article v-for="(product, index) in products" :key="product.skuId" class="supplier-product-panel">
              <header class="supplier-product-panel-header">
                <div class="supplier-product-identity" :data-test="`supplier-product-name-${product.skuId}`">
                  <div><span>产品编号</span><strong>{{ product.productCode || '—' }}</strong></div>
                  <div><span>客户料号</span><strong>{{ product.customerPartNumber || '—' }}</strong></div>
                  <div><span>型号</span><strong>{{ product.model || '—' }}</strong></div>
                </div>
                <div class="supplier-product-panel-actions">
                  <button type="button" class="add-purchase-info-action" :data-test="`add-purchase-info-${product.skuId}`" @click="addPurchaseInfo(product)"><span aria-hidden="true">＋</span>新增采购信息</button>
                  <button type="button" class="remove-product-action" :data-test="`remove-supplier-product-${product.skuId}`" @click="removeProduct(index)">移除产品</button>
                </div>
              </header>
              <div class="purchase-info-list">
                <div class="purchase-info-list-head" aria-hidden="true"><span>采购单价</span><span>最小起订量</span><span>交货天数</span><span>修改时间</span><span>操作</span></div>
                <div v-for="(info, infoIndex) in product.purchaseInfos" :key="info.id ?? `new-${infoIndex}`" class="purchase-info-grid" :class="{ 'purchase-info-new-row': info.id == null }" :data-test="`purchase-info-row-${product.skuId}-${infoIndex}`">
                  <label><span>采购单价</span><input v-model.number="info.purchasePrice" type="number" min="0" step="0.0001" :disabled="saving"></label>
                  <label><span>最小起订量</span><input v-model.number="info.moq" type="number" min="1" step="1" :disabled="saving"></label>
                  <label><span>交货天数</span><input v-model.number="info.leadTimeDays" type="number" min="0" step="1" :disabled="saving"></label>
                  <div class="purchase-info-updated"><span v-if="info.id == null" class="purchase-info-new-badge">待保存</span><span>{{ info.updatedAt ? String(info.updatedAt).replace('T',' ').slice(0,16) : '保存后生成' }}</span></div>
                  <div class="supplier-product-actions"><button v-if="product.purchaseInfos.length > 1" type="button" class="text-action danger" :data-test="`remove-purchase-info-${product.skuId}-${infoIndex}`" :disabled="saving" @click="removePurchaseInfo(product,infoIndex)">删除采购信息</button><span v-else class="purchase-info-required">基础采购信息</span></div>
                </div>
              </div>
            </article>
          </div>
          <p v-else class="supplier-products-empty">暂未添加供应产品。可以先保存供应商资料，后续再补充产品。</p>
        </section>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <footer><button type="button" class="secondary-action" :disabled="saving" @click="requestClose">取消操作</button><button class="primary-action" :disabled="loading || saving">{{ saving ? '正在保存…' : '确认保存' }}</button></footer>
      </form>
    </section>
  </div>
</template>

