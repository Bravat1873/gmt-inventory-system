<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import type { CurrentUser } from '../api/auth'
import { loadOperation, loadOperations, undoOperation, type OperationDetail, type OperationPage, type OperationRecord } from '../api/operation-records'
import { formatDateTimeToSecond } from '../utils/date-time'
import { operationFieldLabel, operationTableLabel, operationValue } from '../utils/operation-labels'

const props = defineProps<{ currentUser: CurrentUser }>()
const emit = defineEmits<{ message: [text: string, kind?: 'success' | 'error'] }>()
const data = ref<OperationPage>({ items: [], total: 0, page: 1, pageSize: 20 })
const filters = reactive({ keyword: '', actor: '', module: '', status: '', from: '', to: '' })
const loading = ref(false), detailLoading = ref(false), undoing = ref(false), error = ref(''), detailError = ref('')
const detail = ref<OperationDetail>(), reason = ref(''), confirming = ref(false), heading = ref<HTMLElement>()
let generation = 0, detailGeneration = 0
const statuses = { SUCCESS: '成功', REVERSED: '已撤回' }
const statusLabel = (status: OperationRecord['status']) => status === 'FAILED' ? '失败' : status === 'LEGACY' ? '历史日志' : statuses[status]
const types = { INSERT: '新增', UPDATE: '修改', DELETE: '删除' }
const modules = ['客户管理', '产品管理', '供应商管理', '订单管理', '库存管理', '采购管理', '财务管理', '资金管理', '售后管理', '用户管理', '导入管理', '操作记录']
const changePages = computed(() => detail.value ? Math.max(1, Math.ceil(detail.value.operation.changeCount / detail.value.changePageSize)) : 1)
const eligible = (row: OperationRecord) => row.status === 'SUCCESS' && row.changeCount > 0 && !row.reversesId && !row.nonReversibleReason && (props.currentUser.role === 'ADMIN' || row.actorId === props.currentUser.id)
const errorText = (cause: unknown) => cause instanceof Error ? cause.message : '请求失败，请重试'

async function load(page = 1) {
  const token = ++generation; loading.value = true; error.value = ''
  if (filters.from && filters.to && filters.from > filters.to) { error.value = '开始日期不能晚于结束日期'; loading.value = false; return }
  const params = new URLSearchParams({ page: String(page) })
  for (const [key, value] of Object.entries(filters)) if (value.trim()) params.set(key, value.trim())
  try { const result = await loadOperations(params); if (token === generation) data.value = result }
  catch (cause) { if (token === generation) error.value = errorText(cause) }
  finally { if (token === generation) loading.value = false }
}
async function open(id: number, confirm = false, page = 1) {
  const token = ++detailGeneration; detailLoading.value = true; detailError.value = ''; confirming.value = false
  try {
    const result = await loadOperation(id, page)
    if (token !== detailGeneration) return
    detail.value = result; reason.value = ''; confirming.value = confirm && result.canUndo
    await nextTick(); heading.value?.focus()
  } catch (cause) { if (token === detailGeneration) detailError.value = errorText(cause) }
  finally { if (token === detailGeneration) detailLoading.value = false }
}
function back() { if (undoing.value) return; ++detailGeneration; detailLoading.value = false; detail.value = undefined; confirming.value = false; detailError.value = '' }
async function undo() {
  if (!detail.value || undoing.value) return
  if (!reason.value.trim()) { detailError.value = '请填写撤回原因'; return }
  undoing.value = true; detailError.value = ''
  const id = detail.value.operation.id
  try {
    const result = await undoOperation(id, reason.value.trim())
    emit('message', `操作 #${id} 已撤回，关联变更已全部恢复。撤回记录 #${result.reversalOperationId}`)
    confirming.value = false; await open(id); await load(data.value.page)
  } catch (cause) {
    const message = errorText(cause)
    try { detail.value = await loadOperation(id) } catch { /* Keep the reviewed details visible. */ }
    confirming.value = false; detailError.value = message
  } finally { undoing.value = false }
}
onMounted(() => load())
</script>

<template>
  <section class="operations-page" :aria-busy="loading || detailLoading">
    <header class="operations-header">
      <div><h1>操作记录</h1><p>只记录实际数据变更，查看对应订单、单据和修改前后内容。</p></div>
      <button v-if="!detail" :disabled="loading" @click="load(data.page)">{{ loading ? '刷新中…' : '刷新记录' }}</button>
      <button v-else :disabled="undoing" @click="back">返回列表</button>
    </header>
    <p class="operations-note">本人可撤回自己的操作，管理员可撤回全部操作。后续业务已使用的数据，需要先处理对应操作。</p>
    <p v-if="detailLoading" role="status">正在读取关联变更…</p>
    <p v-if="detailError" class="form-error" role="alert">{{ detailError }}</p>

    <template v-if="!detail">
      <form class="operations-filters" @submit.prevent="load(1)">
        <label class="operations-keyword"><span>操作 / 关联单据</span><input v-model="filters.keyword" placeholder="操作编号、订单号、客户、产品等" /></label>
        <label><span>操作人员</span><input v-model="filters.actor" placeholder="姓名或用户名" /></label>
        <label><span>关联模块</span><select v-model="filters.module"><option value="">全部模块</option><option v-for="module in modules" :key="module">{{ module }}</option></select></label>
        <label><span>处理状态</span><select v-model="filters.status"><option value="">全部状态</option><option v-for="(label, key) in statuses" :key="key" :value="key">{{ label }}</option></select></label>
        <label><span>开始日期</span><input v-model="filters.from" type="date" /></label>
        <label><span>结束日期</span><input v-model="filters.to" type="date" /></label>
        <button type="submit" class="primary-action" :disabled="loading">查询</button>
        <button type="button" :disabled="loading" @click="Object.assign(filters, { keyword:'', actor:'', module:'', status:'', from:'', to:'' }); load(1)">重置</button>
      </form>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <div class="operations-table-scroll" tabindex="0" role="region" aria-label="操作记录列表，可横向滚动">
        <table class="operations-table">
          <colgroup><col style="width:14%" /><col style="width:11%" /><col style="width:20%" /><col style="width:34%" /><col style="width:8%" /><col style="width:13%" /></colgroup>
          <thead><tr><th scope="col">编号 / 时间</th><th scope="col">操作人员</th><th scope="col">操作内容</th><th scope="col">订单 / 单据及关联信息</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead>
          <tbody><tr v-for="row in data.items" :key="row.id">
            <td><strong>#{{ row.id }}</strong><small>{{ formatDateTimeToSecond(row.operatedAt) }}</small></td>
            <td>{{ row.actorName }}<small>{{ row.actorUsername || '—' }}</small></td>
            <td class="operations-content">{{ row.action }}<small>{{ row.changeCount }} 条数据变更</small><small v-for="(summary, index) in row.changeSummary" :key="index">{{ operationTableLabel(summary.table) }} · {{ types[summary.type] }} {{ summary.count }} 条<template v-if="summary.type === 'UPDATE'">：{{ summary.fields.map(operationFieldLabel).join('、') }}</template></small></td>
            <td class="operations-related">{{ row.affectedModules || row.moduleName }}<small :title="row.relatedRecords">{{ row.relatedRecords || '—' }}</small></td>
            <td><span class="operation-status" :class="`operation-${row.status.toLowerCase()}`">{{ statusLabel(row.status) }}</span></td>
            <td><div class="operations-row-actions"><button :disabled="detailLoading" @click="open(row.id)">详情</button><button :disabled="detailLoading || !eligible(row)" :title="row.nonReversibleReason || '查看关联变更并检查是否可撤回'" @click="open(row.id, true)">撤回</button></div></td>
          </tr><tr v-if="!data.items.length"><td colspan="6" class="operations-empty">{{ loading ? '正在读取操作记录…' : error ? '读取失败，请重试' : '暂无符合条件的操作记录' }}</td></tr></tbody>
        </table>
      </div>
      <footer class="operations-pagination"><span>共 {{ data.total }} 条 · 第 {{ data.page }} 页</span><button :disabled="loading || data.page <= 1" @click="load(data.page - 1)">上一页</button><button :disabled="loading || data.page * data.pageSize >= data.total" @click="load(data.page + 1)">下一页</button></footer>
    </template>

    <article v-else class="operation-detail">
      <header class="operation-detail-heading"><div><h2 ref="heading" tabindex="-1">#{{ detail.operation.id }} · {{ detail.operation.action }}</h2><p>{{ detail.operation.actorName }}（{{ detail.operation.actorUsername || '未保存账号' }}） · {{ formatDateTimeToSecond(detail.operation.operatedAt) }}</p></div><span class="operation-status" :class="`operation-${detail.operation.status.toLowerCase()}`">{{ statusLabel(detail.operation.status) }}</span></header>
      <dl class="operation-facts"><div><dt>关联模块</dt><dd>{{ detail.operation.affectedModules || detail.operation.moduleName }}</dd></div><div><dt>订单 / 单据及关联信息</dt><dd>{{ detail.operation.relatedRecords || '—' }}</dd></div><div><dt>数据变更</dt><dd>{{ detail.operation.changeCount }} 条</dd></div><div><dt>来源地址</dt><dd>{{ detail.operation.clientIp || '—' }}</dd></div></dl>
      <p v-if="detail.operation.resultMessage" class="form-error">{{ detail.operation.resultMessage }}</p>
      <p v-if="detail.operation.reversalReason">撤回原因：{{ detail.operation.reversalReason }}</p>
      <p v-if="detail.operation.reversesId">原操作：<button class="text-action" :disabled="undoing" @click="open(detail.operation.reversesId)">#{{ detail.operation.reversesId }} 查看原操作</button></p>
      <p v-if="detail.operation.reversedByOperationId">撤回记录：<button class="text-action" :disabled="undoing" @click="open(detail.operation.reversedByOperationId)">#{{ detail.operation.reversedByOperationId }} 查看撤回人员、时间和原因</button></p>
      <div class="operation-undo-area">
        <template v-if="detail.canUndo">
          <button v-if="!confirming" class="primary-action" :disabled="detailLoading || undoing" @click="confirming = true; reason = ''; detailError = ''">撤回此次操作</button>
          <form v-else @submit.prevent="undo">
            <h3>确认撤回整次操作</h3>
            <p>将恢复「{{ detail.operation.affectedModules }}」中的 {{ detail.operation.changeCount }} 条变更。关联模块会一起恢复，单号不会重复使用。</p>
            <label><span>撤回原因</span><textarea v-model="reason" maxlength="1000" rows="3" :disabled="undoing" required placeholder="说明为什么需要撤回，原因将保存在新的操作记录中"></textarea></label>
            <div class="operations-row-actions"><button type="submit" class="primary-action" :disabled="undoing || detailLoading">{{ undoing ? '正在撤回全部关联变更…' : '确认撤回整次操作' }}</button><button type="button" :disabled="undoing" @click="confirming = false">取消</button></div>
          </form>
        </template>
        <p v-else>{{ detail.undoReason }}</p>
        <ul v-if="detail.blockers.length" class="operation-blockers"><li v-for="blocker in detail.blockers" :key="blocker.id"><button class="text-action" :disabled="undoing" @click="open(blocker.id)">#{{ blocker.id }} · {{ blocker.action }}</button><span> · {{ blocker.actorName }}</span></li></ul>
      </div>
      <h3>关联变更明细</h3>
      <p class="operations-note">按执行顺序展示每条变更；同一条数据在本次操作中可能被多次更新。</p>
      <p v-if="!detail.changes.length" class="operations-empty">{{ detail.operation.status === 'LEGACY' ? '旧日志没有保存变更前后值，无法补还原始操作。' : '此次操作没有产生数据变更。' }}</p>
      <section v-for="change in detail.changes" :key="change.id" class="operation-change">
        <header><h4>{{ change.module }} · {{ operationTableLabel(change.table) }} <span class="operation-change-type">{{ types[change.type] }}</span></h4><p class="operation-business-info">{{ change.relatedInfo || change.label }}</p><small>记录标识：{{ change.recordKey }}</small></header>
        <p v-if="change.preserved" class="operations-note">单号分配记录会保留，撤回后不会重复使用已分配的单号。</p>
        <div class="operations-table-scroll" tabindex="0" role="region" :aria-label="`${change.label}变更前后对比`"><table class="operations-diff"><thead><tr><th scope="col">字段</th><th scope="col">操作前</th><th scope="col">操作后</th></tr></thead><tbody><tr v-for="field in change.fields" :key="field.field"><th scope="row" :title="field.field">{{ operationFieldLabel(field.field) }}</th><td>{{ operationValue(field.before) }}</td><td>{{ operationValue(field.after) }}</td></tr><tr v-if="!change.fields.length"><td colspan="3">字段值未改变</td></tr></tbody></table></div>
      </section>
      <footer v-if="changePages > 1" class="operations-pagination"><span>变更明细 {{ detail.changePage }} / {{ changePages }} 页</span><button :disabled="detailLoading || undoing || detail.changePage <= 1" @click="open(detail.operation.id, false, detail.changePage - 1)">上一页明细</button><button :disabled="detailLoading || undoing || detail.changePage >= changePages" @click="open(detail.operation.id, false, detail.changePage + 1)">下一页明细</button></footer>
      <details class="operation-technical"><summary>接口信息</summary><p>{{ detail.operation.httpMethod }} {{ detail.operation.requestPath }}</p></details>
    </article>
  </section>
</template>

<style scoped>
.operations-page { color: #25344a; min-width: 0; }
.operations-header, .operation-detail-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; margin-bottom: 18px; }
.operations-header h1 { margin: 0 0 10px; font-size: 26px; }
.operations-header p, .operation-detail-heading p { color: #536176; margin: 0; line-height: 1.6; }
.operations-page button { min-height: 36px; padding: 7px 13px; white-space: nowrap; }
.operations-page button:disabled { cursor: not-allowed; opacity: .55; }
.operations-page :is(button, input, select, textarea, summary, [tabindex]):focus-visible { outline: 2px solid #286bb1; outline-offset: 3px; }
.operations-page ::selection { background: #d5e8fa; color: #183957; }
.operations-note { color: #536176; font-size: 13px; line-height: 1.7; }
.operations-filters { display: flex; align-items: flex-end; flex-wrap: wrap; gap: 14px; padding: 18px 0 22px; }
.operations-page label { display: flex; flex-direction: column; gap: 7px; font-size: 13px; }
.operations-filters label { flex: 1 1 140px; }
.operations-filters .operations-keyword { flex-basis: 240px; }
.operations-page :is(input, select, textarea) { width: 100%; min-width: 0; padding: 9px 10px; border: 1px solid #aebac9; border-radius: 6px; background: #fff; color: #25344a; font: inherit; caret-color: #286bb1; }
.operations-page input::placeholder, .operations-page textarea::placeholder { color: #67758a; }
.operations-table-scroll { max-width: 100%; overflow: auto; scrollbar-color: #aab7c8 #eef2f6; }
.operations-table, .operations-diff { width: 100%; border-collapse: collapse; background: #fff; font-size: 13px; font-variant-numeric: tabular-nums; text-align: left; }
.operations-table { min-width: 850px; }
.operations-page :is(td, th) { padding: 14px 12px; border-bottom: 1px solid #dfe6ed; vertical-align: top; line-height: 1.6; }
.operations-page thead th { background: #f1f5f9; color: #43546b; font-weight: 600; }
.operations-table tbody tr:hover { background: #f6f9fc; }
.operations-table small { display: block; color: #5b6a7e; margin-top: 4px; }
.operations-related { max-width: 320px; overflow-wrap: anywhere; }
.operations-content { max-width: 300px; overflow-wrap: anywhere; }
.operations-related small { white-space: normal; }
.operation-change header small { color: #67758a; font-size: 11px; }
.operations-row-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.operation-status { display: inline-block; padding: 3px 9px; border-radius: 5px; font-size: 12px; white-space: nowrap; background: #edf1f5; color: #42546b; }
.operation-success { background: #e5f3ec; color: #216044; }
.operation-failed { background: #fbeae9; color: #a6342d; }
.operation-reversed { background: #fff1da; color: #805716; }
.operations-pagination { display: flex; align-items: center; gap: 10px; padding: 20px 0; }
.operations-pagination span { margin-right: auto; color: #536176; font-size: 13px; }
.operations-empty { padding: 36px 16px !important; text-align: center; color: #5b6a7e; }
.operation-detail { background: #fff; border: 1px solid #dfe6ed; border-radius: 12px; padding: 24px; }
.operation-detail h2 { margin: 0 0 8px; font-size: 21px; }
.operation-facts { display: grid; grid-template-columns: 1fr 1fr; gap: 18px 28px; margin: 24px 0; }
.operation-facts dt { color: #5b6a7e; font-size: 12px; margin-bottom: 6px; }
.operation-facts dd { margin: 0; font-size: 14px; overflow-wrap: anywhere; line-height: 1.7; }
.operation-undo-area { background: #f5f8fb; padding: 18px; border-radius: 8px; margin: 22px 0 30px; }
.operation-undo-area p { margin: 0 0 14px; line-height: 1.7; }
.operation-undo-area p:last-child { margin-bottom: 0; }
.operation-undo-area h3 { margin-top: 0; }
.operation-undo-area textarea { margin-bottom: 14px; resize: vertical; }
.operation-blockers { padding-left: 18px; margin-bottom: 0; }
.operation-change { margin-top: 26px; }
.operation-change header { margin-bottom: 10px; }
.operation-change h4 { margin: 0 0 7px; font-size: 15px; }
.operation-change header p { font-size: 12px; color: #5b6a7e; margin: 0; overflow-wrap: anywhere; }
.operation-change-type { color: #536176; font-weight: 400; margin-left: 8px; }
.operations-diff { table-layout: fixed; min-width: 480px; }
.operations-diff :is(td, th) { overflow-wrap: anywhere; white-space: pre-wrap; }
.operations-diff th:first-child { width: 24%; }
.operation-technical { margin-top: 24px; font-size: 12px; color: #5b6a7e; overflow-wrap: anywhere; }
.operation-technical summary { cursor: pointer; }
@media (max-width: 700px) { .operations-header { gap: 12px; } .operations-header h1 { font-size: 22px; } .operations-header p { font-size: 13px; } .operations-filters { gap: 12px; } .operations-filters label { flex-basis: 44%; } .operations-filters .operations-keyword { flex-basis: 100%; } .operation-detail { padding: 16px; } .operation-detail-heading { flex-direction: column; } .operation-detail h2 { font-size: 18px; line-height: 1.5; } .operation-facts { grid-template-columns: 1fr; } .operation-undo-area { padding: 14px; } .operations-pagination { flex-wrap: wrap; } }
@media (max-width: 700px) {
  :global(.app-shell.operations-active) { display: flex; flex-direction: column; overflow: hidden; }
  :global(.operations-active .sidebar) { position: static; width: 100%; padding: 8px 12px; border-right: 0; border-bottom: 1px solid #dfe6ed; }
  :global(.operations-active .sidebar::before), :global(.operations-active .sidebar::after) { display: none; }
  :global(.operations-active .nav-list) { margin-top: 0; flex-direction: row; overflow-x: auto; gap: 4px; }
  :global(.operations-active .nav-item), :global(.operations-active .nav-subitem) { flex: 0 0 auto; width: auto; white-space: nowrap; padding: 10px 12px; }
  :global(.operations-active .current-user) { position: static; justify-content: flex-end; padding: 8px 16px; background: #fff; }
  :global(.operations-active main) { height: auto; flex: 1; min-height: 0; }
  :global(.operations-active .content) { padding: 20px 16px 0; }
  .operations-header { flex-wrap: wrap; }
  .operations-header > div { flex: 1 1 240px; }
}
</style>
