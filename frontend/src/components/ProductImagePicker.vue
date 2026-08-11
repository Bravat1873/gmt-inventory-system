<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  deleteProductImage,
  loadProductImages,
  reorderProductImages,
  setPrimaryProductImage,
  type ProductImage
} from '../api/workbench'

const props = defineProps<{ productId?: number; modelValue: File[] }>()
const emit = defineEmits<{
  'update:modelValue': [files: File[]]
  message: [text: string, kind?: 'success' | 'error']
  changed: []
}>()

const MAX_IMAGES = 10
const MAX_BYTES = 5 * 1024 * 1024
const ALLOWED_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp'])

interface PendingImage {
  file: File
  previewUrl: string
}

function createPreviewUrl(file: File) {
  return typeof URL.createObjectURL === 'function'
    ? URL.createObjectURL(file)
    : `data:${file.type || 'application/octet-stream'};base64,`
}

const existingImages = ref<ProductImage[]>([])
const pendingImages = ref<PendingImage[]>(props.modelValue.map(file => ({ file, previewUrl: createPreviewUrl(file) })))
const busy = ref(false)
const draggingPendingIndex = ref<number | null>(null)
const totalCount = computed(() => existingImages.value.length + pendingImages.value.length)

function publishPending() {
  emit('update:modelValue', pendingImages.value.map(item => item.file))
}

function release(item: PendingImage) {
  if (item.previewUrl.startsWith('blob:') && typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(item.previewUrl)
}

watch(() => props.modelValue, files => {
  const current = pendingImages.value.map(item => item.file)
  if (files.length === current.length && files.every((file, index) => file === current[index])) return
  pendingImages.value.forEach(release)
  pendingImages.value = files.map(file => ({ file, previewUrl: createPreviewUrl(file) }))
})

onMounted(async () => {
  if (!props.productId) return
  try {
    existingImages.value = await loadProductImages(props.productId)
  } catch (reason) {
    emit('message', reason instanceof Error ? reason.message : '产品图片加载失败', 'error')
  }
})

onBeforeUnmount(() => pendingImages.value.forEach(release))

function addFiles(files: File[]) {
  for (const file of files) {
    if (!ALLOWED_TYPES.has(file.type)) {
      emit('message', `${file.name}：仅支持 JPG、PNG 和 WebP 图片`, 'error')
      continue
    }
    if (file.size > MAX_BYTES) {
      emit('message', `${file.name}：单张图片不能超过 5MB`, 'error')
      continue
    }
    if (totalCount.value >= MAX_IMAGES) {
      emit('message', '每个产品最多 10 张图片', 'error')
      break
    }
    pendingImages.value.push({ file, previewUrl: createPreviewUrl(file) })
  }
  publishPending()
}

function filesSelected(event: Event) {
  const input = event.target as HTMLInputElement
  addFiles(Array.from(input.files ?? []))
  input.value = ''
}

function dropped(event: DragEvent) {
  addFiles(Array.from(event.dataTransfer?.files ?? []))
}

function removePending(index: number) {
  const [removed] = pendingImages.value.splice(index, 1)
  if (removed) release(removed)
  publishPending()
}

function movePending(index: number, offset: -1 | 1) {
  const target = index + offset
  if (target < 0 || target >= pendingImages.value.length) return
  const [item] = pendingImages.value.splice(index, 1)
  pendingImages.value.splice(target, 0, item)
  publishPending()
}

function setPendingPrimary(index: number) {
  if (index <= 0) return
  const [item] = pendingImages.value.splice(index, 1)
  pendingImages.value.unshift(item)
  publishPending()
}

function startPendingDrag(index: number) {
  draggingPendingIndex.value = index
}

function finishPendingDrag(index: number) {
  const from = draggingPendingIndex.value
  draggingPendingIndex.value = null
  if (from == null || from === index) return
  const [item] = pendingImages.value.splice(from, 1)
  pendingImages.value.splice(index, 0, item)
  publishPending()
}

async function setExistingPrimary(imageId: number) {
  if (!props.productId || busy.value) return
  busy.value = true
  try {
    existingImages.value = await setPrimaryProductImage(props.productId, imageId)
    emit('changed')
  } catch (reason) {
    emit('message', reason instanceof Error ? reason.message : '设置主图失败', 'error')
  } finally {
    busy.value = false
  }
}

async function moveExisting(index: number, offset: -1 | 1) {
  if (!props.productId || busy.value) return
  const target = index + offset
  if (target < 0 || target >= existingImages.value.length) return
  const ids = existingImages.value.map(image => image.id)
  ;[ids[index], ids[target]] = [ids[target], ids[index]]
  busy.value = true
  try {
    existingImages.value = await reorderProductImages(props.productId, ids)
    emit('changed')
  } catch (reason) {
    emit('message', reason instanceof Error ? reason.message : '图片排序失败', 'error')
  } finally {
    busy.value = false
  }
}

async function removeExisting(imageId: number) {
  if (!props.productId || busy.value) return
  busy.value = true
  try {
    existingImages.value = await deleteProductImage(props.productId, imageId)
    emit('changed')
  } catch (reason) {
    emit('message', reason instanceof Error ? reason.message : '删除图片失败', 'error')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="product-image-picker" @dragover.prevent @drop.prevent="dropped">
    <div class="product-image-picker-heading">
      <div>
        <h3>产品图片</h3>
        <p>支持 JPG、PNG、WebP，单张不超过 5MB，最多 10 张</p>
      </div>
      <label class="product-image-add" :class="{ disabled: totalCount >= MAX_IMAGES }">
        选择图片
        <input
          data-test="product-image-input"
          type="file"
          accept="image/jpeg,image/png,image/webp"
          multiple
          :disabled="totalCount >= MAX_IMAGES || busy"
          @change="filesSelected"
        >
      </label>
    </div>

    <div v-if="existingImages.length" class="product-image-grid" data-test="existing-images">
      <article v-for="(image, index) in existingImages" :key="image.id" class="product-image-card">
        <div class="product-image-preview-wrap">
          <img :src="image.contentUrl" :alt="image.originalFilename" class="product-image-preview">
          <span v-if="image.primary" data-test="primary-badge" class="product-image-primary">主图</span>
        </div>
        <strong :title="image.originalFilename">{{ image.originalFilename }}</strong>
        <div class="product-image-controls">
          <button type="button" data-test="set-primary-existing" :disabled="busy || image.primary" @click="setExistingPrimary(image.id)">{{ image.primary ? '当前主图' : '设为主图' }}</button>
          <button type="button" data-test="move-existing-left" :disabled="busy || index === 0" @click="moveExisting(index, -1)">左移</button>
          <button type="button" :disabled="busy || index === existingImages.length - 1" @click="moveExisting(index, 1)">右移</button>
          <button type="button" data-test="remove-existing-image" :disabled="busy" @click="removeExisting(image.id)">删除</button>
        </div>
      </article>
    </div>

    <div v-if="pendingImages.length" class="product-image-grid">
      <article
        v-for="(item, index) in pendingImages"
        :key="item.previewUrl"
        class="product-image-card pending"
        data-test="pending-image-card"
        draggable="true"
        @dragstart="startPendingDrag(index)"
        @drop.stop.prevent="finishPendingDrag(index)"
      >
        <div class="product-image-preview-wrap">
          <img data-test="pending-image-preview" :src="item.previewUrl" :alt="item.file.name" class="product-image-preview">
          <span v-if="index === 0 && !existingImages.length" data-test="primary-badge" class="product-image-primary">主图</span>
          <span class="product-image-pending">待上传</span>
        </div>
        <strong :title="item.file.name">{{ item.file.name }}</strong>
        <div class="product-image-controls">
          <button v-if="index > 0 && !existingImages.length" type="button" @click="setPendingPrimary(index)">设为主图</button>
          <button type="button" :disabled="index === 0" @click="movePending(index, -1)">左移</button>
          <button type="button" :disabled="index === pendingImages.length - 1" @click="movePending(index, 1)">右移</button>
          <button type="button" data-test="remove-pending-image" @click="removePending(index)">删除</button>
        </div>
      </article>
    </div>

    <p v-if="!totalCount" class="product-image-empty">拖入图片，或点击“选择图片”添加</p>
    <p class="product-image-total">已选择 {{ totalCount }} / {{ MAX_IMAGES }} 张</p>
  </section>
</template>
