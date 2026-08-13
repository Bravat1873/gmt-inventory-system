<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

export interface FuzzyPickerOption {
  id: number
  label: string
  searchText?: string
}

const props = withDefaults(defineProps<{
  modelValue: number | null
  options: FuzzyPickerOption[]
  placeholder: string
  disabled?: boolean
  emptyText?: string
}>(), {
  disabled: false,
  emptyText: '没有匹配的选项'
})

const emit = defineEmits<{ 'update:modelValue': [value: number | null] }>()

const query = ref('')
const open = ref(false)
const picker = ref<HTMLElement>()
const popupStyle = ref<Record<string, string>>({})

const selected = computed(() => props.options.find(option => option.id === props.modelValue))
const filteredOptions = computed(() => {
  const keyword = query.value.trim().toLocaleLowerCase()
  if (!keyword) return props.options
  return props.options.filter(option => `${option.label} ${option.searchText ?? ''}`.toLocaleLowerCase().includes(keyword))
})

watch(selected, option => {
  if (!open.value) query.value = option?.label ?? ''
}, { immediate: true })

watch([open, filteredOptions], () => {
  if (open.value) void nextTick(updatePopupPosition)
})

function updatePopupPosition() {
  const element = picker.value
  if (!element || !open.value) return
  const rect = element.getBoundingClientRect()
  if ((rect.width > 0 || rect.height > 0) && (rect.right <= 0 || rect.left >= window.innerWidth || rect.bottom <= 0 || rect.top >= window.innerHeight)) {
    open.value = false
    return
  }
  const gap = 4
  const edge = 8
  const spaceBelow = window.innerHeight - rect.bottom - edge - gap
  const spaceAbove = rect.top - edge - gap
  const showAbove = spaceBelow < 140 && spaceAbove > spaceBelow
  const availableHeight = Math.max(72, Math.min(224, showAbove ? spaceAbove : spaceBelow))
  popupStyle.value = {
    left: `${Math.max(edge, Math.min(rect.left, window.innerWidth - edge))}px`,
    width: `${Math.max(0, Math.min(rect.width, window.innerWidth - rect.left - edge))}px`,
    maxHeight: `${availableHeight}px`,
    top: showAbove ? 'auto' : `${rect.bottom + gap}px`,
    bottom: showAbove ? `${window.innerHeight - rect.top + gap}px` : 'auto'
  }
}

function showOptions() {
  if (props.disabled) return
  open.value = true
  void nextTick(updatePopupPosition)
}

function search() {
  if (selected.value?.label !== query.value) emit('update:modelValue', null)
  showOptions()
}

function choose(option: FuzzyPickerOption) {
  query.value = option.label
  emit('update:modelValue', option.id)
  open.value = false
}

function closeLater() {
  window.setTimeout(() => { open.value = false }, 120)
}

onMounted(() => {
  window.addEventListener('resize', updatePopupPosition)
  window.addEventListener('scroll', updatePopupPosition, true)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', updatePopupPosition)
  window.removeEventListener('scroll', updatePopupPosition, true)
})
</script>

<template>
  <div ref="picker" class="fuzzy-picker">
    <input
      v-model="query"
      type="search"
      autocomplete="off"
      role="combobox"
      :placeholder="placeholder"
      :disabled="disabled"
      :aria-expanded="open"
      @focus="showOptions"
      @blur="closeLater"
      @input="search"
    >
    <Teleport to="body">
      <div v-if="open" class="fuzzy-picker-options" :style="popupStyle" role="listbox">
      <button
        v-for="option in filteredOptions"
        :key="option.id"
        :data-test="`fuzzy-option-${option.id}`"
        type="button"
        role="option"
        :aria-selected="option.id === modelValue"
        @mousedown.prevent
        @click="choose(option)"
      >{{ option.label }}</button>
      <span v-if="!filteredOptions.length" class="fuzzy-picker-empty">{{ emptyText }}</span>
      </div>
    </Teleport>
  </div>
</template>
