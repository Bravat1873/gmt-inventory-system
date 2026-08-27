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
import FinanceReviewDialog from './components/FinanceReviewDialog.vue'
import InvoiceDialog from './components/InvoiceDialog.vue'
import ShipmentQuantityDialog from './components/ShipmentQuantityDialog.vue'
import OrderDocumentExportDialog from './components/OrderDocumentExportDialog.vue'
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
import { deleteOrder, downloadExcelExport, getOrder, loadBusinessTrace, loadOrderAllocations, loadPurchase, postAction, reviewOrder, type BusinessTrace, type OrderAllocation, type PurchaseDetail } from './api/workbench'
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
const editingPurchase = ref<PurchaseDetail>()
const paymentOpen = ref(false)
const paymentRow = ref<Record<string, unknown>>()
const purchaseReceiptOpen = ref(false)
const purchaseReceiptOrder = ref<PurchaseDetail>()
const procurementReviewId = ref<number>()
const receiptOpen = ref(false)
const receiptRow = ref<Record<string, unknown>>()
const financeReviewRow = ref<Record<string, unknown>>()
const invoiceContext = ref<{ type: 'SALES' | 'PURCHASE'; businessId: number; businessNo: string }>()
const traceOpen = ref(false)
const businessTrace = ref<BusinessTrace | null>(null)
const productGalleryRow = ref<Record<string, unknown>>()
const productCodeRulesOpen = ref(false)
const productMenuOpen = ref(activeModule.value === 'product')
const editRow = ref<Record<string, unknown>>()
const list = ref<InstanceType<typeof ModuleListPage>>()
const actionInput = ref<{ title: string; label: string; placeholder: string; submit: (value: string) => Promise<void> } | null>(null)
const shipmentOpen = ref(false)
const documentExportOrder = ref<Record<string, unknown>>()
const documentExportShipments = computed(() => Array.isArray(documentExportOrder.value?.shipments) ? documentExportOrder.value.shipments : [])
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
const canWriteFinance = computed(() => ['ADMIN', 'FINANCE'].includes(user.value?.role ?? 'USER'))
const canMaintainInvoices = computed(() => user.value?.role === 'ADMIN')

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
  editingPurchase.value = undefined
  paymentOpen.value = false
  paymentRow.value = undefined
  purchaseReceiptOpen.value = false
  purchaseReceiptOrder.value = undefined
  procurementReviewId.value = undefined
  receiptOpen.value = false
  financeReviewRow.value = undefined
  invoiceContext.value = undefined
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
  if (activeModule.value === 'purchase') {
    editingPurchase.value = undefined
    manualPurchaseOpen.value = true
  }
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
  if (activeModule.value === 'purchase') {
      try {
        editingPurchase.value = await loadPurchase(Number(row.id))
        manualPurchaseOpen.value = true
    } catch (cause) { showMessage(cause instanceof Error ? cause.message : '读取采购单失败', 'error') }
    return
  }
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
    ? (String(row.cashDirection) === 'PAYABLE' ? 'purchase' : 'order')
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
  if (activeModule.value === 'finance' && !canWriteFinance.value) return
  try { receiptRow.value = await getOrder(Number(row.id)); receiptOpen.value = true }
  catch (cause) { showMessage(cause instanceof Error ? cause.message : '璇诲彇璁㈠崟鏀舵淇℃伅澶辫触', 'error') }
}

function payment(row: Record<string, unknown>) {
  if (activeModule.value === 'finance' && !canWriteFinance.value) return
  paymentRow.value = row
  paymentOpen.value = true
}

function financeType(row: Record<string, unknown>): 'SALES' | 'PURCHASE' {
  return String(row.cashDirection) === 'PAYABLE' ? 'PURCHASE' : 'SALES'
}

function openFinanceReview(row: Record<string, unknown>) {
  if (!canWriteFinance.value) return
  financeReviewRow.value = row
}

function openInvoice(row: Record<string, unknown>) {
  if (!canMaintainInvoices.value) return
  if (activeModule.value === 'order') {
    invoiceContext.value = { type: 'SALES', businessId: Number(row.id), businessNo: String(row.orderNo ?? '') }
    return
  }
  if (activeModule.value === 'purchase') {
    invoiceContext.value = { type: 'PURCHASE', businessId: Number(row.id), businessNo: String(row.purchaseNo ?? '') }
    return
  }
  invoiceContext.value = { type: financeType(row), businessId: Number(row.id), businessNo: String(row.businessNo ?? '') }
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

async function exportDocument(row: Record<string, unknown>) {
  if (activeModule.value === 'order') {
    try { documentExportOrder.value = await getOrder(Number(row.id)) }
    catch (cause) { showMessage(cause instanceof Error ? cause.message : '读取订单导出信息失败', 'error') }
    return
  }
  try { await downloadExcelExport(activeModule.value, 'document', Number(row.id)); showMessage('Excel 单据已开始下载') }
  catch (cause) { showMessage(cause instanceof Error ? cause.message : 'Excel 单据导出失败', 'error') }
}

async function exportOrderDocument(type: 'sales' | 'shipment', shipmentId?: number) {
  const order = documentExportOrder.value
  if (!order) return
  try {
    await downloadExcelExport('order', 'document', Number(order.id), type === 'shipment' ? { type: 'shipment', shipmentId } : {})
    documentExportOrder.value = undefined
    showMessage('Excel 单据已开始下载')
  } catch (cause) { showMessage(cause instanceof Error ? cause.message : 'Excel 单据导出失败', 'error') }
}

async function exportSummary() {
  try { await downloadExcelExport(activeModule.value, 'summary'); showMessage('Excel 汇总数据已开始下载') }
  catch (cause) { showMessage(cause instanceof Error ? cause.message : 'Excel 汇总数据导出失败', 'error') }
}
async function reviewOrderRow(row: Record<string, unknown>) {
  if (!window.confirm(`确认复核订单“${String(row.orderNo ?? '')}”吗？复核后将锁定可用库存，并生成采购建议。`)) return
  try { await reviewOrder(Number(row.id)); showMessage('订单已复核'); list.value?.reload() }
  catch (cause) { showMessage(cause instanceof Error ? cause.message : '订单复核失败', 'error') }
}
async function deleteOrderRow(row: Record<string, unknown>) {
  if (!window.confirm(`确认删除订单“${String(row.orderNo ?? '')}”吗？已锁定库存将释放，采购建议会重新计算。`)) return
  try { await deleteOrder(Number(row.id)); showMessage('订单已删除'); list.value?.reload() }
  catch (cause) { showMessage(cause instanceof Error ? cause.message : '订单删除失败', 'error') }
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
    showMessage('草稿订单请在订单列表中复核')
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
  editingPurchase.value = undefined
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
    <main><div class="content"><DashboardPage v-if="activeModule === 'dashboard'" @navigate="navigateFromDashboard" /><ProductCodeRulesDialog v-else-if="productCodeRulesOpen" @close="productCodeRulesOpen=false" @message="showMessage" /><ModuleListPage v-else ref="list" :module="currentModule" :current-user-role="user.role" @action="primary" @import="openImport" @export-document="exportDocument" @export-summary="exportSummary" @manual="manual" @edit="edit" @gallery="openProductGallery" @funds="openCustomerFunds" @details="details" @receipt="receipt" @payment="payment" @finance-review="openFinanceReview" @invoice="openInvoice" @purchase-receipt="purchaseReceipt" @after-sales-receipt="openAfterSalesReceipt" @after-sales-shipment="openAfterSalesShipment" @after-sales-refund="row=>afterSalesRefundId=Number(row.id)" @after-sales-cancel="cancelAfterSalesRow" @review-order="reviewOrderRow" @delete-order="deleteOrderRow" @shipment="shipment" @allocation="allocation" @workflow="workflow" @navigate-supplier="selectModule('supplier')" @message="showMessage" /></div></main>
    <div v-if="importOpen && currentModule.importType && canUseCurrentModuleImport" class="dialog-mask import-dialog-mask"><ImportPanel :type="currentModule.importType" :title="currentModule.importActionLabel ?? currentModule.actionLabel" @close="importOpen=false; list?.reload()" @message="showMessage" /></div>
    <CustomerDialog v-if="entityOpen && activeModule === 'customer'" :row="editRow" @close="entityOpen=false" @saved="saved" @message="showMessage" />
    <EntityDialog v-else-if="entityOpen" :module="activeModule" :row="editRow" :current-user-role="user.role" @close="entityOpen=false" @saved="saved" @message="showMessage" />
    <CustomerFundsDialog v-if="customerFundsRow" :customer="customerFundsRow" :current-user-role="user.role" @close="customerFundsRow=undefined" @changed="list?.reload()" @message="showMessage" />
    <SupplierDialog v-if="supplierOpen" :row="editRow" @close="supplierOpen=false" @saved="saved" @message="showMessage" />
    <OrderDialog v-if="orderOpen" :row="editRow" :default-salesperson="user?.displayName" @close="orderOpen=false" @saved="saved" @message="showMessage" />
    <ManualPurchaseDialog v-if="manualPurchaseOpen" :purchase="editingPurchase" @close="manualPurchaseOpen=false; editingPurchase=undefined" @saved="saved" @message="showMessage" />
    <PaymentDialog v-if="paymentOpen && paymentRow" :purchase="paymentRow" @close="paymentOpen=false; paymentRow=undefined" @saved="saved" @message="showMessage" />
    <PurchaseReceiptDialog v-if="purchaseReceiptOpen && purchaseReceiptOrder" :purchase="purchaseReceiptOrder" @close="purchaseReceiptOpen=false; purchaseReceiptOrder=undefined" @saved="purchaseReceiptOpen=false; purchaseReceiptOrder=undefined; saved()" @message="showMessage" />
    <ProcurementReviewDialog v-if="procurementReviewId" :suggestion-id="procurementReviewId" @close="procurementReviewId=undefined" @saved="procurementReviewId=undefined; list?.reload()" @message="showMessage" />
    <ReceiptDialog v-if="receiptOpen && receiptRow" :order="receiptRow" @close="receiptOpen=false" @saved="receiptOpen=false; saved()" @message="showMessage" />
    <FinanceReviewDialog v-if="financeReviewRow" :type="financeType(financeReviewRow)" :business-id="Number(financeReviewRow.id)" :can-review-invoices="canMaintainInvoices" @close="financeReviewRow=undefined" @saved="saved(false)" @message="showMessage" />
    <InvoiceDialog v-if="invoiceContext" :type="invoiceContext.type" :business-id="invoiceContext.businessId" :business-no="invoiceContext.businessNo" @close="invoiceContext=undefined" @saved="saved" @message="showMessage" />
    <OrderAllocationDialog v-if="allocationOpen && orderAllocation" :allocation="orderAllocation" @close="allocationOpen=false; orderAllocation=undefined" @saved="allocationOpen=false; orderAllocation=undefined; list?.reload()" @message="showMessage" />
    <ShipmentQuantityDialog v-if="shipmentOpen && shipmentOrder" :order="shipmentOrder" @close="shipmentOpen=false" @saved="shipmentOpen=false; list?.reload()" @message="showMessage" />
    <OrderDocumentExportDialog v-if="documentExportOrder" :order-no="String(documentExportOrder.orderNo ?? '')" :shipments="documentExportShipments" @close="documentExportOrder=undefined" @export="exportOrderDocument" />
    <BusinessTraceDialog v-if="traceOpen && businessTrace" :trace="businessTrace" @close="traceOpen=false" />

    <ProductGalleryDialog v-if="productGalleryRow" :product-id="Number(productGalleryRow.id)" :initial-image-id="productGalleryRow.primaryImageId ? Number(productGalleryRow.primaryImageId) : undefined" @close="productGalleryRow=undefined" />
    <AfterSalesDialog v-if="afterSalesOpen" :detail="afterSalesDetail" @close="afterSalesOpen=false" @saved="afterSalesSaved" @message="showMessage" />
    <AfterSalesReceiptDialog v-if="afterSalesReceiptOpen && afterSalesDetail" :detail="afterSalesDetail" @close="afterSalesReceiptOpen=false" @saved="afterSalesSaved" @message="showMessage" />
    <AfterSalesShipmentDialog v-if="afterSalesShipmentOpen && afterSalesDetail" :detail="afterSalesDetail" @close="afterSalesShipmentOpen=false" @saved="afterSalesSaved" @message="showMessage" />
    <AfterSalesRefundDialog v-if="afterSalesRefundId" :after-sales-id="afterSalesRefundId" @close="afterSalesRefundId=undefined" @saved="afterSalesRefundId=undefined; list?.reload()" @message="showMessage" />
    <ActionInputDialog v-if="actionInput" :title="actionInput.title" :label="actionInput.label" :placeholder="actionInput.placeholder" @close="actionInput=null" @confirm="submitActionInput" />
  </div>
</template>

