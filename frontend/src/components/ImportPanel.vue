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
    if (props.type === 'SUPPLIER' || props.type === 'PRODUCT') {
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

async function commitPreview(action: () => Promise<ImportBatch>) {
  busy.value = true
  errorMessage.value = ''
  try {
    const committed = await action()
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
        <div class="supplier-preview-table-wrap"><table class="product-preview-table"><thead><tr><th>状态</th><th>来源</th><th>来源编号</th><th>计算编号</th><th>型号</th><th>产品配置</th><th>供应商</th><th>含税价</th><th>冲突决定</th></tr></thead><tbody><tr v-for="row in previewBatch.rows" :key="row.id" data-test="preview-row"><td><span :class="['supplier-preview-status', row.status.toLowerCase()]">{{ row.status === 'VALID' ? '有效' : row.status === 'ERROR' ? '错误' : '忽略' }}</span></td><td class="compact-cell" :title="`${row.sheetName} · 第 ${row.rowNumber} 行`">{{ row.sheetName }} · 第 {{ row.rowNumber }} 行</td><td class="compact-cell" :title="value(row, 'sourceProductCode')">{{ value(row, 'sourceProductCode') }}</td><td class="compact-cell code-cell" :title="value(row, 'productCode')">{{ value(row, 'productCode') }}</td><td>{{ value(row, 'model') }}</td><td class="compact-cell" :title="value(row, 'productConfiguration')">{{ value(row, 'productConfiguration') }}</td><td class="compact-cell" :title="value(row, 'supplierName')">{{ value(row, 'supplierName') }}</td><td>{{ value(row, 'supplierTaxPrice') }}</td><td><template v-if="row.data._conflictGroup"><label class="decision-control"><input :data-test="`product-conflict-keep-${row.id}`" type="radio" :name="`product-${row.data._conflictGroup}`" :checked="productActions[row.id] === 'KEEP'" :disabled="row.status !== 'VALID' || busy" @change="chooseProductKeep(productConflictGroups.find(group => group.code === String(row.data._conflictGroup))?.rows ?? [], row.id)">保留</label><span v-if="productActions[row.id] === 'SKIP'" class="skip-state">跳过</span><span v-else-if="!productActions[row.id]" class="unresolved-state">未选择</span></template><span v-else>自动保留</span></td></tr><tr v-if="previewBatch.rows.length === 0"><td colspan="9">没有可预览的产品数据</td></tr></tbody></table></div>
        <div v-if="productConflictGroups.length" class="conflict-groups"><div v-for="group in productConflictGroups" :key="group.code"><span>重复编号 {{ group.code }}（{{ group.rows.length }} 行）</span><button type="button" class="secondary-action compact-action" :disabled="busy" @click="skipProductGroup(group.rows)">整组跳过</button></div></div>
        <div v-if="productConflictGroups.length" class="conflict-row-actions">
          <div v-for="group in productConflictGroups" :key="`${group.code}-rows`"><span>逐行跳过：</span>
            <button v-for="row in group.rows" :key="row.id" :data-test="`product-conflict-skip-${row.id}`" type="button" class="secondary-action compact-action" :disabled="row.status !== 'VALID' || busy" @click="skipProductRow(row.id)">第 {{ row.rowNumber }} 行</button>
          </div>
        </div>
        <div class="supplier-preview-actions"><p v-if="previewBatch.errorRows > 0">存在错误行，不能执行全量替换。</p><p v-else-if="!productCanCommit">请完成所有冲突组的保留或跳过决定。</p><button data-test="commit-product-replace" type="button" class="primary-action danger-action" :disabled="busy || !productCanCommit" @click="confirmProductReplace">确认全量替换</button></div>
      </section>

      <p v-else-if="importedRows !== null" data-test="import-success" class="simple-import-success">成功导入 {{ importedRows }} 条数据。关闭窗口后，列表会自动刷新。</p>
      <p v-else-if="errorMessage" class="simple-import-error">导入失败：{{ errorMessage }}</p>
    </div>
    <footer class="simple-import-footer"><button type="button" class="secondary-action" @click="emit('close')">完成</button></footer>
  </section>
</template>

<style scoped>
.supplier-preview-panel{width:min(1120px,calc(100vw - 48px));max-height:min(820px,calc(100vh - 48px))}.supplier-preview-panel .simple-import-body{max-height:680px;overflow:auto}.supplier-preview,.product-preview{width:100%;border-top:1px solid #e5e7eb;padding-top:14px}.supplier-preview-summary{display:flex;flex-wrap:wrap;gap:10px;margin-bottom:12px}.supplier-preview-summary span{padding:7px 10px;border:1px solid #e1e4e8;border-radius:3px;color:#555;background:#fafafa;font-size:13px}.supplier-preview-table-wrap{max-width:100%;overflow:auto;border:1px solid #e1e4e8;border-radius:4px}.supplier-preview-table,.product-preview-table{width:100%;min-width:760px;border-collapse:collapse}.product-preview-table{min-width:1280px;table-layout:fixed}.supplier-preview-table th,.supplier-preview-table td,.product-preview-table th,.product-preview-table td{height:auto;padding:10px;border-bottom:1px solid #eceff1;text-align:left;vertical-align:top;font-size:12px;white-space:normal}.supplier-preview-status{display:inline-flex;padding:3px 7px;border-radius:999px}.supplier-preview-status.valid{color:#17633c;background:#eaf6ee}.supplier-preview-status.error{color:#a13226;background:#fff0ee}.supplier-preview-status.ignored{color:#666;background:#f1f2f3}.supplier-preview-fields{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px 10px;margin:0}.supplier-preview-fields div{min-width:0}.supplier-preview-fields dt{color:#777}.supplier-preview-fields dd{margin:2px 0 0;overflow-wrap:anywhere;color:#222}.supplier-preview-error{margin:8px 0 0;color:#a13226}.supplier-preview-actions{display:flex;align-items:center;justify-content:flex-end;gap:12px;margin-top:12px}.supplier-preview-actions p{margin:0 auto 0 0;color:#a13226;font-size:12px}.compact-cell{max-width:180px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap!important}.code-cell{font-family:ui-monospace,SFMono-Regular,Consolas,monospace}.product-danger-note{padding:9px 11px;border-left:3px solid #c73c2f;background:#fff6f4;color:#842d25;font-size:13px}.decision-control{display:inline-flex;gap:5px;align-items:center;white-space:nowrap}.skip-state{margin-left:8px;color:#666}.unresolved-state{margin-left:8px;color:#a13226}.conflict-groups{display:grid;gap:7px;margin-top:10px}.conflict-groups>div{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:8px 10px;background:#fafafa;border:1px solid #e7e7e7}.compact-action{padding:5px 9px}.danger-action{background:#9f271e;border-color:#9f271e}
</style>
