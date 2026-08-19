<script setup lang="ts">
import OverflowText from './OverflowText.vue'
import { computed, ref } from 'vue'
import {
  commitImport,
  commitProductReplace,
  previewImport,
  type ImportBatch,
  type ImportRow,
  type ImportType,
  type ProductConflictAction,
  type SupplierImportMode
} from '../api/imports'

const props = defineProps<{ type: ImportType; title: string }>()
const emit = defineEmits<{ close: []; message: [text: string, kind: 'success' | 'error'] }>()

const busy = ref(false)
const selectedFilename = ref('')
const importedRows = ref<number | null>(null)
const errorMessage = ref('')
const supplierMode = ref<SupplierImportMode>('OVERWRITE')
const previewBatch = ref<ImportBatch | null>(null)
const productActions = ref<Record<number, ProductConflictAction>>({})
const errorText = (error: unknown) => error instanceof Error ? error.message : '导入失败，请稍后重试'

const supplierFieldLabels: Record<string, string> = {
  manufacturerCategory: '厂商分类', manufacturerType: '厂商类型', supplierLocation: '供应商地点',
  productAttribute: '产品属性', shortName: '简称', supplierName: '供应商名称', contactName: '联系人',
  contactTitle: '职称', phone: '联系方式', address: '供应商地址', currency: '币种',
  taxRegistrationNo: '税务登记号', bankAddress: '开户地址', bankAccount: '开户账户'
}

const productConflictGroups = computed(() => {
  const groups = new Map<string, ImportRow[]>()
  for (const row of previewBatch.value?.rows ?? []) {
    const group = String(row.data._conflictGroup ?? '')
    if (!group) continue
    groups.set(group, [...(groups.get(group) ?? []), row])
  }
  return [...groups.entries()].map(([code, rows]) => ({ code, rows }))
})

const orderGroups = computed(() => {
  const groups = new Map<string, ImportRow[]>()
  for (const row of previewBatch.value?.rows ?? []) {
    const orderNo = String(row.data.externalOrderNo ?? '未填写外部订单号')
    groups.set(orderNo, [...(groups.get(orderNo) ?? []), row])
  }
  return [...groups.entries()].map(([externalOrderNo, rows]) => ({ externalOrderNo, rows, header: rows[0] }))
})

interface OrderCommitResult {
  createdOrders: number
  failedOrders: number
  committed: number
  errors: number
  orderErrors: Array<{ externalOrderNo: string; error: string }>
}

const orderCommitResult = computed<OrderCommitResult | null>(() => {
  const result = previewBatch.value?.result
  if (!result || typeof result !== 'object') return null
  const detail = result as Partial<OrderCommitResult>
  return {
    createdOrders: Number(detail.createdOrders ?? 0),
    failedOrders: Number(detail.failedOrders ?? 0),
    committed: Number(detail.committed ?? 0),
    errors: Number(detail.errors ?? 0),
    orderErrors: Array.isArray(detail.orderErrors) ? detail.orderErrors : []
  }
})

const orderCommitHasSuccess = computed(() => Boolean(orderCommitResult.value
  && (orderCommitResult.value.createdOrders > 0 || orderCommitResult.value.committed > 0)))

const productCanCommit = computed(() => {
  const batch = previewBatch.value
  if (!batch || batch.importType !== 'PRODUCT' || batch.errorRows > 0) return false
  if (batch.rows.some(row => row.status === 'ERROR')) return false
  for (const group of productConflictGroups.value) {
    if (group.rows.some(row => !productActions.value[row.id])) return false
    if (group.rows.filter(row => productActions.value[row.id] === 'KEEP').length > 1) return false
  }
  const nonConflicts = batch.rows.filter(row => row.status === 'VALID' && !row.data._conflictGroup).length
  const keptConflicts = Object.values(productActions.value).filter(action => action === 'KEEP').length
  return nonConflicts + keptConflicts > 0
})

const orderCanCommit = computed(() => {
  const batch = previewBatch.value
  return Boolean(batch
    && batch.importType === 'ORDER'
    && batch.status === 'PREVIEW'
    && batch.errorRows === 0
    && batch.validRows > 0
    && orderGroups.value.length > 0
    && !batch.rows.some(row => row.status === 'ERROR'))
})

function displayedFields(row: ImportRow) {
  return Object.entries(row.data)
    .filter(([key, value]) => !key.startsWith('_') && value !== null && value !== undefined)
    .map(([key, value]) => ({ key, label: supplierFieldLabels[key] ?? key, value: String(value) }))
}

function value(row: ImportRow, key: string) {
  const raw = row.data[key]
  return raw === null || raw === undefined || raw === '' ? '—' : String(raw)
}

function chooseProductKeep(groupRows: ImportRow[], rowId: number) {
  const next = { ...productActions.value }
  for (const row of groupRows) next[row.id] = row.id === rowId ? 'KEEP' : 'SKIP'
  productActions.value = next
}

function skipProductGroup(groupRows: ImportRow[]) {
  const next = { ...productActions.value }
  for (const row of groupRows) next[row.id] = 'SKIP'
  productActions.value = next
}

function skipProductRow(rowId: number) {
  productActions.value = { ...productActions.value, [rowId]: 'SKIP' }
}

async function onFileChange(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file || busy.value) return
  busy.value = true
  selectedFilename.value = file.name
  importedRows.value = null
  previewBatch.value = null
  productActions.value = {}
  errorMessage.value = ''
  try {
    const batch = await previewImport(props.type, file)
    if (props.type === 'SUPPLIER' || props.type === 'PRODUCT' || props.type === 'ORDER') {
      previewBatch.value = batch
      return
    }
    const committed = await commitImport(batch.batchId)
    importedRows.value = committed.committedRows
    emit('message', `成功导入 ${committed.committedRows} 条数据`, 'success')
  } catch (error) {
    errorMessage.value = errorText(error)
    emit('message', errorMessage.value, 'error')
  } finally {
    busy.value = false
  }
}

async function confirmSupplierImport() {
  const batch = previewBatch.value
  if (!batch || busy.value) return
  if (supplierMode.value === 'REPLACE_ALL'
    && !window.confirm('全量替换将停用文件中不存在的供应商，是否继续？')) return
  await commitPreview(() => commitImport(batch.batchId, supplierMode.value))
}

async function confirmProductReplace() {
  const batch = previewBatch.value
  if (!batch || busy.value || !productCanCommit.value) return
  const warning = '产品全量替换将清空：订单、库存、采购、售后、发货、发票/收付款、合同价格、产品图片、供应商产品关系和成本历史。\n\n将保留：客户、供应商主体、用户权限、产品编号规则和客户资金流水。\n\n此操作不可单独撤销，是否继续？'
  if (!window.confirm(warning)) return
  await commitPreview(() => commitProductReplace(batch.batchId, productActions.value))
}

function orderStatusLabel(row: ImportRow) {
  const normalized = String(row.data._normalizedStatus ?? '')
  if (normalized === 'DRAFT') return '草稿'
  if (normalized === 'PENDING_CUSTOMER_PAYMENT') return '正式订单'
  return value(row, 'orderStatus')
}

async function confirmOrderImport() {
  const batch = previewBatch.value
  if (!batch || busy.value || !orderCanCommit.value) return
  const warning = '确认提交订单导入？正式订单将按现有逻辑锁定库存，草稿不锁定，客户余额不会自动扣减。'
  if (!window.confirm(warning)) return
  await commitPreview(() => commitImport(batch.batchId))
}

async function commitPreview(action: () => Promise<ImportBatch>) {
  busy.value = true
  errorMessage.value = ''
  try {
    const committed = await action()
    const detail = committed.importType === 'ORDER' && committed.result && typeof committed.result === 'object'
      ? committed.result as Partial<OrderCommitResult>
      : null
    const orderHasFailures = committed.importType === 'ORDER'
      && (committed.errorRows > 0 || Number(detail?.failedOrders ?? 0) > 0
        || (Array.isArray(detail?.orderErrors) && detail.orderErrors.length > 0))
    if (orderHasFailures) {
      previewBatch.value = committed
      importedRows.value = null
      const hasSuccess = Number(detail?.createdOrders ?? 0) > 0 || Number(detail?.committed ?? 0) > 0
      emit('message', `${hasSuccess ? '订单部分导入成功' : '订单导入失败'}：成功 ${Number(detail?.createdOrders ?? 0)} 单，失败 ${Number(detail?.failedOrders ?? 0)} 单`, 'error')
      return
    }
    importedRows.value = committed.committedRows
    previewBatch.value = null
    emit('message', `成功导入 ${committed.committedRows} 条数据`, 'success')
  } catch (error) {
    errorMessage.value = errorText(error)
    emit('message', errorMessage.value, 'error')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="import-panel simple-import-panel supplier-preview-panel" aria-label="Excel 导入面板">
    <header class="import-header">
      <div>
        <h2>{{ title }}</h2>
        <p v-if="type === 'PRODUCT'">选择文件后先核对两张产品表、计算编号与冲突，再确认全量替换。</p>
        <p v-else-if="type === 'SUPPLIER'">选择文件后先核对预览，再确认写入供应商资料。</p>
        <p v-else-if="type === 'ORDER'">选择文件后先按外部订单号核对分组预览，再确认提交订单。</p>
        <p v-else>选择 Excel 文件后，系统会自动解析并导入当前页面的数据。</p>
      </div>
      <button data-test="close-panel" type="button" class="dialog-close" @click="emit('close')">关闭</button>
    </header>

    <div class="simple-import-body">
      <fieldset v-if="type === 'SUPPLIER'" class="supplier-import-strategy" :disabled="busy">
        <legend>导入策略</legend>
        <div class="supplier-import-options">
          <label :class="{ selected: supplierMode === 'OVERWRITE' }"><input data-test="supplier-mode-overwrite" v-model="supplierMode" type="radio" value="OVERWRITE" :aria-checked="supplierMode === 'OVERWRITE'"><span><strong>覆盖更新</strong><small>更新同名供应商并新增文件中的其他供应商</small></span></label>
          <label :class="{ selected: supplierMode === 'REPLACE_ALL' }"><input data-test="supplier-mode-replace-all" v-model="supplierMode" type="radio" value="REPLACE_ALL" :aria-checked="supplierMode === 'REPLACE_ALL'"><span><strong>全量替换</strong><small>同时停用文件中不存在的供应商</small></span></label>
        </div>
        <p v-if="supplierMode === 'REPLACE_ALL'" class="supplier-import-warning" role="note">全量替换会停用文件中不存在的供应商；确认导入时，系统会再次询问。</p>
      </fieldset>

      <label class="file-picker" :class="{ disabled: busy }">
        <span>{{ busy ? '正在处理…' : '选择 Excel 文件' }}</span>
        <input type="file" :accept="type === 'SUPPLIER' ? '.xls,.xlsx' : '.xlsx'" :disabled="busy" @change="onFileChange">
      </label>
      <a v-if="['CUSTOMER', 'SUPPLIER', 'PRODUCT', 'ORDER'].includes(type)" data-test="download-import-template" class="order-template-download" :href="`/api/imports/templates/${type}.xlsx`" download>&#19979;&#36733; XLSX &#27169;&#26495;</a>
      <OverflowText v-if="selectedFilename" class="filename" :value="selectedFilename" />
      <p v-else class="simple-import-hint">{{ type === 'SUPPLIER' ? '支持 .xls 和 .xlsx 文件' : '支持 .xlsx 文件' }}</p>
      <p v-if="busy" class="simple-import-status">{{ previewBatch ? '正在提交数据，请稍候…' : '正在解析文件，请稍候…' }}</p>

      <section v-if="previewBatch && importedRows === null && type === 'SUPPLIER'" class="supplier-preview" aria-label="供应商导入预览">
        <div class="supplier-preview-summary"><span>总行数 <strong data-test="preview-total-count">{{ previewBatch.totalRows }}</strong></span><span>有效行 <strong data-test="preview-valid-count">{{ previewBatch.validRows }}</strong></span><span>错误行 <strong data-test="preview-error-count">{{ previewBatch.errorRows }}</strong></span></div>
        <div class="supplier-preview-table-wrap"><table class="supplier-preview-table"><thead><tr><th>状态</th><th>来源</th><th>标准化字段</th></tr></thead><tbody><tr v-for="row in previewBatch.rows" :key="row.id" data-test="preview-row"><td><span :class="['supplier-preview-status', row.status.toLowerCase()]">{{ row.status === 'VALID' ? '有效' : row.status === 'ERROR' ? '错误' : '忽略' }}</span></td><td>{{ row.sheetName }} · 第 {{ row.rowNumber }} 行</td><td><dl class="supplier-preview-fields"><div v-for="field in displayedFields(row)" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ field.value }}</dd></div></dl><p v-if="row.errorMessage" class="supplier-preview-error">{{ row.errorMessage }}</p></td></tr></tbody></table></div>
        <div class="supplier-preview-actions"><p v-if="supplierMode === 'REPLACE_ALL' && previewBatch.errorRows > 0">全量替换前必须修正所有错误行。</p><button data-test="commit-import" type="button" class="primary-action" :disabled="busy || (supplierMode === 'REPLACE_ALL' && previewBatch.errorRows > 0)" @click="confirmSupplierImport">确认导入</button></div>
      </section>

      <section v-else-if="previewBatch && importedRows === null && type === 'PRODUCT'" class="product-preview" aria-label="产品全量替换预览">
        <div class="supplier-preview-summary"><span>总行数 <strong data-test="preview-total-count">{{ previewBatch.totalRows }}</strong></span><span>有效行 <strong data-test="preview-valid-count">{{ previewBatch.validRows }}</strong></span><span>错误行 <strong data-test="preview-error-count">{{ previewBatch.errorRows }}</strong></span><span>冲突组 <strong>{{ productConflictGroups.length }}</strong></span></div>
        <p class="product-danger-note" role="note">当前仅为预览，尚未修改数据库。请先处理所有重复编号和错误行。</p>
        <div class="supplier-preview-table-wrap"><table class="product-preview-table"><thead><tr><th>状态</th><th>来源</th><th>来源编号</th><th>计算编号</th><th>型号</th><th>产品配置</th><th>供应商</th><th>含税价</th><th>实际库存数量</th><th>已锁定数量</th><th>在途数量</th><th>库存供应商</th><th>库存备注</th><th>错误</th><th>冲突决定</th></tr></thead><tbody><tr v-for="row in previewBatch.rows" :key="row.id" data-test="preview-row"><td><span :class="['supplier-preview-status', row.status.toLowerCase()]">{{ row.status === 'VALID' ? '有效' : row.status === 'ERROR' ? '错误' : '忽略' }}</span></td><td class="compact-cell" :title="`${row.sheetName} · 第 ${row.rowNumber} 行`">{{ row.sheetName }} · 第 {{ row.rowNumber }} 行</td><td class="compact-cell" :title="value(row, 'sourceProductCode')">{{ value(row, 'sourceProductCode') }}</td><td class="compact-cell code-cell" :title="value(row, 'productCode')">{{ value(row, 'productCode') }}</td><td class="compact-cell" :data-test="`product-model-${row.id}`" :title="value(row, 'model')">{{ value(row, 'model') }}</td><td class="compact-cell" :title="value(row, 'productConfiguration')">{{ value(row, 'productConfiguration') }}</td><td class="compact-cell" :title="value(row, 'supplierName')">{{ value(row, 'supplierName') }}</td><td class="compact-cell" :title="value(row, 'supplierTaxPrice')">{{ value(row, 'supplierTaxPrice') }}</td><td class="compact-cell" :title="value(row, 'actualQuantity')">{{ value(row, 'actualQuantity') }}</td><td class="compact-cell" :title="value(row, 'lockedQuantity')">{{ value(row, 'lockedQuantity') }}</td><td class="compact-cell" :title="value(row, 'inTransitQuantity')">{{ value(row, 'inTransitQuantity') }}</td><td class="compact-cell" :title="value(row, 'sourceSupplierName')">{{ value(row, 'sourceSupplierName') }}</td><td class="compact-cell" :title="value(row, 'inventoryRemark')">{{ value(row, 'inventoryRemark') }}</td><td class="compact-cell product-error-cell" :data-test="`product-row-error-${row.id}`" :title="row.errorMessage ?? ''">{{ row.errorMessage ?? '—' }}</td><td><template v-if="row.data._conflictGroup"><label class="decision-control"><input :data-test="`product-conflict-keep-${row.id}`" type="radio" :name="`product-${row.data._conflictGroup}`" :checked="productActions[row.id] === 'KEEP'" :disabled="row.status !== 'VALID' || busy" @change="chooseProductKeep(productConflictGroups.find(group => group.code === String(row.data._conflictGroup))?.rows ?? [], row.id)">保留</label><span v-if="productActions[row.id] === 'SKIP'" class="skip-state">跳过</span><span v-else-if="!productActions[row.id]" class="unresolved-state">未选择</span></template><span v-else>自动保留</span></td></tr><tr v-if="previewBatch.rows.length === 0"><td colspan="15">没有可预览的产品数据</td></tr></tbody></table></div>
        <div v-if="productConflictGroups.length" class="conflict-groups"><div v-for="group in productConflictGroups" :key="group.code"><span>重复编号 {{ group.code }}（{{ group.rows.length }} 行）</span><button type="button" class="secondary-action compact-action" :disabled="busy" @click="skipProductGroup(group.rows)">整组跳过</button></div></div>
        <div v-if="productConflictGroups.length" class="conflict-row-actions">
          <div v-for="group in productConflictGroups" :key="`${group.code}-rows`"><span>逐行跳过：</span>
            <button v-for="row in group.rows" :key="row.id" :data-test="`product-conflict-skip-${row.id}`" type="button" class="secondary-action compact-action" :disabled="row.status !== 'VALID' || busy" @click="skipProductRow(row.id)">第 {{ row.rowNumber }} 行</button>
          </div>
        </div>
        <div class="supplier-preview-actions"><p v-if="previewBatch.errorRows > 0">存在错误行，不能执行全量替换。</p><p v-else-if="!productCanCommit">请完成所有冲突组的保留或跳过决定。</p><button data-test="commit-product-replace" type="button" class="primary-action danger-action" :disabled="busy || !productCanCommit" @click="confirmProductReplace">确认全量替换</button></div>
      </section>

      <section v-else-if="previewBatch && importedRows === null && type === 'ORDER'" class="order-preview" aria-label="订单导入预览">
        <div v-if="orderCommitResult && (orderCommitResult.failedOrders > 0 || orderCommitResult.errors > 0 || orderCommitResult.orderErrors.length > 0)" data-test="order-partial-result" class="order-partial-result" role="status">
          <strong>{{ orderCommitHasSuccess ? '订单部分导入成功' : '订单导入失败' }}</strong>
          <span>成功订单 {{ orderCommitResult.createdOrders }}</span>
          <span>失败订单 {{ orderCommitResult.failedOrders }}</span>
          <span>成功行 {{ orderCommitResult.committed }}</span>
          <span>失败行 {{ orderCommitResult.errors }}</span>
          <a data-test="download-import-errors" :href="`/api/imports/${previewBatch.batchId}/errors.xlsx`" download>下载错误清单</a>
        </div>
        <div class="supplier-preview-summary"><span>订单数 <strong data-test="order-preview-count">{{ orderGroups.length }}</strong></span><span>总行数 <strong data-test="preview-total-count">{{ previewBatch.totalRows }}</strong></span><span>有效行 <strong data-test="preview-valid-count">{{ previewBatch.validRows }}</strong></span><span>错误行 <strong data-test="preview-error-count">{{ previewBatch.errorRows }}</strong></span></div>
        <section v-for="group in orderGroups" :key="group.externalOrderNo" class="order-preview-group" :data-test="`order-preview-group-${group.externalOrderNo}`">
          <header><strong>外部订单号：{{ group.externalOrderNo }}</strong><span>客户编码：{{ value(group.header, 'customerCode') }}</span><span>订单日期：{{ value(group.header, 'orderDate') }}</span><span>订单类型：{{ value(group.header, 'orderType') }}</span><span>状态：{{ orderStatusLabel(group.header) }}</span></header>
          <div class="supplier-preview-table-wrap"><table class="order-preview-table"><thead><tr><th>产品编号</th><th>客户料号</th><th>数量</th><th>含税单价</th><th>状态</th><th>错误</th></tr></thead><tbody><tr v-for="row in group.rows" :key="row.id" data-test="order-preview-row"><td>{{ value(row, 'productCode') }}</td><td>{{ value(row, 'customerPartNumber') }}</td><td>{{ value(row, 'model') }}</td><td>{{ value(row, 'quantity') }}</td><td>{{ value(row, 'salePrice') }}</td><td>{{ row.status === 'VALID' ? '有效' : row.status === 'ERROR' ? '错误' : '忽略' }}</td><td>{{ row.errorMessage ?? '—' }}</td></tr></tbody></table></div>
        </section>
        <div class="supplier-preview-actions"><p v-if="!orderCanCommit">{{ previewBatch.errorRows > 0 ? '存在错误行，不能提交订单。' : '没有有效订单，不能提交。' }}</p><button data-test="commit-order-import" type="button" class="primary-action" :disabled="busy || !orderCanCommit" @click="confirmOrderImport">确认导入订单</button></div>
      </section>

      <p v-else-if="importedRows !== null" data-test="import-success" class="simple-import-success">成功导入 {{ importedRows }} 条数据。关闭窗口后，列表会自动刷新。</p>
      <p v-else-if="errorMessage" class="simple-import-error">导入失败：{{ errorMessage }}</p>
    </div>
    <footer class="simple-import-footer"><button type="button" class="secondary-action" @click="emit('close')">完成</button></footer>
  </section>
</template>

<style scoped>
.order-template-download{display:inline-flex;align-self:flex-start;color:#315d82;text-decoration:underline;text-underline-offset:2px}
.supplier-preview-panel{width:min(1120px,calc(100vw - 48px));max-height:min(820px,calc(100vh - 48px))}.supplier-preview-panel .simple-import-body{max-height:680px;overflow:auto}.supplier-preview,.product-preview,.order-preview{width:100%;border-top:1px solid #e5e7eb;padding-top:14px}.supplier-preview-summary{display:flex;flex-wrap:wrap;gap:10px;margin-bottom:12px}.supplier-preview-summary span{padding:7px 10px;border:1px solid #e1e4e8;border-radius:3px;color:#555;background:#fafafa;font-size:13px}.supplier-preview-table-wrap{max-width:100%;overflow:auto;border:1px solid #e1e4e8;border-radius:4px}.supplier-preview-table,.product-preview-table,.order-preview-table{width:100%;min-width:760px;border-collapse:collapse}.product-preview-table{min-width:1280px;table-layout:fixed}.supplier-preview-table th,.supplier-preview-table td,.product-preview-table th,.product-preview-table td,.order-preview-table th,.order-preview-table td{height:auto;padding:10px;border-bottom:1px solid #eceff1;text-align:left;vertical-align:top;font-size:12px;white-space:normal}.supplier-preview-status{display:inline-flex;padding:3px 7px;border-radius:999px}.supplier-preview-status.valid{color:#17633c;background:#eaf6ee}.supplier-preview-status.error{color:#a13226;background:#fff0ee}.supplier-preview-status.ignored{color:#666;background:#f1f2f3}.supplier-preview-fields{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px 10px;margin:0}.supplier-preview-fields div{min-width:0}.supplier-preview-fields dt{color:#777}.supplier-preview-fields dd{margin:2px 0 0;overflow-wrap:anywhere;color:#222}.supplier-preview-error{margin:8px 0 0;color:#a13226}.supplier-preview-actions{display:flex;align-items:center;justify-content:flex-end;gap:12px;margin-top:12px}.supplier-preview-actions p{margin:0 auto 0 0;color:#a13226;font-size:12px}.compact-cell{max-width:180px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap!important}.code-cell{font-family:ui-monospace,SFMono-Regular,Consolas,monospace}.product-danger-note{padding:9px 11px;border-left:3px solid #c73c2f;background:#fff6f4;color:#842d25;font-size:13px}.decision-control{display:inline-flex;gap:5px;align-items:center;white-space:nowrap}.skip-state{margin-left:8px;color:#666}.unresolved-state{margin-left:8px;color:#a13226}.conflict-groups{display:grid;gap:7px;margin-top:10px}.conflict-groups>div{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:8px 10px;background:#fafafa;border:1px solid #e7e7e7}.compact-action{padding:5px 9px}.danger-action{background:#9f271e;border-color:#9f271e}.order-preview-group{margin:12px 0;border:1px solid #e1e4e8;border-radius:4px;overflow:hidden}.order-preview-group>header{display:flex;flex-wrap:wrap;gap:8px 16px;padding:10px;background:#fafafa;color:#444;font-size:12px}.order-preview-group>header strong{color:#222}.order-partial-result{display:flex;flex-wrap:wrap;align-items:center;gap:8px 16px;margin-bottom:12px;padding:12px;border:1px solid #e3b45b;border-radius:4px;background:#fff9ec;color:#6f4b0b}.order-partial-result strong{width:100%;color:#7d3d12}.order-partial-result a{margin-left:auto;color:#315d82;text-decoration:underline;text-underline-offset:2px}


/* Unified import workspace */
.supplier-preview-panel{width:min(1120px,calc(100vw - 40px));max-height:min(840px,calc(100vh - 40px));overflow:hidden;border:1px solid #dfe3e8;border-radius:7px;box-shadow:0 18px 48px rgba(25,32,40,.18)}
.import-header{align-items:flex-start;padding:22px 24px 18px;border-bottom:1px solid #e7e9ec;background:#f8f9fa}
.import-header h2{margin:0;color:#1f2933;font-size:20px;line-height:1.35}
.import-header p{max-width:720px;margin:7px 0 0;color:#68737d;font-size:13px;line-height:1.6}
.dialog-close{min-width:64px;min-height:40px;border:1px solid #d8dde2;border-radius:4px;background:#fff;color:#53606b}.dialog-close:hover{border-color:#9aa9b5;background:#f4f6f7;color:#20262c}
.supplier-preview-panel .simple-import-body{display:flex;max-height:680px;overflow:auto;flex-direction:column;gap:14px;padding:22px 24px}
.file-picker{display:flex;min-height:112px;align-items:center;justify-content:center;padding:20px;border:1px dashed #aebdca;border-radius:6px;background:#f7f9fa;cursor:pointer;transition:border-color .16s,background .16s,box-shadow .16s}
.file-picker::before{content:"↑";display:grid;width:44px;height:44px;margin-right:14px;place-items:center;border:1px solid #d5e0e8;border-radius:6px;background:#fff;color:#3d6789;font-size:23px;font-weight:600}
.file-picker span{display:inline-flex;min-height:40px;align-items:center;padding:0 18px;border-radius:4px;background:#25323d;color:#fff;font-size:14px;font-weight:650}
.file-picker:hover{border-color:#4f7698;background:#f3f7fa;box-shadow:0 0 0 3px rgba(69,112,148,.08)}.file-picker.disabled{cursor:not-allowed;opacity:.65}.file-picker:focus-within{outline:3px solid rgba(49,93,130,.22);outline-offset:2px}
.order-template-download{display:inline-flex;min-height:40px;align-items:center;align-self:flex-start;padding:0 14px;border:1px solid #cbd6df;border-radius:4px;background:#fff;color:#315d82;font-size:13px;font-weight:650;text-decoration:none}
.order-template-download::before{content:"↓";margin-right:8px;font-size:16px;font-weight:700}.order-template-download:hover{border-color:#7898b2;background:#f4f8fa}
.filename{padding:10px 12px;border:1px solid #e0e5e9;border-radius:4px;background:#fff;color:#27333e;font-size:13px;font-weight:600}.simple-import-hint{margin:-4px 0 0;color:#7a858f;font-size:12px}
.simple-import-status{margin:0;padding:11px 13px;border-radius:4px;background:#eef4f8;color:#315d82;font-size:13px}
.supplier-import-strategy{padding:16px;border:1px solid #dde3e8;border-radius:6px;background:#fafbfc}.supplier-import-strategy legend{padding:0 7px;color:#34414c;font-size:13px;font-weight:700}.supplier-import-options label{border-radius:5px}
.supplier-preview,.product-preview,.order-preview{padding-top:18px}.supplier-preview-summary{gap:8px}.supplier-preview-summary span{border-color:#e0e5e9;border-radius:4px;background:#f6f8f9;color:#59636c}.supplier-preview-summary strong{color:#202a33}
.supplier-preview-table-wrap{border-color:#dce3e8;border-radius:5px}.supplier-preview-table th,.product-preview-table th,.order-preview-table th{background:#f5f7f8;color:#44515c;font-weight:700}
.supplier-preview-table tbody tr:hover,.product-preview-table tbody tr:hover,.order-preview-table tbody tr:hover{background:#fafcfd}.product-danger-note{border-radius:0 4px 4px 0}.order-preview-group{border-color:#dce3e8;border-radius:5px}
.simple-import-success,.simple-import-error{margin:0;padding:13px 14px;border:1px solid;border-radius:5px;font-size:13px}.simple-import-success{border-color:#b9dcc8;background:#f0f8f3;color:#25633f}.simple-import-error{border-color:#edc7c2;background:#fff5f3;color:#97372d}
.simple-import-footer{min-height:66px;padding:12px 24px;border-top:1px solid #e6e9ec;background:#fafbfc}.simple-import-footer .secondary-action{min-width:88px;min-height:40px}
@media (max-width:640px){.supplier-preview-panel{width:calc(100vw - 20px);max-height:calc(100vh - 20px)}.import-header,.supplier-preview-panel .simple-import-body,.simple-import-footer{padding-left:16px;padding-right:16px}.file-picker{min-height:136px;flex-direction:column;gap:12px;text-align:center}.file-picker::before{margin-right:0}.file-picker span{width:100%;justify-content:center}.order-template-download{width:100%;justify-content:center}.supplier-preview-fields{grid-template-columns:1fr}}
</style>
