<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElTooltip } from 'element-plus'

const props = defineProps<{ value: unknown }>()
const target = ref<HTMLElement>()
const truncated = ref(false)
const displayValue = computed(() => props.value == null || props.value === '' ? '—' : String(props.value))

function measure() {
  const element = target.value
  truncated.value = Boolean(element && (element.scrollWidth > element.clientWidth || element.scrollHeight > element.clientHeight))
}

let observer: ResizeObserver | undefined
onMounted(() => {
  nextTick(measure)
  window.addEventListener('resize', measure)
  if (typeof ResizeObserver !== 'undefined' && target.value) {
    observer = new ResizeObserver(measure)
    observer.observe(target.value)
  }
})
watch(displayValue, () => nextTick(measure))
onBeforeUnmount(() => { window.removeEventListener('resize', measure); observer?.disconnect() })
</script>

<template>
  <ElTooltip :content="displayValue" :disabled="!truncated" placement="top" :show-after="250" popper-class="overflow-text-popper">
    <span ref="target" class="overflow-text" data-test="overflow-text" :tabindex="truncated ? 0 : undefined">{{ displayValue }}</span>
  </ElTooltip>
</template>
