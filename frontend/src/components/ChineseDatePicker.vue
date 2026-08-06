<script setup lang="ts">
import { computed, ref } from 'vue'

const props=withDefaults(defineProps<{modelValue:string;placeholder?:string;disabled?:boolean}>(),{placeholder:'请选择日期',disabled:false})
const emit=defineEmits<{ 'update:modelValue':[value:string] }>()
const input=ref<HTMLInputElement>()
const display=computed(()=>{if(!props.modelValue)return props.placeholder;const [year,month,day]=props.modelValue.split('-').map(Number);return `${year}年${month}月${day}日`})
function open(){if(props.disabled||!input.value)return;if(typeof input.value.showPicker==='function')input.value.showPicker();else input.value.focus()}
</script>
<template><div class="date-picker"><button type="button" class="date-display" :class="{empty:!modelValue}" :disabled="disabled" data-test="date-display" @click="open"><span>{{display}}</span><span class="date-display-icon" aria-hidden="true"></span></button><input ref="input" class="native-date-input" type="date" :value="modelValue" :disabled="disabled" @input="emit('update:modelValue',($event.target as HTMLInputElement).value)"></div></template>
