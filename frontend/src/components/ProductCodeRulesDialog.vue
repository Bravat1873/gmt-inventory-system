<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { createProductCodeRule, deleteProductCodeRule, loadProductCodeRules, updateProductCodeRule, type ProductCodeRule } from '../api/workbench'

const emit = defineEmits<{ close: []; message: [text: string, kind?: 'success' | 'error'] }>()
const rules = ref<ProductCodeRule[]>([])
const category = ref('BRAND')
const form = reactive({ id: 0, code: '', displayName: '', sortOrder: 10, version: 0 })
const categories = [
  ['BRAND','品牌'],['SERIES','系列'],['BODY_COLOR','锁体颜色'],['LOCK_TYPE','锁体类型'],
  ['CONNECTIVITY','联网方式'],['SALES_CHANNEL','销售渠道'],['OPERATING_ENTITY','运营主体'],['LANGUAGE','语言'],
  ['DOOR_MODEL','成品型号'],['SECURITY_GRADE','安全等级'],['BASE_MATERIAL','主基材料'],['THICKNESS','成品厚度'],['FINISH_COLOR','花色'],
  ['SUFFIX','后缀']
] as const
const current = computed(() => rules.value.filter(rule => rule.category === category.value))

async function reload() { rules.value = await loadProductCodeRules() }
function reset() { Object.assign(form, { id: 0, code: '', displayName: '', sortOrder: 10, version: 0 }) }
function edit(rule: ProductCodeRule) { Object.assign(form, rule) }
async function save() {
  try {
    const body = { category: category.value, code: form.code, displayName: form.displayName, sortOrder: Number(form.sortOrder), enabled: true, version: form.version }
    if (form.id) await updateProductCodeRule(form.id, body); else await createProductCodeRule(body)
    await reload(); reset(); emit('message', '编码规则已保存')
  } catch (error) { emit('message', error instanceof Error ? error.message : '保存失败', 'error') }
}
async function remove(rule: ProductCodeRule) {
  if (!confirm(`确认删除“${rule.displayName}”吗？`)) return
  try { await deleteProductCodeRule(rule.id); await reload() } catch (error) { emit('message', error instanceof Error ? error.message : '删除失败', 'error') }
}
function formatDateTime(value?: string) { return value ? value.replace('T', ' ').slice(0, 19) : '—' }
onMounted(reload)
</script>

<template>
  <section class="module-page rule-management-page">
    <header class="module-heading">
      <div><h1>产品编号规则</h1><p class="module-description">维护产品编号各组成部分的可选值与显示顺序</p></div>
      <div class="heading-actions"><button class="secondary-action" @click="emit('close')">返回产品管理</button></div>
    </header>
    <div class="list-panel rule-panel">
      <div class="rule-layout">
        <nav class="rule-tabs" aria-label="规则分类">
          <button v-for="item in categories" :key="item[0]" :class="{ active: category === item[0] }" @click="category=item[0];reset()">{{ item[1] }}</button>
        </nav>
        <div class="rule-content">
          <form class="rule-form" @submit.prevent="save">
            <label for="product-rule-code"><span>规则代码</span><input id="product-rule-code" v-model.trim="form.code" placeholder="例如：P50" required></label>
            <label for="product-rule-name"><span>规则名称</span><input id="product-rule-name" v-model.trim="form.displayName" placeholder="请输入显示名称" required></label>
            <label for="product-rule-sort-order"><span>显示顺序</span><input id="product-rule-sort-order" v-model.number="form.sortOrder" type="number" placeholder="数值越小越靠前"></label>
            <div class="rule-form-actions"><button class="primary-action">{{ form.id ? '保存修改' : '新增规则' }}</button><button v-if="form.id" type="button" class="secondary-action" @click="reset">取消编辑</button></div>
          </form>
          <div class="rule-table-wrap">
            <table><thead><tr><th>代码</th><th>名称</th><th>显示顺序</th><th>修改时间</th><th>操作</th></tr></thead><tbody><tr v-for="rule in current" :key="rule.id"><td>{{ rule.code }}</td><td>{{ rule.displayName }}</td><td>{{ rule.sortOrder }}</td><td>{{ formatDateTime(rule.updatedAt) }}</td><td><button @click="edit(rule)">修改</button><button @click="remove(rule)">删除</button></td></tr><tr v-if="!current.length"><td colspan="5" class="empty-state">暂无规则</td></tr></tbody></table>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.rule-management-page { overflow: hidden; }
.rule-panel { overflow: hidden; }
.rule-layout { display: grid; grid-template-columns: 190px minmax(0, 1fr); gap: 20px; height: 100%; padding: 18px; min-height: 0; flex: 1; }
.rule-tabs { display: grid; gap: 7px; min-height: 0; align-content: start; overflow-y: auto; }
.rule-tabs button { min-height: 42px; padding: 9px 14px; border: 1px solid #d7d7d7; border-radius: 4px; background: #fafafa; text-align: left; }
.rule-tabs button:hover { border-color: #999; background: #f3f3f3; }
.rule-tabs .active { border-color: #111; background: #111; color: #fff; }
.rule-content { min-width: 0; min-height: 0; display: flex; flex-direction: column; }
.rule-form { display: grid; grid-template-columns: minmax(150px, .8fr) minmax(240px, 1.4fr) minmax(160px, .8fr); gap: 12px; align-items: end; margin-bottom: 16px; padding: 16px; border: 1px solid #e5e5e5; border-radius: 4px; background: #fafafa; }
.rule-form > label:not(.rule-enabled) { display: flex; min-width: 0; flex-direction: column; gap: 7px; color: #555; font-size: 13px; }
.rule-form input { min-width: 0; min-height: 40px; padding: 9px 12px; }
.rule-form-actions { display: flex; grid-column: 1 / -1; gap: 10px; }
.module-description { margin: 6px 0 0; color: #777; font-size: 13px; }
.rule-table-wrap { min-height: 0; flex: 1; overflow: auto; border: 1px solid #e5e5e5; border-radius: 4px; }
.rule-content table { width: 100%; border-collapse: collapse; }
.rule-content th, .rule-content td { height: 48px; padding: 10px 14px; border-bottom: 1px solid #eee; text-align: left; }
.rule-content th { position: sticky; top: 0; z-index: 1; background: #fafafa; color: #666; font-weight: 500; }
.rule-content td button { margin-right: 8px; }
@media (max-width: 900px) { .rule-layout { grid-template-columns: 1fr; } .rule-tabs { grid-template-columns: repeat(2,minmax(0,1fr)); } .rule-form { grid-template-columns: 1fr 1fr; } }
@media (max-width: 600px) { .rule-form { grid-template-columns: 1fr; } .rule-form-actions { grid-column: 1; } }
</style>
