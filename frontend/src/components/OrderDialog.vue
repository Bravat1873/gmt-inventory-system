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
  orderType: ['\u5de5\u7a0b\u8ba2\u5355', '\u96f6\u552e\u8ba2\u5355', '\u524d\u7f6e\u8ba2\u5355'].includes(String(props.row?.orderType ?? '')) ? String(props.row?.orderType) : '',
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
const selectedCustomer = computed(() => customers.value.find(item => item.id === form.customerId))
const orderAmount = computed(() => form.items.reduce((sum, line) => sum + Number(line.quantity || 0) * Number(line.salePrice || 0), 0))
const customerBalance = computed(() => Number(selectedCustomer.value?.fundBalance ?? 0))
const balanceCoverage = computed(() => orderAmount.value <= 0 ? 100 : customerBalance.value / orderAmount.value * 100)
const money = (value:number) => value.toLocaleString('zh-CN', { minimumFractionDigits:2, maximumFractionDigits:2 })
const customerOptions = computed<FuzzyPickerOption[]>(() => customers.value.map(customer => ({
  id: customer.id,
  label: customer.customerName,
  searchText: [customer.customerCode, customer.customerName, customer.businessContactName, customer.businessContactPhone, customer.orderContactName, customer.orderContactPhone, customer.financeContactName, customer.financeContactPhone, customer.contactName, customer.phone].filter(Boolean).join(' ')
})))
const skuOptions = computed<FuzzyPickerOption[]>(() => skus.value.map(sku => ({ id: sku.id, label: skuLabel(sku), selectedLabel: String(sku.productCode ?? '').trim() || '未设置产品编号', searchText: [sku.productCode, sku.customerPartNumber, sku.model].filter(Boolean).join(' ') })))

function skuFor(line: Line) { return skus.value.find(sku => sku.id === line.skuId) }
function postOrderSupplyDemandSurplus(line: Line, index: number) {
  const sku = skuFor(line)
  if (!sku) return 0
  const draftRemainder = Math.max(Number(line.quantity || 0) - Number(line.shippedQuantity || 0), 0)
  const original = (props.row?.items as Line[] | undefined)?.[index]
  const originalRemainder = isEditing.value && original?.skuId === line.skuId
    ? Math.max(Number(original.quantity || 0) - Number(original.shippedQuantity || 0), 0)
    : 0
  return sku.supplyDemandSurplus + originalRemainder - draftRemainder
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
  return [
    `产品编号：${String(sku.productCode ?? '').trim() || '—'}`,
    `客户料号：${String(sku.customerPartNumber ?? '').trim() || '—'}`,
    `型号：${String(sku.model ?? '').trim() || '—'}`
  ].join('\n')
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
  if (!['\u5de5\u7a0b\u8ba2\u5355', '\u96f6\u552e\u8ba2\u5355', '\u524d\u7f6e\u8ba2\u5355'].includes(form.orderType)) return '\u8bf7\u9009\u62e9\u8ba2\u5355\u7c7b\u578b'
  if (!form.salesperson.trim()) return '请填写销售员'
  const invalidSku = form.items.findIndex(line => !line.skuId)
  if (invalidSku >= 0) return `请选择第 ${invalidSku + 1} 条明细的客户料号`
  const invalidQuantity = form.items.findIndex(line => !Number.isFinite(line.quantity) || line.quantity <= 0)
  if (invalidQuantity >= 0) return `第 ${invalidQuantity + 1} 条明细的订单数量必须大于 0`
  const invalidPrice = form.items.findIndex(line => !Number.isFinite(line.salePrice) || line.salePrice < 0)
  if (invalidPrice >= 0) return `第 ${invalidPrice + 1} 条明细的含税单价不能小于 0`
  return ''
}
function hasError(message: string) { return error.value === message }
function lineError(index: number, field: 'sku' | 'quantity' | 'price') {
  const number = index + 1
  if (field === 'sku') return hasError(`请选择第 ${number} 条明细的客户料号`)
  if (field === 'quantity') return hasError(`第 ${number} 条明细的订单数量必须大于 0`)
  return hasError(`第 ${number} 条明细的含税单价不能小于 0`)
}
function quantityErrorMessage(index: number) {
  return hasError(`第 ${index + 1} 条明细的订单数量必须大于 0`) ? '数量须大于 0' : ''
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
            <label :class="{ 'field-invalid': hasError('请选择订单类型') }"><span>订单类型 <small v-if="hasError('请选择订单类型')" data-test="order-type-error" class="field-error">请选择订单类型</small></span><select v-model="form.orderType" data-test="order-type-select"><option value="">请选择订单类型</option><option value="工程订单">工程订单</option><option value="零售订单">零售订单</option><option value="前置订单">前置订单</option></select></label>
            <label :class="{ 'field-invalid': hasError('请填写销售员') }"><span>销售员 <small v-if="hasError('请填写销售员')" data-test="salesperson-error" class="field-error">请填写销售员</small></span><input v-model.trim="form.salesperson"></label>
          </div>
        </section>
        <section class="order-section">
          <h3>客户信息</h3>
<div v-if="form.customerId" class="order-fund-coverage" :class="{ insufficient: balanceCoverage < 100 }" data-test="order-fund-coverage"><span>客户可用余额 <strong>¥ {{ money(customerBalance) }}</strong></span><span>订单金额 <strong>¥ {{ money(orderAmount) }}</strong></span><span>余额覆盖比例 <strong>{{ money(balanceCoverage) }}%</strong></span><small v-if="balanceCoverage < 100">余额不足，仅作下单判断提示，仍可直接生成订单。</small></div>
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
          <p v-if="!skus.length" class="empty-option-hint">暂无可选产品，请先导入产品或库存数据。</p>
          <div class="order-lines">
            <article v-for="(line, index) in form.items" :key="index" class="order-line-panel">
              <header class="order-line-panel-header">
                <span class="order-line-index">明细 {{ index + 1 }}</span>
                <label class="order-line-product-picker" :class="{ 'field-invalid': lineError(index, 'sku') }"><span>产品 <small v-if="lineError(index, 'sku')" :data-test="`sku-error-${index}`" class="field-error">请选择产品</small></span><FuzzyPicker :data-test="`order-sku-picker-${index}`" v-model="line.skuId" :options="skuOptions" :placeholder="skus.length ? '输入产品编号、客户料号或型号搜索' : '暂无可选产品，请先导入产品或库存'" :disabled="saving || !skus.length" empty-text="没有匹配的产品" @update:model-value="value => selectSku(index, value)" /></label>
                <div class="order-product-image-cell"><span>产品图片</span><img v-if="imageUrlFor(line)" :data-test="`order-product-image-${index}`" :src="imageUrlFor(line)" :alt="`${skuFor(line)?.productName || skuFor(line)?.customerPartNumber || '产品'}主图`" @error="markImageFailed(line)"><div v-else :data-test="`order-image-placeholder-${index}`" class="order-image-placeholder">暂无图片</div></div>
                <button class="order-line-remove" :data-test="`remove-order-line-${index}`" type="button" :disabled="form.items.length === 1" @click="remove(index)">删除明细</button>
              </header>
              <div class="order-line-identity" :data-test="`order-product-identifiers-${index}`">
                <div><span>产品编号</span><strong>{{ skuFor(line)?.productCode || '—' }}</strong></div>
                <div><span>客户料号</span><strong>{{ skuFor(line)?.customerPartNumber || '—' }}</strong></div>
                <div><span>型号</span><strong>{{ skuFor(line)?.model || '—' }}</strong></div>
                <div><span>型号 / 规格</span><strong>{{ [skuFor(line)?.model, skuFor(line)?.configuration].filter(Boolean).join(' / ') || '—' }}</strong></div>
                <div><span>单位</span><strong>{{ skuFor(line)?.unit || '—' }}</strong></div>
              </div>
              <div class="order-line-fields">
                <label :class="{ 'field-invalid': lineError(index, 'quantity') }"><span>订单数量 <small v-if="lineError(index, 'quantity')" :data-test="`quantity-error-${index}`" class="field-error">{{ quantityErrorMessage(index) }}</small></span><input v-model.number="line.quantity" type="number" min="1"></label>
                <label :class="{ 'field-invalid': lineError(index, 'price') }"><span>含税单价 <small v-if="lineError(index, 'price')" :data-test="`price-error-${index}`" class="field-error">单价不可为负</small></span><input v-model.number="line.salePrice" type="number" min="0" step="0.01"></label>
                <div><span>已发货数量</span><strong>{{ line.shippedQuantity ?? 0 }}</strong></div>
                <div><span>未发货数量</span><strong>{{ line.remainingQuantity ?? line.quantity }}</strong></div>
              </div>
              <div :data-test="`order-inventory-${index}`" class="order-line-metrics">
                <div><span>实际库存</span><strong>{{ skuFor(line)?.actualQuantity ?? 0 }}</strong></div>
                <div><span>在途数量</span><strong>{{ skuFor(line)?.inTransitQuantity ?? 0 }}</strong></div>
                <div><span>全局未发货</span><strong>{{ skuFor(line)?.pendingDeliveryQuantity ?? 0 }}</strong></div>
                <div :class="{ negative: (skuFor(line)?.supplyDemandSurplus ?? 0) < 0 }"><span>供需余量</span><strong>{{ skuFor(line)?.supplyDemandSurplus ?? 0 }}</strong></div>
                <div :class="{ negative: postOrderSupplyDemandSurplus(line, index) < 0 }"><span>下单后供需余量</span><strong>{{ postOrderSupplyDemandSurplus(line, index) }}</strong><small v-if="postOrderSupplyDemandSurplus(line, index) < 0">下单后采购缺口 {{ Math.abs(postOrderSupplyDemandSurplus(line, index)) }}</small></div>
              </div>
            </article>
          </div>
        </section>
        <footer><button type="button" class="secondary-action" @click="emit('close')">取消操作</button><button class="primary-action" :disabled="saving">确认保存</button></footer>
      </form>
    </section>
  </div>
</template>


