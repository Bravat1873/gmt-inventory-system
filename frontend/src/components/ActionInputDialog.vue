<script setup lang="ts">
import { ref } from 'vue'

const props=defineProps<{title:string;label:string;placeholder:string}>()
const emit=defineEmits<{close:[];confirm:[value:string]}>()
const value=ref('');const error=ref('')
function submit(){const text=value.value.trim();if(!text){error.value=`请填写${props.label}`;return}emit('confirm',text)}
</script>
<template><div class="dialog-mask"><section class="dialog-card action-input-dialog" role="dialog" aria-modal="true"><header><h2>{{title}}</h2><button type="button" @click="emit('close')">关闭</button></header><form novalidate @submit.prevent="submit"><label :class="{'field-invalid':Boolean(error)}"><span>{{label}} <small v-if="error" class="field-error">{{error}}</small></span><input v-model="value" :placeholder="placeholder" autofocus></label><footer><button type="button" class="secondary-action" @click="emit('close')">取消</button><button class="primary-action">确认</button></footer></form></section></div></template>
