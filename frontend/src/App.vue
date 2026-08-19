<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import LoginPage from './components/LoginPage.vue'
import { currentUser, logout, type CurrentUser } from './api/auth'
import ImportPanel from './components/ImportPanel.vue'
import ModuleListPage from './components/ModuleListPage.vue'
import DashboardPage from './components/DashboardPage.vue'
import EntityDialog from './components/EntityDialog.vue'
import CustomerDialog from './components/CustomerDialog.vue'
import CustomerFundsDialog from './components/CustomerFundsDialog.vue'
import SupplierDialog from './components/SupplierDialog.vue'
import OrderDialog from './components/OrderDialog.vue'
import ManualPurchaseDialog from './components/ManualPurchaseDialog.vue'
import PaymentDialog from './components/PaymentDialog.vue'
import PurchaseReceiptDialog from './components/PurchaseReceiptDialog.vue'
import ProcurementReviewDialog from './components/ProcurementReviewDialog.vue'
import ReceiptDialog from './components/ReceiptDialog.vue'
import ShipmentQuantityDialog from './components/ShipmentQuantityDialog.vue'
import OrderAllocationDialog from './components/OrderAllocationDialog.vue'
import BusinessTraceDialog from './components/BusinessTraceDialog.vue'
import ProductGalleryDialog from './components/ProductGalleryDialog.vue'
import ProductCodeRulesDialog from './components/ProductCodeRulesDialog.vue'
import ActionInputDialog from './components/ActionInputDialog.vue'
import AfterSalesDialog from './components/AfterSalesDialog.vue'
import AfterSalesReceiptDialog from './components/AfterSalesReceiptDialog.vue'
import AfterSalesShipmentDialog from './components/AfterSalesShipmentDialog.vue'
import AfterSalesRefundDialog from './components/AfterSalesRefundDialog.vue'
import { cancelAfterSales, loadAfterSales, type AfterSalesDetail } from './api/after-sales'
import { getOrder, loadBusinessTrace, loadOrderAllocations, loadPurchase, postAction, type BusinessTrace, type OrderAllocation, type PurchaseDetail } from './api/workbench'
import { moduleDefinitions, type ModuleKey } from './modules/module-config'
import { useGlobalDialogCloseGuard } from './composables/useUnsavedChangesGuard'

const fromAddress = new URLSearchParams(location.search).get('module') as ModuleKey | null
const activeModule = ref<ModuleKey>(moduleDefinitions.some(item => item.key === fromAddress) ? fromAddress! : 'dashboard')
const message = ref('')
const messageKind = ref<'success' | 'error'>('success')
const importOpen = ref(false)
const entityOpen = ref(false)
const customerFundsRow = ref<Record<string, unknown>>()
const supplierOpen = ref(false)
const orderOpen = ref(false)
const manualPurchaseOpen = ref(false)
const paymentOpen = ref(false)
const paymentRow = ref<Record<string, unknown>>()
const purchaseReceiptOpen = ref(false)
const purchaseReceiptOrder = ref<PurchaseDetail>()
const procurementReviewId = ref<number>()
const receiptOpen = ref(false)
const receiptRow = ref<Record<string, unknown>>()
const traceOpen = ref(false)
const businessTrace = ref<BusinessTrace | null>(null)
const productGalleryRow = ref<Record<string, unknown>>()
const productCodeRulesOpen = ref(false)
const productMenuOpen = ref(activeModule.value === 'product')
const editRow = ref<Record<string, unknown>>()
const list = ref<InstanceType<typeof ModuleListPage>>()
const actionInput = ref<{ title: string; label: string; placeholder: string; submit: (value: string) => Promise<void> } | null>(null)
const shipmentOpen = ref(false)
const shipmentOrder = ref<any>()
const allocationOpen = ref(false)
const orderAllocation = ref<OrderAllocation>()
const afterSalesOpen = ref(false)
const afterSalesReceiptOpen = ref(false)
const afterSalesShipmentOpen = ref(false)
const afterSalesRefundId = ref<number>()
const afterSalesDetail = ref<AfterSalesDetail>()
const user = ref<CurrentUser | null>(null)
const authReady = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined
useGlobalDialogCloseGuard()

const currentModule = computed(() => moduleDefinitions.find(item => item.key === activeModule.value) ?? moduleDefinitions[0])
const canUseCurrentModulePrimary = computed(() => {
  if (currentModule.value.key === 'user') return user.value?.role === 'ADMIN'
  if (currentModule.value.key === 'order') return ['ADMIN', 'USER'].includes(user.value?.role ?? 'USER')
  if (currentModule.value.importType === 'PRODUCT') return user.value?.role === 'ADMIN'
  return currentModule.value.importType !== 'COST'
    || user.value?.role === 'ADMIN' || user.value?.role === 'FINANCE'
})
const canUseCurrentModuleImport = computed(() => currentModule.value.importType !== undefined && canUseCurrentModulePrimary.value)

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
  customerFundsRow.value = undefined
  supplierOpen.value = false
  orderOpen.value = false
  manualPurchaseOpen.value = false
  paymentOpen.value = false
  paymentRow.value = undefined
  purchaseReceiptOpen.value = false
  purchaseReceiptOrder.value = undefined
  procurementReviewId.value = undefined
  receiptOpen.value = false
  allocationOpen.value = false
  orderAllocation.value = undefined
  traceOpen.value = false
  productGalleryRow.value = undefined
  actionInput.value = null
  history.pushState(null, '', `${location.pathname}?${new URLSearchParams({ module: key, page: '1' })}`)
}

function navigateFromDashboard(key: ModuleKey, keyword: string) {
  activeModule.value = key
  productCodeRulesOpen.value = false
  const params = new URLSearchParams({ module: key, page: '1' })
  if (keyword) params.set('keyword', keyword)
  history.pushState(null, '', location.pathname + '?' + params.toString())
}

function navigateModule(key: ModuleKey) {
  if (key === 'product') {
    const alreadyOnProduct = activeModule.value === 'product'
    productCodeRulesOpen.value = false
    selectModule(key)
    productMenuOpen.value = alreadyOnProduct ? !productMenuOpen.value : true
    return
  }
  productCodeRulesOpen.value = false
  selectModule(key)
}
function showMessage(text: string, kind: 'success' | 'error' = 'success') {
  if (timer) clearTimeout(timer)
  message.value = text
  messageKind.value = kind
  if (kind === 'success') timer = setTimeout(() => message.value = '', 3000)
}

function primary() {
  if (!canUseCurrentModulePrimary.value) return
  if (currentModule.value.importType && !currentModule.value.importActionLabel) { importOpen.value = true; return }
  if (activeModule.value === 'user') { editRow.value = undefined; entityOpen.value = true; return }
  if (activeModule.value === 'supplier') { editRow.value = undefined; supplierOpen.value = true; return }
  if (activeModule.value === 'order') { editRow.value = undefined; orderOpen.value = true; return }
  if (activeModule.value === 'afterSales') { afterSalesDetail.value = undefined; afterSalesOpen.value = true; return }
  if (activeModule.value === 'purchase') manualPurchaseOpen.value = true
}

function openImport() {
  if (!canUseCurrentModuleImport.value) return
  importOpen.value = true
}


function manual() {
  editRow.value = undefined
  if (activeModule.value === 'supplier') { supplierOpen.value = true; return }
  entityOpen.value = true
}

function openCustomerFunds(row: Record<string, unknown>) { customerFundsRow.value = row }

function openProductGallery(row: Record<string, unknown>) {
  if (activeModule.value === 'product') productGalleryRow.value = row
}

async function edit(row: Record<string, unknown>) {
  if (activeModule.value === 'afterSales') { try { afterSalesDetail.value=await loadAfterSales(Number(row.id)); afterSalesOpen.value=true } catch(cause){ showMessage(cause instanceof Error?cause.message:'读取售后单失败','error') }; return }
  if (activeModule.value === 'user' && user.value?.role !== 'ADMIN') return
  if (activeModule.value === 'order') {
    try { editRow.value = await getOrder(Number(row.id)); orderOpen.value = true }
    catch (cause) { showMessage(cause instanceof Error ? cause.message : '璇诲彇璁㈠崟澶辫触', 'error') }
    return
  }
  editRow.value = row
  if (activeModule.value === 'supplier') supplierOpen.value = true
  else entityOpen.value = true
}

async function openTrace(type: 'order' | 'purchase', id: number) {
  try { businessTrace.value = await loadBusinessTrace(type, id); traceOpen.value = true }
  catch (cause) { showMessage(cause instanceof Error ? cause.message : '璇诲彇涓氬姟杞ㄨ抗澶辫触', 'error') }
}

async function details(row: Record<string, unknown>) {
  const type = activeModule.value === 'finance'
    ? (row.businessType === '閲囪喘璁㈠崟' ? 'purchase' : 'order')
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
  catch (cause) { showMessage(cause instanceof Error ? cause.message : '璇诲彇璁㈠崟鏀舵淇℃伅澶辫触', 'error') }
}

function payment(row: Record<string, unknown>) {
  paymentRow.value = row
  paymentOpen.value = true
}

async function purchaseReceipt(row: Record<string, unknown>) {
  try { purchaseReceiptOrder.value = await loadPurchase(Number(row.id)); purchaseReceiptOpen.value = true }
  catch (cause) { showMessage(cause instanceof Error ? cause.message : '璇诲彇閲囪喘鏀惰揣淇℃伅澶辫触', 'error') }
}

async function allocation(row: Record<string, unknown>) {
  try { orderAllocation.value = await loadOrderAllocations(Number(row.id)); allocationOpen.value = true }
  catch (cause) { showMessage(cause instanceof Error ? cause.message : '璇诲彇搴撳瓨鍒嗛厤澶辫触', 'error') }
}
async function shipment(row: Record<string, unknown>) {
  try { shipmentOrder.value = await getOrder(Number(row.id)); shipmentOpen.value = true }
  catch (cause) { showMessage(cause instanceof Error ? cause.message : '璇诲彇璁㈠崟鍙戣揣淇℃伅澶辫触', 'error') }
}

async function openAfterSalesReceipt(row: Record<string, unknown>) { try { afterSalesDetail.value=await loadAfterSales(Number(row.id)); afterSalesReceiptOpen.value=true } catch(cause){ showMessage(cause instanceof Error?cause.message:'读取售后单失败','error') } }
async function openAfterSalesShipment(row: Record<string, unknown>) { try { afterSalesDetail.value=await loadAfterSales(Number(row.id)); afterSalesShipmentOpen.value=true } catch(cause){ showMessage(cause instanceof Error?cause.message:'读取售后单失败','error') } }
async function cancelAfterSalesRow(row: Record<string, unknown>) { if(!window.confirm('确认取消该售后单吗？'))return;try{await cancelAfterSales(Number(row.id),Number(row.version));showMessage('售后单已取消');list.value?.reload()}catch(cause){showMessage(cause instanceof Error?cause.message:'取消失败','error')} }
async function afterSalesSaved(){ afterSalesOpen.value=false;afterSalesReceiptOpen.value=false;afterSalesShipmentOpen.value=false;afterSalesDetail.value=undefined;await nextTick();list.value?.reload() }
async function workflow(row: Record<string, unknown>) {
  const status = String(row.status)
  const id = Number(row.id)
  if (status === 'DRAFT') {
    if (activeModule.value === 'purchase') {
      procurementReviewId.value = id
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
  try { await postAction(path, body); showMessage('涓氬姟鍔炵悊鎴愬姛'); list.value?.reload() }
  catch (cause) { showMessage(cause instanceof Error ? cause.message : '鍔炵悊澶辫触', 'error') }
}

async function saved(closeDialog = true) {
  if (closeDialog) entityOpen.value = false
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
    <aside class="sidebar" aria-label="主导航">
      <nav class="nav-list">
        <template v-for="item in moduleDefinitions" :key="item.key">
          <button class="nav-item" :class="{ active: activeModule === item.key }" :data-module="item.key" @click="navigateModule(item.key)">
            <span>{{ item.label }}</span><svg v-if="item.key === 'product'" class="nav-chevron" :class="{ open: productMenuOpen }" aria-hidden="true" viewBox="0 0 16 16"><path d="M4 6l4 4 4-4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" /></svg>
          </button>
          <button v-if="item.key === 'product' && productMenuOpen" class="nav-subitem" :class="{ active: productCodeRulesOpen }" @click="selectModule('product'); productCodeRulesOpen=true">产品编号规则</button>
        </template>
      </nav>
    </aside>
    <div class="current-user">{{ user.displayName }}（{{ user.username }}）<button class="text-action" @click="signOut">退出</button></div>
    <div v-if="message" class="message-bar" :class="`message-${messageKind}`" role="status"><span>{{ message }}</span><button data-test="close-message" @click="message=''">关闭</button></div>
    <main><div class="content"><DashboardPage v-if="activeModule === 'dashboard'" @navigate="navigateFromDashboard" /><ProductCodeRulesDialog v-else-if="productCodeRulesOpen" @close="productCodeRulesOpen=false" @message="showMessage" /><ModuleListPage v-else ref="list" :module="currentModule" :current-user-role="user.role" @action="primary" @import="openImport" @manual="manual" @edit="edit" @gallery="openProductGallery" @funds="openCustomerFunds" @details="details" @receipt="receipt" @payment="payment" @purchase-receipt="purchaseReceipt" @after-sales-receipt="openAfterSalesReceipt" @after-sales-shipment="openAfterSalesShipment" @after-sales-refund="row=>afterSalesRefundId=Number(row.id)" @after-sales-cancel="cancelAfterSalesRow" @shipment="shipment" @allocation="allocation" @workflow="workflow" @navigate-supplier="selectModule('supplier')" @message="showMessage" /></div></main>
    <div v-if="importOpen && currentModule.importType && canUseCurrentModuleImport" class="dialog-mask import-dialog-mask"><ImportPanel :type="currentModule.importType" :title="currentModule.importActionLabel ?? currentModule.actionLabel" @close="importOpen=false; list?.reload()" @message="showMessage" /></div>
    <CustomerDialog v-if="entityOpen && activeModule === 'customer'" :row="editRow" @close="entityOpen=false" @saved="saved" @message="showMessage" />
    <EntityDialog v-else-if="entityOpen" :module="activeModule" :row="editRow" :current-user-role="user.role" @close="entityOpen=false" @saved="saved" @message="showMessage" />
    <CustomerFundsDialog v-if="customerFundsRow" :customer="customerFundsRow" :current-user-role="user.role" @close="customerFundsRow=undefined" @changed="list?.reload()" @message="showMessage" />
    <SupplierDialog v-if="supplierOpen" :row="editRow" @close="supplierOpen=false" @saved="saved" @message="showMessage" />
    <OrderDialog v-if="orderOpen" :row="editRow" :default-salesperson="user?.displayName" @close="orderOpen=false" @saved="saved" @message="showMessage" />
    <ManualPurchaseDialog v-if="manualPurchaseOpen" @close="manualPurchaseOpen=false" @saved="saved" @message="showMessage" />
    <PaymentDialog v-if="paymentOpen && paymentRow" :purchase="paymentRow" @close="paymentOpen=false; paymentRow=undefined" @saved="saved" @message="showMessage" />
    <PurchaseReceiptDialog v-if="purchaseReceiptOpen && purchaseReceiptOrder" :purchase="purchaseReceiptOrder" @close="purchaseReceiptOpen=false; purchaseReceiptOrder=undefined" @saved="purchaseReceiptOpen=false; purchaseReceiptOrder=undefined; saved()" @message="showMessage" />
    <ProcurementReviewDialog v-if="procurementReviewId" :suggestion-id="procurementReviewId" @close="procurementReviewId=undefined" @saved="procurementReviewId=undefined; list?.reload()" @message="showMessage" />
    <ReceiptDialog v-if="receiptOpen && receiptRow" :order="receiptRow" @close="receiptOpen=false" @saved="receiptOpen=false; saved()" @message="showMessage" />
    <OrderAllocationDialog v-if="allocationOpen && orderAllocation" :allocation="orderAllocation" @close="allocationOpen=false; orderAllocation=undefined" @saved="allocationOpen=false; orderAllocation=undefined; list?.reload()" @message="showMessage" />
    <ShipmentQuantityDialog v-if="shipmentOpen && shipmentOrder" :order="shipmentOrder" @close="shipmentOpen=false" @saved="shipmentOpen=false; list?.reload()" @message="showMessage" />
    <BusinessTraceDialog v-if="traceOpen && businessTrace" :trace="businessTrace" @close="traceOpen=false" />

    <ProductGalleryDialog v-if="productGalleryRow" :product-id="Number(productGalleryRow.id)" :initial-image-id="productGalleryRow.primaryImageId ? Number(productGalleryRow.primaryImageId) : undefined" @close="productGalleryRow=undefined" />
    <AfterSalesDialog v-if="afterSalesOpen" :detail="afterSalesDetail" @close="afterSalesOpen=false" @saved="afterSalesSaved" @message="showMessage" />
    <AfterSalesReceiptDialog v-if="afterSalesReceiptOpen && afterSalesDetail" :detail="afterSalesDetail" @close="afterSalesReceiptOpen=false" @saved="afterSalesSaved" @message="showMessage" />
    <AfterSalesShipmentDialog v-if="afterSalesShipmentOpen && afterSalesDetail" :detail="afterSalesDetail" @close="afterSalesShipmentOpen=false" @saved="afterSalesSaved" @message="showMessage" />
    <AfterSalesRefundDialog v-if="afterSalesRefundId" :after-sales-id="afterSalesRefundId" @close="afterSalesRefundId=undefined" @saved="afterSalesRefundId=undefined; list?.reload()" @message="showMessage" />
    <ActionInputDialog v-if="actionInput" :title="actionInput.title" :label="actionInput.label" :placeholder="actionInput.placeholder" @close="actionInput=null" @confirm="submitActionInput" />
  </div>
</template>

