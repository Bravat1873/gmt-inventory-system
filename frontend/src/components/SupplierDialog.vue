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

type ProductRow = SupplierProductConfig & { label: string }

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
  searchText: [sku.skuCode, sku.model, sku.productName, sku.configuration].filter(Boolean).join(' ')
})))

function labelOf(sku: OrderSku) {
  const code = sku.skuCode || sku.model || `产品 ${sku.id}`
  return sku.productName ? `${code} · ${sku.productName}` : code
}

function requestClose() {
  if (!saving.value) emit('close')
}

function addProduct() {
  const skuId = pickedSku.value
  if (skuId == null) return
  const sku = skus.value.find(item => item.id === skuId)
  if (!sku) return
  products.value.push({ skuId, label: labelOf(sku), purchasePrice: 0, moq: 1, leadTimeDays: 0 })
  pickedSku.value = null
}

function removeProduct(index: number) {
  products.value.splice(index, 1)
}

function trimmedOrUndefined(value: string) {
  return value.trim() || undefined
}

function preservedOrUndefined(value: string) {
  return value === '' ? undefined : value
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
    products: products.value.map(({ skuId, purchasePrice, moq, leadTimeDays }) => ({ skuId, purchasePrice: Number(purchasePrice), moq: Number(moq), leadTimeDays: Number(leadTimeDays) })),
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
  if (products.value.some(item => !Number.isFinite(Number(item.purchasePrice)) || Number(item.purchasePrice) < 0 || !Number.isInteger(Number(item.moq)) || Number(item.moq) <= 0 || !Number.isInteger(Number(item.leadTimeDays)) || Number(item.leadTimeDays) < 0)) {
    error.value = '请完整填写供应产品的采购单价、最小起订量和交货天数'
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
        label: sku ? labelOf(sku) : `${String(product.skuCode ?? '产品')} · ${String(product.productName ?? '')}`,
        purchasePrice: Number(product.purchasePrice ?? 0),
        moq: Number(product.moq ?? 1),
        leadTimeDays: Number(product.leadTimeDays ?? 0)
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
          <div class="supplier-products-heading"><div><h3>供应产品</h3><p>维护该供应商可采购的产品、默认采购单价、起订量和交货天数。</p></div><div class="supplier-product-add"><FuzzyPicker data-test="supplier-product-picker" v-model="pickedSku" :options="availableSkuOptions" placeholder="输入物料编号、型号或名称搜索" :disabled="loading || saving" empty-text="没有可添加的产品" /><button data-test="add-supplier-product" type="button" class="secondary-action" :disabled="pickedSku == null || saving" @click="addProduct">添加产品</button></div></div>
          <div v-if="products.length" class="supplier-products-table-wrap"><table class="supplier-products-table"><thead><tr><th>产品</th><th>采购单价</th><th>最小起订量</th><th>交货天数</th><th>操作</th></tr></thead><tbody><tr v-for="(product,index) in products" :key="product.skuId"><td>{{ product.label }}</td><td><input v-model.number="product.purchasePrice" type="number" min="0" step="0.0001" :disabled="saving"></td><td><input v-model.number="product.moq" type="number" min="1" step="1" :disabled="saving"></td><td><input v-model.number="product.leadTimeDays" type="number" min="0" step="1" :disabled="saving"></td><td><button type="button" class="text-action" :disabled="saving" @click="removeProduct(index)">删除</button></td></tr></tbody></table></div>
          <p v-else class="supplier-products-empty">暂未添加供应产品。可以先保存供应商资料，后续再补充产品。</p>
        </section>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <footer><button type="button" class="secondary-action" :disabled="saving" @click="requestClose">取消操作</button><button class="primary-action" :disabled="loading || saving">{{ saving ? '正在保存…' : '确认保存' }}</button></footer>
      </form>
    </section>
  </div>
</template>
