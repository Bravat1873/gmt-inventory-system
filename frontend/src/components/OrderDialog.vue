<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { createOrder, loadContractPrice, loadOrderCustomers, loadOrderSkus, type OrderCustomer, type OrderSku, updateOrder } from '../api/workbench'
import ChineseDatePicker from './ChineseDatePicker.vue'
import FuzzyPicker, { type FuzzyPickerOption } from './FuzzyPicker.vue'

interface Line { id?: number; lineNo?: number; skuId: number | null; quantity: number; salePrice: number; shippedQuantity?: number; remainingQuantity?: number }
const props = defineProps<{ row?: Record<string, unknown>; defaultSalesperson?: string }>()
const emit = defineEmits<{ close: []; saved: []; message: [text: string, kind?: 'success' | 'error'] }>()
const today = new Date().toISOString().slice(0, 10)
const snapshotValue = (name: string, legacy?: string) => {
  const value = String(props.row?.[name] ?? '').trim()
  return value || (legacy ? String(props.row?.[legacy] ?? '') : '')
}
const form = reactive({
  customerId: Number(props.row?.customerId) || null,
  externalOrderNo: String(props.row?.externalOrderNo ?? ''),
  orderDate: String(props.row?.orderDate ?? today).slice(0, 10),
  orderType: String(props.row?.orderType ?? '工程订单'),
  status: String(props.row?.status ?? 'PENDING_CUSTOMER_PAYMENT'),
  salesperson: String(props.row?.salesperson ?? props.defaultSalesperson ?? ''),
  customerContact: snapshotValue('customerContact'),
  customerPhone: snapshotValue('customerPhone'),
  remark: String(props.row?.remark ?? ''),
  deliveryAddress: String(props.row?.deliveryAddress ?? ''),
  deliveryContact: String(props.row?.deliveryContact ?? ''),
  deliveryPhone: String(props.row?.deliveryPhone ?? ''),
  shippingMethod: String(props.row?.shippingMethod ?? ''),
  version: props.row?.version as number | undefined,
  items: (props.row?.items as Line[] | undefined)?.map(item => ({ ...item })) ?? [{ skuId: null, quantity: 1, salePrice: 0 }]
})
const contacts = reactive({
  businessContactName: snapshotValue('businessContactName'),
  businessContactPhone: snapshotValue('businessContactPhone'),
  orderContactName: snapshotValue('orderContactName', 'customerContact'),
  orderContactPhone: snapshotValue('orderContactPhone', 'customerPhone'),
  financeContactName: snapshotValue('financeContactName'),
  financeContactPhone: snapshotValue('financeContactPhone')
})
watch(() => contacts.orderContactName, value => { form.customerContact = value })
watch(() => contacts.orderContactPhone, value => { form.customerPhone = value })

const skus = ref<OrderSku[]>([])
const customers = ref<OrderCustomer[]>([])
const saving = ref(false)
const error = ref('')
const isEditing = computed(() => Boolean(props.row?.id))
const customerOptions = computed<FuzzyPickerOption[]>(() => customers.value.map(customer => ({
  id: customer.id,
  label: customer.customerName,
  searchText: [customer.customerCode, customer.customerName, customer.businessContactName, customer.businessContactPhone, customer.orderContactName, customer.orderContactPhone, customer.financeContactName, customer.financeContactPhone, customer.contactName, customer.phone].filter(Boolean).join(' ')
})))
const skuOptions = computed<FuzzyPickerOption[]>(() => skus.value.map(sku => ({ id: sku.id, label: skuLabel(sku), searchText: [sku.skuCode, sku.model, sku.productName, sku.configuration].filter(Boolean).join(' ') })))

function skuFor(line: Line) { return skus.value.find(sku => sku.id === line.skuId) }
function salesMinimum(line: Line) { return Math.max(Number(skuFor(line)?.salesMinimumOrderQuantity ?? 1), 1) }
function postOrderSupplyDemandBalance(line: Line, index: number) {
  const sku = skuFor(line)
  if (!sku) return 0
  const draftRemainder = Math.max(Number(line.quantity || 0) - Number(line.shippedQuantity || 0), 0)
  const original = (props.row?.items as Line[] | undefined)?.[index]
  const originalRemainder = isEditing.value && original?.skuId === line.skuId
    ? Math.max(Number(original.quantity || 0) - Number(original.shippedQuantity || 0), 0)
    : 0
  return sku.supplyDemandBalance + originalRemainder - draftRemainder
}
const failedImageIds = reactive(new Set<number>())
function imageIdFor(line: Line) { return Number(skuFor(line)?.primaryImageId) || null }
function imageUrlFor(line: Line) {
  const imageId = imageIdFor(line)
  return imageId && !failedImageIds.has(imageId) ? `/api/product-images/${imageId}/content` : ''
}
function markImageFailed(line: Line) {
  const imageId = imageIdFor(line)
  if (imageId) failedImageIds.add(imageId)
}
function skuLabel(sku: OrderSku) {
  const code = String(sku.skuCode ?? '').trim()
  if (code) return code
  const details = [sku.model, sku.productName, sku.configuration].map(value => String(value ?? '').trim()).filter(Boolean)
  return [...new Set(details)].join(' / ') || `未编号物料#${sku.id}`
}
async function selectCustomerById(customerId: number | null) {
  const customer = customers.value.find(item => item.id === customerId)
  if (!customer) return
  contacts.businessContactName = customer.businessContactName ?? ''
  contacts.businessContactPhone = customer.businessContactPhone ?? ''
  contacts.orderContactName = customer.orderContactName ?? ''
  contacts.orderContactPhone = customer.orderContactPhone ?? ''
  contacts.financeContactName = customer.financeContactName ?? ''
  contacts.financeContactPhone = customer.financeContactPhone ?? ''
  form.customerContact = contacts.orderContactName
  form.customerPhone = contacts.orderContactPhone
  form.deliveryAddress = customer.address ?? ''
  form.deliveryContact = contacts.orderContactName
  form.deliveryPhone = contacts.orderContactPhone
  await Promise.all(form.items.map(line => line.skuId ? applyContractPrice(line, line.skuId) : Promise.resolve()))
}
async function selectSku(index: number, skuId: number | null) {
  const line = form.items[index]
  if (!line) return
  line.skuId = skuId
  if (skuId) await applyContractPrice(line, skuId)
}
async function applyContractPrice(line: Line, skuId: number) {
  const customerId = form.customerId
  if (!customerId) return
  const price = await loadContractPrice(customerId, skuId)
  if (price != null && form.customerId === customerId && line.skuId === skuId && form.items.includes(line)) line.salePrice = price
}
function add() { form.items.push({ skuId: null, quantity: 1, salePrice: 0 }) }
function remove(index: number) { if (form.items.length > 1) form.items.splice(index, 1) }
function lineNo(index: number) { return form.items[index].lineNo ?? (index + 1) * 10000 }
function validationMessage() {
  if (!form.customerId) return '请选择客户'
  if (!form.orderDate) return '请选择订单日期'
  if (!form.orderType.trim()) return '请填写订单类型'
  if (!form.salesperson.trim()) return '请填写销售员'
  const invalidSku = form.items.findIndex(line => !line.skuId)
  if (invalidSku >= 0) return `请选择第 ${invalidSku + 1} 条明细的物料编号`
  const invalidQuantity = form.items.findIndex(line => !Number.isFinite(line.quantity) || line.quantity <= 0)
  if (invalidQuantity >= 0) return `第 ${invalidQuantity + 1} 条明细的订单数量必须大于 0`
  const belowMinimum = form.items.findIndex(line => line.skuId && line.quantity < salesMinimum(line))
  if (belowMinimum >= 0) return `第 ${belowMinimum + 1} 条明细的订单数量不能小于销售最小起订量 ${salesMinimum(form.items[belowMinimum])}`
  const invalidPrice = form.items.findIndex(line => !Number.isFinite(line.salePrice) || line.salePrice < 0)
  if (invalidPrice >= 0) return `第 ${invalidPrice + 1} 条明细的含税单价不能小于 0`
  return ''
}
function hasError(message: string) { return error.value === message }
function lineError(index: number, field: 'sku' | 'quantity' | 'price') {
  const number = index + 1
  if (field === 'sku') return hasError(`请选择第 ${number} 条明细的物料编号`)
  if (field === 'quantity') return hasError(`第 ${number} 条明细的订单数量必须大于 0`) || hasError(`第 ${number} 条明细的订单数量不能小于销售最小起订量 ${salesMinimum(form.items[index])}`)
  return hasError(`第 ${number} 条明细的含税单价不能小于 0`)
}
function quantityErrorMessage(index: number) {
  return hasError(`第 ${index + 1} 条明细的订单数量必须大于 0`) ? '数量须大于 0' : `不能小于 ${salesMinimum(form.items[index])}`

}
async function save() {
  error.value = validationMessage()
  if (error.value) { emit('message', error.value, 'error'); return }
  saving.value = true
  try {
    const payload = { ...form, ...contacts, customerContact: contacts.orderContactName, customerPhone: contacts.orderContactPhone, items: form.items.map((line, index) => ({ lineNo: lineNo(index), skuId: line.skuId, quantity: line.quantity, salePrice: line.salePrice })) }
    if (props.row?.id) await updateOrder(Number(props.row.id), payload)
    else await createOrder(payload)
    emit('message', '订单保存成功')
    emit('saved')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '订单保存失败'
    emit('message', error.value, 'error')
  } finally { saving.value = false }
}
onMounted(async () => {
  try { [skus.value, customers.value] = await Promise.all([loadOrderSkus(), loadOrderCustomers()]) }
  catch (caught) { emit('message', caught instanceof Error ? caught.message : '无法加载订单基础数据', 'error') }
})
</script>

<template>
  <div class="dialog-mask">
    <section class="dialog-card order-dialog" role="dialog" aria-modal="true">
      <header><h2>{{ isEditing ? '修改订单' : '新增订单' }}</h2><button @click="emit('close')">关闭</button></header>
      <form novalidate @submit.prevent="save">
        <section class="order-section">
          <h3>基本信息</h3>
          <div class="form-grid order-basic">
            <label><span>订单编号</span><input :value="String(row?.orderNo ?? '保存后自动生成')" disabled></label>
            <label :class="{ 'field-invalid': hasError('请选择订单日期') }"><span>订单日期 <small v-if="hasError('请选择订单日期')" data-test="date-error" class="field-error">请选择订单日期</small></span><ChineseDatePicker v-model="form.orderDate" placeholder="请选择订单日期" /></label>
            <label :class="{ 'field-invalid': hasError('请填写订单类型') }"><span>订单类型 <small v-if="hasError('请填写订单类型')" data-test="order-type-error" class="field-error">请填写订单类型</small></span><input v-model.trim="form.orderType"></label>
            <label><span>订单状态</span><select v-model="form.status"><option value="DRAFT">草稿</option><option value="PENDING_CUSTOMER_PAYMENT">确认订单并分配库存</option></select></label>
            <label :class="{ 'field-invalid': hasError('请填写销售员') }"><span>销售员 <small v-if="hasError('请填写销售员')" data-test="salesperson-error" class="field-error">请填写销售员</small></span><input v-model.trim="form.salesperson"></label>
          </div>
        </section>
        <section class="order-section">
          <h3>客户信息</h3>
          <div class="form-grid order-customer-picker-row">
            <label :class="{ 'field-invalid': hasError('请选择客户') }"><span>客户 <small v-if="hasError('请选择客户')" data-test="customer-error" class="field-error">请选择客户</small></span><FuzzyPicker data-test="order-customer-picker" v-model="form.customerId" :options="customerOptions" placeholder="输入客户名称、编码或联系人搜索" :disabled="saving" empty-text="没有匹配的客户" @update:model-value="selectCustomerById" /></label>
            <label class="wide-field"><span>备注</span><textarea v-model.trim="form.remark"></textarea></label>
          </div>
          <div class="order-contact-cards">
            <section class="order-contact-card"><h4>业务</h4><label><span>姓名</span><input data-test="business-contact-name" v-model.trim="contacts.businessContactName"></label><label><span>电话</span><input data-test="business-contact-phone" v-model.trim="contacts.businessContactPhone"></label></section>
            <section class="order-contact-card"><h4>订单</h4><label><span>姓名</span><input data-test="order-customer-contact" v-model.trim="contacts.orderContactName"></label><label><span>电话</span><input data-test="order-customer-phone" v-model.trim="contacts.orderContactPhone"></label></section>
            <section class="order-contact-card"><h4>财务</h4><label><span>姓名</span><input data-test="finance-contact-name" v-model.trim="contacts.financeContactName"></label><label><span>电话</span><input data-test="finance-contact-phone" v-model.trim="contacts.financeContactPhone"></label></section>
          </div>
        </section>
        <section class="order-section">
          <h3>收货信息</h3>
          <div class="form-grid"><label class="wide-field"><span>收货地址</span><textarea data-test="order-delivery-address" v-model.trim="form.deliveryAddress"></textarea></label><label><span>收货联系人</span><input data-test="order-delivery-contact" v-model.trim="form.deliveryContact"></label><label><span>收货联系电话</span><input data-test="order-delivery-phone" v-model.trim="form.deliveryPhone"></label><label><span>发货方式</span><input v-model.trim="form.shippingMethod" placeholder="例如：物流、快递"></label></div>
        </section>
        <section class="order-section">
          <div class="line-title"><h3>订单明细</h3><button data-test="add-order-line" type="button" @click="add">新增明细</button></div>
          <p v-if="!skus.length" class="empty-option-hint">暂无可选物料，请先导入产品或库存数据。</p>
          <div class="order-lines-scroll" data-test="order-lines-scroll"><div class="order-lines"><div v-for="(line, index) in form.items" :key="index" class="order-line order-line-full"><span class="line-number">{{ index + 1 }}</span><label :class="{ 'field-invalid': lineError(index, 'sku') }"><span>物料编号 <small v-if="lineError(index, 'sku')" :data-test="`sku-error-${index}`" class="field-error">请选择物料</small></span><FuzzyPicker :data-test="`order-sku-picker-${index}`" v-model="line.skuId" :options="skuOptions" :placeholder="skus.length ? '输入物料编号、型号或名称搜索' : '暂无可选物料，请先导入产品或库存'" :disabled="saving || !skus.length" empty-text="没有匹配的物料" @update:model-value="value => selectSku(index, value)" /></label><div class="order-product-image-cell"><span>图片</span><img v-if="imageUrlFor(line)" :data-test="`order-product-image-${index}`" :src="imageUrlFor(line)" :alt="`${skuFor(line)?.productName || skuFor(line)?.skuCode || '产品'}主图`" @error="markImageFailed(line)"><div v-else :data-test="`order-image-placeholder-${index}`" class="order-image-placeholder">暂无图片</div></div><div :data-test="`order-inventory-${index}`" class="order-inventory-cell"><span>库存</span><strong>实际 {{ skuFor(line)?.actualQuantity ?? 0 }}</strong><strong>在途 {{ skuFor(line)?.inTransitQuantity ?? 0 }}</strong><strong>待交订单 {{ skuFor(line)?.pendingDeliveryQuantity ?? 0 }}</strong><strong>销售最小起订量 {{ salesMinimum(line) }}</strong><strong :class="{ negative: (skuFor(line)?.supplyDemandBalance ?? 0) < 0 }">供需余量 {{ skuFor(line)?.supplyDemandBalance ?? 0 }}</strong><strong :class="{ negative: postOrderSupplyDemandBalance(line, index) < 0 }">下单后供需余量 {{ postOrderSupplyDemandBalance(line, index) }}</strong><small v-if="postOrderSupplyDemandBalance(line, index) < 0" class="field-error">下单后采购缺口 {{ Math.abs(postOrderSupplyDemandBalance(line, index)) }}</small></div><label><span>物料名称</span><input :value="skuFor(line)?.productName ?? ''" disabled></label><label><span>规格型号</span><input :value="[skuFor(line)?.model, skuFor(line)?.configuration].filter(Boolean).join(' / ')" disabled></label><label><span>单位</span><input :value="skuFor(line)?.unit ?? ''" disabled></label><label :class="{ 'field-invalid': lineError(index, 'quantity') }"><span>订单数量 <small v-if="lineError(index, 'quantity')" :data-test="`quantity-error-${index}`" class="field-error">{{ quantityErrorMessage(index) }}</small></span><input v-model.number="line.quantity" type="number" :min="salesMinimum(line)"></label><label><span>已发货数量</span><input :value="line.shippedQuantity ?? 0" disabled></label><label><span>剩余数量</span><input :value="line.remainingQuantity ?? line.quantity" disabled></label><label :class="{ 'field-invalid': lineError(index, 'price') }"><span>含税单价 <small v-if="lineError(index, 'price')" :data-test="`price-error-${index}`" class="field-error">单价不可为负</small></span><input v-model.number="line.salePrice" type="number" min="0" step="0.01"></label><button :data-test="`remove-order-line-${index}`" type="button" @click="remove(index)">删除</button></div></div></div>
        </section>
        <footer><button type="button" class="secondary-action" @click="emit('close')">取消操作</button><button class="primary-action" :disabled="saving">确认保存</button></footer>
      </form>
    </section>
  </div>
</template>
