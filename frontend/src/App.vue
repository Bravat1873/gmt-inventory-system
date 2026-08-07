<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import LoginPage from './components/LoginPage.vue'
import { currentUser, logout, type CurrentUser } from './api/auth'
import ImportPanel from './components/ImportPanel.vue'
import ModuleListPage from './components/ModuleListPage.vue'
import EntityDialog from './components/EntityDialog.vue'
import SupplierDialog from './components/SupplierDialog.vue'
import OrderDialog from './components/OrderDialog.vue'
import ManualPurchaseDialog from './components/ManualPurchaseDialog.vue'
import PaymentDialog from './components/PaymentDialog.vue'
import PurchaseReceiptDialog from './components/PurchaseReceiptDialog.vue'
import ReceiptDialog from './components/ReceiptDialog.vue'
import ShipmentQuantityDialog from './components/ShipmentQuantityDialog.vue'
import InventoryMovementDialog from './components/InventoryMovementDialog.vue'
import BusinessTraceDialog from './components/BusinessTraceDialog.vue'
import ActionInputDialog from './components/ActionInputDialog.vue'
import { getOrder, loadBusinessTrace, loadInventoryMovements, loadPurchase, postAction, type BusinessTrace, type InventoryMovement, type PurchaseDetail } from './api/workbench'
import { moduleDefinitions, type ModuleKey } from './modules/module-config'

const fromAddress = new URLSearchParams(location.search).get('module') as ModuleKey | null
const activeModule = ref<ModuleKey>(moduleDefinitions.some(item => item.key === fromAddress) ? fromAddress! : 'order')
const message = ref('')
const messageKind = ref<'success' | 'error'>('success')
const importOpen = ref(false)
const entityOpen = ref(false)
const supplierOpen = ref(false)
const orderOpen = ref(false)
const manualPurchaseOpen = ref(false)
const paymentOpen = ref(false)
const paymentRow = ref<Record<string, unknown>>()
const purchaseReceiptOpen = ref(false)
const purchaseReceiptOrder = ref<PurchaseDetail>()
const receiptOpen = ref(false)
const receiptRow = ref<Record<string, unknown>>()
const movementOpen = ref(false)
const traceOpen = ref(false)
const businessTrace = ref<BusinessTrace | null>(null)
const inventoryMovements = ref<InventoryMovement[]>([])
const editRow = ref<Record<string, unknown>>()
const list = ref<InstanceType<typeof ModuleListPage>>()
const actionInput = ref<{ title: string; label: string; placeholder: string; submit: (value: string) => Promise<void> } | null>(null)
const shipmentOpen = ref(false)
const shipmentOrder = ref<any>()
const user = ref<CurrentUser | null>(null)
const authReady = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined

const currentModule = computed(() => moduleDefinitions.find(item => item.key === activeModule.value) ?? moduleDefinitions[0])
const canUseCurrentModulePrimary = computed(() => currentModule.value.importType !== 'COST'
  || user.value?.role === 'ADMIN' || user.value?.role === 'FINANCE')

onMounted(async () => {
  try { user.value = await currentUser() } catch {} finally { authReady.value = true }
})

async function signOut() {
  try { await logout() } finally { user.value = null }
}

function selectModule(key: ModuleKey) {
  activeModule.value = key
  importOpen.value = false
  entityOpen.value = false
  supplierOpen.value = false
  orderOpen.value = false
  manualPurchaseOpen.value = false
  paymentOpen.value = false
  paymentRow.value = undefined
  purchaseReceiptOpen.value = false
  purchaseReceiptOrder.value = undefined
  receiptOpen.value = false
  movementOpen.value = false
  traceOpen.value = false
  actionInput.value = null
  history.pushState(null, '', `${location.pathname}?${new URLSearchParams({ module: key, page: '1' })}`)
}

function showMessage(text: string, kind: 'success' | 'error' = 'success') {
  if (timer) clearTimeout(timer)
  message.value = text
  messageKind.value = kind
  if (kind === 'success') timer = setTimeout(() => message.value = '', 3000)
}

function primary() {
  if (!canUseCurrentModulePrimary.value) return
  if (currentModule.value.importType) { importOpen.value = true; return }
  if (activeModule.value === 'user') { editRow.value = undefined; entityOpen.value = true; return }
  if (activeModule.value === 'supplier') { editRow.value = undefined; supplierOpen.value = true; return }
  if (activeModule.value === 'order') { editRow.value = undefined; orderOpen.value = true; return }
  if (activeModule.value === 'purchase') manualPurchaseOpen.value = true
}

function manual() {
  editRow.value = undefined
  entityOpen.value = true
}

async function edit(row: Record<string, unknown>) {
  if (activeModule.value === 'order') {
    try { editRow.value = await getOrder(Number(row.id)); orderOpen.value = true }
    catch (cause) { showMessage(cause instanceof Error ? cause.message : '读取订单失败', 'error') }
    return
  }
  editRow.value = row
  if (activeModule.value === 'supplier') supplierOpen.value = true
  else entityOpen.value = true
}

async function openTrace(type: 'order' | 'purchase', id: number) {
  try { businessTrace.value = await loadBusinessTrace(type, id); traceOpen.value = true }
  catch (cause) { showMessage(cause instanceof Error ? cause.message : '读取业务轨迹失败', 'error') }
}

async function details(row: Record<string, unknown>) {
  if (activeModule.value === 'inventory') {
    try { inventoryMovements.value = await loadInventoryMovements(Number(row.id)); movementOpen.value = true }
    catch (cause) { showMessage(cause instanceof Error ? cause.message : '读取入/出库明细失败', 'error') }
    return
  }
  const type = activeModule.value === 'finance'
    ? (row.businessType === '采购订单' ? 'purchase' : 'order')
    : activeModule.value as 'order' | 'purchase'
  await openTrace(type, Number(row.id))
}

function openActionInput(title: string, label: string, placeholder: string, submit: (value: string) => Promise<void>) {
  actionInput.value = { title, label, placeholder, submit }
}

async function submitActionInput(value: string) {
  const dialog = actionInput.value
  if (!dialog) return
  await dialog.submit(value)
  actionInput.value = null
}

async function receipt(row: Record<string, unknown>) {
  try { receiptRow.value = await getOrder(Number(row.id)); receiptOpen.value = true }
  catch (cause) { showMessage(cause instanceof Error ? cause.message : '读取订单收款信息失败', 'error') }
}

function payment(row: Record<string, unknown>) {
  paymentRow.value = row
  paymentOpen.value = true
}

async function purchaseReceipt(row: Record<string, unknown>) {
  try { purchaseReceiptOrder.value = await loadPurchase(Number(row.id)); purchaseReceiptOpen.value = true }
  catch (cause) { showMessage(cause instanceof Error ? cause.message : '读取采购收货信息失败', 'error') }
}

async function shipment(row: Record<string, unknown>) {
  try { shipmentOrder.value = await getOrder(Number(row.id)); shipmentOpen.value = true }
  catch (cause) { showMessage(cause instanceof Error ? cause.message : '读取订单发货信息失败', 'error') }
}

async function workflow(row: Record<string, unknown>) {
  const status = String(row.status)
  const id = Number(row.id)
  if (status === 'DRAFT') {
    if (activeModule.value === 'purchase') {
      if (window.confirm('确认该采购建议并创建采购单吗？')) await action(`/api/procurement/suggestions/${id}/confirm`)
      return
    }
    showMessage('草稿订单请先修改为确认订单')
    return
  }
  if (status === 'READY_TO_SHIP') {
    openActionInput('订单发货', '物流单号', '请输入物流单号', value => action(`/api/orders/${id}/ship`, { carrier: '物流', trackingNo: value }))
    return
  }
  if (status === 'PENDING_SUPPLIER_PAYMENT') { payment(row); return }
  if (status === 'EXECUTING') { await action(`/api/procurement/purchases/${id}/receive`); return }
  showMessage(status === 'WAITING_STOCK' ? '订单尚未齐货，请先生成采购' : '当前记录无需办理')
}

async function action(path: string, body: Record<string, unknown> = {}) {
  try { await postAction(path, body); showMessage('业务办理成功'); list.value?.reload() }
  catch (cause) { showMessage(cause instanceof Error ? cause.message : '办理失败', 'error') }
}

async function saved() {
  entityOpen.value = false
  supplierOpen.value = false
  orderOpen.value = false
  manualPurchaseOpen.value = false
  paymentOpen.value = false
  paymentRow.value = undefined
  await nextTick()
  list.value?.reload()
}
</script>

<template>
  <div v-if="!authReady" class="login-page">正在检查登录状态…</div>
  <LoginPage v-else-if="!user" @logged-in="user=$event" />
  <div v-else class="app-shell">
    <aside class="sidebar" aria-label="主导航"><nav class="nav-list"><button v-for="item in moduleDefinitions" :key="item.key" class="nav-item" :class="{ active: activeModule === item.key }" :data-module="item.key" @click="selectModule(item.key)">{{ item.label }}</button></nav></aside>
    <div class="current-user">{{ user.displayName }}（{{ user.username }}）<button class="text-action" @click="signOut">退出</button></div>
    <div v-if="message" class="message-bar" :class="`message-${messageKind}`" role="status"><span>{{ message }}</span><button data-test="close-message" @click="message=''">关闭</button></div>
    <main><div class="content"><ModuleListPage ref="list" :module="currentModule" :current-user-role="user.role" @action="primary" @manual="manual" @edit="edit" @details="details" @receipt="receipt" @payment="payment" @purchase-receipt="purchaseReceipt" @shipment="shipment" @workflow="workflow" @message="showMessage" /></div></main>
    <div v-if="importOpen && currentModule.importType && canUseCurrentModulePrimary" class="dialog-mask import-dialog-mask" @click.self="importOpen=false"><ImportPanel :type="currentModule.importType" :title="currentModule.actionLabel" @close="importOpen=false; list?.reload()" @message="showMessage" /></div>
    <EntityDialog v-if="entityOpen" :module="activeModule" :row="editRow" :current-user-role="user.role" @close="entityOpen=false" @saved="saved" @message="showMessage" />
    <SupplierDialog v-if="supplierOpen" :row="editRow" @close="supplierOpen=false" @saved="saved" @message="showMessage" />
    <OrderDialog v-if="orderOpen" :row="editRow" :default-salesperson="user?.displayName" @close="orderOpen=false" @saved="saved" @message="showMessage" />
    <ManualPurchaseDialog v-if="manualPurchaseOpen" @close="manualPurchaseOpen=false" @saved="saved" @message="showMessage" />
    <PaymentDialog v-if="paymentOpen && paymentRow" :purchase="paymentRow" @close="paymentOpen=false; paymentRow=undefined" @saved="saved" @message="showMessage" />
    <PurchaseReceiptDialog v-if="purchaseReceiptOpen && purchaseReceiptOrder" :purchase="purchaseReceiptOrder" @close="purchaseReceiptOpen=false; purchaseReceiptOrder=undefined" @saved="purchaseReceiptOpen=false; purchaseReceiptOrder=undefined; saved()" @message="showMessage" />
    <ReceiptDialog v-if="receiptOpen && receiptRow" :order="receiptRow" @close="receiptOpen=false" @saved="receiptOpen=false; saved()" @message="showMessage" />
    <ShipmentQuantityDialog v-if="shipmentOpen && shipmentOrder" :order="shipmentOrder" @close="shipmentOpen=false" @saved="shipmentOpen=false; list?.reload()" @message="showMessage" />
    <InventoryMovementDialog v-if="movementOpen" :movements="inventoryMovements" @close="movementOpen=false" />
    <BusinessTraceDialog v-if="traceOpen && businessTrace" :trace="businessTrace" @close="traceOpen=false" />
    <ActionInputDialog v-if="actionInput" :title="actionInput.title" :label="actionInput.label" :placeholder="actionInput.placeholder" @close="actionInput=null" @confirm="submitActionInput" />
  </div>
</template>
