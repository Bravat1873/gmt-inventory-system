<script setup lang="ts">
import { ref } from 'vue'
import { commitImport, previewImport, type ImportType, type SupplierImportMode } from '../api/imports'

const props = defineProps<{ type: ImportType; title: string }>()
const emit = defineEmits<{ close: []; message: [text: string, kind: 'success' | 'error'] }>()

const busy = ref(false)
const selectedFilename = ref('')
const importedRows = ref<number | null>(null)
const errorMessage = ref('')
const supplierMode = ref<SupplierImportMode>('OVERWRITE')
const errorText = (error: unknown) => error instanceof Error ? error.message : '导入失败，请稍后重试'

async function onFileChange(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file || busy.value) return
  busy.value = true
  selectedFilename.value = file.name
  importedRows.value = null
  errorMessage.value = ''
  try {
    const batch = await previewImport(props.type, file)
    if (props.type === 'SUPPLIER' && supplierMode.value === 'REPLACE_ALL'
      && !window.confirm('全量替换将停用文件中不存在的供应商，是否继续？')) return
    const committed = props.type === 'SUPPLIER'
      ? await commitImport(batch.batchId, supplierMode.value)
      : await commitImport(batch.batchId)
    importedRows.value = committed.committedRows
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
  <section class="import-panel simple-import-panel" aria-label="Excel 导入面板">
    <header class="import-header">
      <div><h2>{{ title }}</h2><p>选择 Excel 文件后，系统会自动解析并导入当前页面的数据。</p></div>
      <button data-test="close-panel" type="button" class="dialog-close" @click="emit('close')">关闭</button>
    </header>

    <div class="simple-import-body">
      <fieldset v-if="type === 'SUPPLIER'" class="supplier-import-strategy" :disabled="busy">
        <legend>导入策略</legend>
        <div class="supplier-import-options">
          <label :class="{ selected: supplierMode === 'OVERWRITE' }">
            <input data-test="supplier-mode-overwrite" v-model="supplierMode" type="radio" value="OVERWRITE" :aria-checked="supplierMode === 'OVERWRITE'">
            <span><strong>覆盖更新</strong><small>更新同名供应商并新增文件中的其他供应商</small></span>
          </label>
          <label :class="{ selected: supplierMode === 'REPLACE_ALL' }">
            <input data-test="supplier-mode-replace-all" v-model="supplierMode" type="radio" value="REPLACE_ALL" :aria-checked="supplierMode === 'REPLACE_ALL'">
            <span><strong>全量替换</strong><small>同时停用文件中不存在的供应商</small></span>
          </label>
        </div>
        <p v-if="supplierMode === 'REPLACE_ALL'" class="supplier-import-warning" role="note">全量替换会停用文件中不存在的供应商；完成预览后，系统会再次确认。</p>
      </fieldset>
      <label class="file-picker" :class="{ disabled: busy }">
        <span>{{ busy ? '正在导入…' : '选择 Excel 文件' }}</span>
        <input type="file" :accept="type === 'SUPPLIER' ? '.xls,.xlsx' : '.xlsx'" :disabled="busy" @change="onFileChange" />
      </label>
      <p v-if="selectedFilename" class="filename">{{ selectedFilename }}</p>
      <p v-else class="simple-import-hint">{{ type === 'SUPPLIER' ? '支持 .xls 和 .xlsx 文件' : '支持 .xlsx 文件' }}</p>
      <p v-if="busy" class="simple-import-status">正在解析并写入数据，请稍候…</p>
      <p v-else-if="importedRows !== null" data-test="import-success" class="simple-import-success">成功导入 {{ importedRows }} 条数据。关闭窗口后，列表会自动刷新。</p>
      <p v-else-if="errorMessage" class="simple-import-error">导入失败：{{ errorMessage }}</p>
    </div>

    <footer class="simple-import-footer"><button type="button" class="secondary-action" @click="emit('close')">完成</button></footer>
  </section>
</template>
