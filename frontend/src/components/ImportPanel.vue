<script setup lang="ts">
import { ref } from 'vue'
import { commitImport, previewImport, type ImportType } from '../api/imports'

const props = defineProps<{ type: ImportType; title: string }>()
const emit = defineEmits<{ close: []; message: [text: string, kind: 'success' | 'error'] }>()

const busy = ref(false)
const selectedFilename = ref('')
const importedRows = ref<number | null>(null)
const errorMessage = ref('')
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
</script>

<template>
  <section class="import-panel simple-import-panel" aria-label="Excel 导入面板">
    <header class="import-header">
      <div><h2>{{ title }}</h2><p>选择 Excel 文件后，系统会自动解析并导入当前页面的数据。</p></div>
      <button data-test="close-panel" type="button" class="dialog-close" @click="emit('close')">关闭</button>
    </header>

    <div class="simple-import-body">
      <label class="file-picker" :class="{ disabled: busy }">
        <span>{{ busy ? '正在导入…' : '选择 Excel 文件' }}</span>
        <input type="file" accept=".xlsx" :disabled="busy" @change="onFileChange" />
      </label>
      <p v-if="selectedFilename" class="filename">{{ selectedFilename }}</p>
      <p v-else class="simple-import-hint">支持 .xlsx 文件</p>
      <p v-if="busy" class="simple-import-status">正在解析并写入数据，请稍候…</p>
      <p v-else-if="importedRows !== null" data-test="import-success" class="simple-import-success">成功导入 {{ importedRows }} 条数据。关闭窗口后，列表会自动刷新。</p>
      <p v-else-if="errorMessage" class="simple-import-error">导入失败：{{ errorMessage }}</p>
    </div>

    <footer class="simple-import-footer"><button type="button" class="secondary-action" @click="emit('close')">完成</button></footer>
  </section>
</template>
