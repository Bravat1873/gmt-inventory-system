<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { loadProductImages, type ProductImage } from '../api/workbench'

const props = defineProps<{ productId: number; initialImageId?: number }>()
const emit = defineEmits<{ close: [] }>()

const images = ref<ProductImage[]>([])
const selectedIndex = ref(0)
const loading = ref(true)
const error = ref('')

const selectedImage = computed(() => images.value[selectedIndex.value])
const navigationDisabled = computed(() => images.value.length <= 1)
const imageAlt = computed(() => selectedImage.value
  ? `产品图片 ${selectedIndex.value + 1}/${images.value.length}：${selectedImage.value.originalFilename}`
  : '产品图片')

onMounted(async () => {
  try {
    images.value = await loadProductImages(props.productId)
    const requestedIndex = props.initialImageId == null
      ? -1
      : images.value.findIndex(image => image.id === props.initialImageId)
    const primaryIndex = images.value.findIndex(image => image.primary)
    selectedIndex.value = requestedIndex >= 0 ? requestedIndex : Math.max(0, primaryIndex)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '图片加载失败'
  } finally {
    loading.value = false
  }
})

function previous() {
  if (navigationDisabled.value) return
  selectedIndex.value = (selectedIndex.value - 1 + images.value.length) % images.value.length
}

function next() {
  if (navigationDisabled.value) return
  selectedIndex.value = (selectedIndex.value + 1) % images.value.length
}
</script>

<template>
  <div class="dialog-mask product-gallery-mask" @click.self="emit('close')">
    <section
      class="dialog-card product-gallery-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="product-gallery-title"
    >
      <header class="product-gallery-header">
        <div>
          <h2 id="product-gallery-title">产品图库</h2>
          <p>{{ images.length ? `共 ${images.length} 张图片` : '查看产品图片' }}</p>
        </div>
        <button type="button" aria-label="关闭产品图库" @click="emit('close')">关闭</button>
      </header>

      <div class="product-gallery-content">
        <p v-if="loading" class="product-gallery-state">正在加载图片…</p>
        <p v-else-if="error" class="product-gallery-state product-gallery-error">{{ error }}</p>
        <p v-else-if="!images.length" class="product-gallery-state">该产品暂未上传图片</p>

        <template v-else-if="selectedImage">
          <div class="product-gallery-stage">
            <button
              type="button"
              class="product-gallery-navigation product-gallery-previous"
              aria-label="上一张图片"
              :disabled="navigationDisabled"
              @click="previous"
            >
              ‹
            </button>
            <img
              data-test="gallery-main-image"
              :src="selectedImage.contentUrl"
              :alt="imageAlt"
            >
            <button
              type="button"
              class="product-gallery-navigation product-gallery-next"
              aria-label="下一张图片"
              :disabled="navigationDisabled"
              @click="next"
            >
              ›
            </button>
          </div>

          <div class="product-gallery-caption">
            <strong>{{ selectedImage.originalFilename }}</strong>
            <span v-if="selectedImage.primary" class="product-gallery-primary">主图</span>
            <span>{{ selectedIndex + 1 }} / {{ images.length }}</span>
          </div>

          <div class="product-gallery-thumbnails" aria-label="产品图片缩略图">
            <button
              v-for="(image, index) in images"
              :key="image.id"
              type="button"
              :class="{ active: index === selectedIndex }"
              :aria-label="`查看图片 ${image.originalFilename}`"
              :aria-current="index === selectedIndex ? 'true' : undefined"
              @click="selectedIndex = index"
            >
              <img :src="image.contentUrl" :alt="`缩略图：${image.originalFilename}`">
              <span v-if="image.primary">主图</span>
            </button>
          </div>
        </template>
      </div>
    </section>
  </div>
</template>
