<script setup lang="ts">
import { ref } from 'vue'; import { login, type CurrentUser } from '../api/auth'
const emit=defineEmits<{loggedIn:[user:CurrentUser]}>(); const username=ref('admin'); const password=ref(''); const error=ref(''); const busy=ref(false)
async function submit(){busy.value=true;error.value='';try{emit('loggedIn',await login(username.value,password.value))}catch(e){error.value=e instanceof Error?e.message:'登录失败'}finally{busy.value=false}}
</script>
<template><main class="login-page"><form class="login-card" @submit.prevent="submit"><h1>GMT进销存系统</h1><p>请使用账号登录后继续操作</p><label>用户名<input v-model="username" autocomplete="username"/></label><label>密码<input v-model="password" type="password" autocomplete="current-password"/></label><p v-if="error" class="login-error">{{error}}</p><button class="primary-action" :disabled="busy">{{busy?'登录中…':'登录'}}</button></form></main></template>
