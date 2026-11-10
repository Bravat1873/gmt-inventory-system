<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import type { CurrentUser } from '../api/auth'
import { confirmOperation, loadOperation, loadOperations, undoOperation, type OperationDetail, type OperationPage, type OperationRecord } from '../api/operation-records'
import { formatDateTimeToSecond } from '../utils/date-time'
import { businessLines, businessModule, changeText, primaryDocument } from '../utils/operation-presentation'

const props = defineProps<{ currentUser: CurrentUser }>()
const emit = defineEmits<{ message: [text: string, kind?: 'success' | 'error'] }>()
const data = ref<OperationPage>({ items: [], total: 0, page: 1, pageSize: 20 })
const filters = reactive({ keyword: '', actor: '', module: '', status: '', from: '', to: '' })
const loading = ref(false), detailLoading = ref(false), undoing = ref(false), error = ref(''), detailError = ref('')
const detail = ref<OperationDetail>(), reason = ref(''), confirming = ref(false), heading = ref<HTMLElement>()
let generation = 0, detailGeneration = 0
const statuses = { SUCCESS: '已执行', REVERSED: '已撤回' }
const statusLabel = (status: OperationRecord['status']) => status === 'FAILED' ? '失败' : status === 'LEGACY' ? '历史日志' : statuses[status]
const expanded = ref(false)
const lines = computed(() => detail.value ? businessLines(detail.value) : [])
const modules = ['客户管理', '产品管理', '供应商管理', '订单管理', '库存管理', '采购管理', '财务管理', '资金管理', '售后管理', '用户管理', '导入管理', '操作记录']
const changePages = computed(() => detail.value ? Math.max(1, Math.ceil(detail.value.operation.changeCount / detail.value.changePageSize)) : 1)
const hiddenCount = computed(() => Math.max(0, lines.value.length-3) + lines.value.slice(0,3).reduce((n, row) => n+Math.max(0,row.parts.length-2),0))
const eligible = (row: OperationRecord) => row.status === 'SUCCESS' && row.changeCount > 0 && !row.confirmedAt && !row.reversesId && !row.nonReversibleReason && (props.currentUser.role === 'ADMIN' || row.actorId === props.currentUser.id)
const errorText = (cause: unknown) => cause instanceof Error ? cause.message : '请求失败，请重试'

const moreFilters = ref(false)
const lockTarget = ref<OperationRecord>()
const locking = ref(false)
const cancelButton = ref<HTMLButtonElement>(), lockButton = ref<HTMLButtonElement>()
let confirmTrigger: HTMLElement | null = null
watch(lockTarget, async target => {
  if (target) {
    confirmTrigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
    await nextTick()
    cancelButton.value?.focus()
  } else confirmTrigger?.focus()
})
const ownerOrAdmin = (row: OperationRecord) => props.currentUser.role === 'ADMIN' || row.actorId === props.currentUser.id
const canConfirm = (row: OperationRecord) => row.status === 'SUCCESS' && !row.confirmedAt && ownerOrAdmin(row)
const listRows = computed(() => data.value.items.map(row => ({
  ...row, document: primaryDocument(row), module: businessModule(row), description: changeText(row)
})))

async function lockRecord() {
  if (!lockTarget.value || locking.value) return
  locking.value = true
  try {
    await confirmOperation(lockTarget.value.id)
    lockTarget.value = undefined
    emit('message', '已确认锁定，此操作不能再撤回')
    await load(data.value.page)
  } catch (cause) {
    lockTarget.value = undefined
    await load(data.value.page)
    error.value = errorText(cause)
  } finally { locking.value = false }
}

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
    detail.value = result; expanded.value = false; reason.value = ''; confirming.value = confirm && result.canUndo
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
      <div><h1>操作记录</h1></div>
      <button v-if="!detail" :disabled="loading" @click="load(data.page)">{{ loading ? '刷新中…' : '刷新记录' }}</button>
      <button v-else :disabled="undoing" @click="back">返回列表</button>
    </header>
    <p v-if="detailLoading" role="status">正在读取关联变更…</p>
    <p v-if="detailError" class="form-error" role="alert">{{ detailError }}</p>

    <template v-if="!detail">
      <form class="operations-filters" @submit.prevent="load(1)">
        <label class="operations-keyword"><span>搜索记录</span><input v-model="filters.keyword" placeholder="操作编号、订单号、客户、产品等" /></label>
        <label><span>操作模块</span><select v-model="filters.module"><option value="">全部模块</option><option v-for="module in modules" :key="module">{{ module }}</option></select></label>
        <template v-if="moreFilters">
          <label><span>操作人员</span><input v-model="filters.actor" placeholder="姓名或用户名" /></label>
          <label><span>记录状态</span><select v-model="filters.status"><option value="">全部状态</option><option value="UNCONFIRMED">待确认</option><option value="CONFIRMED">已确认</option><option value="REVERSED">已撤回</option></select></label>
          <label><span>开始日期</span><input v-model="filters.from" type="date" /></label>
          <label><span>结束日期</span><input v-model="filters.to" type="date" /></label>
        </template>
        <button type="submit" class="primary-action" :disabled="loading">查询</button>
        <button type="button" :aria-expanded="moreFilters" @click="moreFilters = !moreFilters">{{ moreFilters ? '收起筛选' : '更多筛选' }}</button>
        <button v-if="Object.values(filters).some(Boolean)" type="button" :disabled="loading" @click="Object.assign(filters, { keyword:'', actor:'', module:'', status:'', from:'', to:'' }); load(1)">重置</button>
      </form>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <div class="operations-table-scroll" tabindex="0" role="region" aria-label="操作记录列表，可横向滚动">
        <table class="operations-table">
          <colgroup><col style="width:15%" /><col style="width:9%" /><col style="width:10%" /><col style="width:17%" /><col style="width:33%" /><col style="width:16%" /></colgroup>
          <thead><tr><th scope="col">操作时间</th><th scope="col">操作人员</th><th scope="col">操作模块</th><th scope="col">单号 / 对象</th><th scope="col">改了什么</th><th scope="col">操作</th></tr></thead>
          <tbody><tr v-for="row in listRows" :key="row.id">
            <td class="operations-time"><time :datetime="row.operatedAt">{{ formatDateTimeToSecond(row.operatedAt) }}</time></td>
            <td>{{ row.actorName || row.actorUsername || '—' }}</td>
            <td>{{ row.module }}</td>
            <td class="operations-related"><span :title="row.relatedRecords">{{ row.document }}</span></td>
            <td class="operations-content"><button class="change-link" :title="'查看完整变更：'+row.description" :disabled="detailLoading || locking" @click="open(row.id)">{{ row.description }}</button></td>
            <td><div class="operations-row-actions">
              <span v-if="row.status === 'REVERSED'" class="operation-status operation-reversed">已撤回</span>
              <span v-else-if="row.confirmedAt" class="operation-status operation-success" :title="row.confirmedByName+' · '+formatDateTimeToSecond(row.confirmedAt)">已确认锁定</span>
              <template v-else>
                <button :disabled="locking || detailLoading || !canConfirm(row)" @click="lockTarget = row">确认</button>
                <button class="record-undo-action" :disabled="locking || detailLoading || !eligible(row)" @click="open(row.id, true)">撤回</button>
              </template>
            </div></td>
          </tr><tr v-if="!data.items.length"><td colspan="6" class="operations-empty">{{ loading ? '正在读取操作记录…' : error ? '读取失败，请重试' : '暂无符合条件的操作记录' }}</td></tr></tbody>
        </table>
      </div>
      <footer class="operations-pagination"><span>共 {{ data.total }} 条 · 第 {{ data.page }} 页</span><button :disabled="loading || data.page <= 1" @click="load(data.page - 1)">上一页</button><button :disabled="loading || data.page * data.pageSize >= data.total" @click="load(data.page + 1)">下一页</button></footer>
      <div v-if="lockTarget" class="dialog-mask" @keydown.esc="!locking && (lockTarget = undefined)">
        <section role="alertdialog" aria-modal="true" aria-labelledby="record-confirm-title" class="confirm-card" @keydown.tab.prevent="($event.target === cancelButton ? lockButton : cancelButton)?.focus()">
          <h2 id="record-confirm-title">确认并锁定这条记录？</h2>
          <p>{{ primaryDocument(lockTarget) }} · {{ changeText(lockTarget) }}</p>
          <p>确认后，这笔操作和关联模块的变更都不能再通过此记录撤回。</p>
          <div class="operations-row-actions"><button ref="cancelButton" :disabled="locking" @click="lockTarget = undefined">取消</button><button ref="lockButton" class="primary-action" :disabled="locking" @click="lockRecord">{{ locking ? '确认中…' : '确认锁定' }}</button></div>
        </section>
      </div>
    </template>

    <article v-else class="operation-detail">
      <header class="operation-detail-heading"><div><h2 ref="heading" tabindex="-1">{{ primaryDocument(detail.operation) }} · {{ detail.operation.action.split(' · ').pop() }}</h2><p>{{ formatDateTimeToSecond(detail.operation.operatedAt) }}　{{ detail.operation.actorName }}　{{ businessModule(detail.operation) }}</p></div><span class="operation-status" :class="`operation-${detail.operation.status.toLowerCase()}`">{{ detail.operation.confirmedAt ? '已确认锁定' : statusLabel(detail.operation.status) }}</span></header>
      <section class="business-detail" aria-label="本次变化">
        <h3>本次变化</h3>
        <ul v-if="lines.length"><li v-for="item in (expanded ? lines : lines.slice(0, 3))" :key="item.id"><strong>{{ item.subject }}</strong><span>{{ (expanded ? item.parts : item.parts.slice(0, 2)).join('；') }}</span></li></ul>
        <p v-else>{{ detail.operation.status === 'LEGACY' ? '旧记录没有保存变更明细。' : changePages > 1 ? '本页无业务字段变化。' : changeText(detail.operation) }}</p>
        <button v-if="hiddenCount" type="button" :aria-expanded="expanded" @click="expanded = !expanded">{{ expanded ? '收起' : '查看其余变化' }}</button>
      </section>
      <p v-if="detail.operation.resultMessage" class="form-error">{{ detail.operation.resultMessage }}</p>
      <p v-if="detail.operation.reversalReason">撤回原因：{{ detail.operation.reversalReason }}</p>
      <p v-if="detail.operation.reversesId">原操作：<button class="text-action" :disabled="undoing" @click="open(detail.operation.reversesId)">#{{ detail.operation.reversesId }} 查看原操作</button></p>
      <p v-if="detail.operation.reversedByOperationId">撤回记录：<button class="text-action" :disabled="undoing" @click="open(detail.operation.reversedByOperationId)">#{{ detail.operation.reversedByOperationId }} 查看撤回人员、时间和原因</button></p>
      <footer v-if="changePages > 1" class="operations-pagination"><span>变更明细 {{ detail.changePage }} / {{ changePages }} 页</span><button :disabled="detailLoading || undoing || detail.changePage <= 1" @click="open(detail.operation.id, false, detail.changePage - 1)">上一页明细</button><button :disabled="detailLoading || undoing || detail.changePage >= changePages" @click="open(detail.operation.id, false, detail.changePage + 1)">下一页明细</button></footer>
      <div class="operation-undo-area">
        <template v-if="detail.canUndo">
          <button v-if="!confirming" class="primary-action" :disabled="detailLoading || undoing" @click="confirming = true; reason = ''; detailError = ''">撤回此次操作</button>
          <form v-else @submit.prevent="undo">
            <h3>确认撤回整次操作</h3>
            <p>撤回将同时恢复关联模块的变化，已分配的单号会保留。</p>
            <label><span>撤回原因</span><textarea v-model="reason" maxlength="1000" rows="3" :disabled="undoing" required placeholder="说明为什么需要撤回，原因将保存在新的操作记录中"></textarea></label>
            <div class="operations-row-actions"><button type="submit" class="primary-action" :disabled="undoing || detailLoading">{{ undoing ? '正在撤回全部关联变更…' : '确认撤回整次操作' }}</button><button type="button" :disabled="undoing" @click="confirming = false">取消</button></div>
          </form>
        </template>
        <p v-else>{{ detail.undoReason }}</p>
        <ul v-if="detail.blockers.length" class="operation-blockers"><li v-for="blocker in detail.blockers" :key="blocker.id"><button class="text-action" :disabled="undoing" @click="open(blocker.id)">#{{ blocker.id }} · {{ blocker.action }}</button><span> · {{ blocker.actorName }}</span></li></ul>
      </div>
    </article>
  </section>
</template>

<style scoped>
.operations-page { color: #262a30; min-width: 0; }
.operations-header, .operation-detail-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; margin-bottom: 18px; }
.operations-header h1 { margin: 0; font-size: 26px; font-weight: 650; }
.operations-header p, .operation-detail-heading p { color: #536176; margin: 0; line-height: 1.6; }
.operations-page button { min-height: 36px; padding: 7px 13px; white-space: nowrap; border: 1px solid #d3d7dc; border-radius: 6px; background: #fff; color: #373d45; font: inherit; font-size: 13px; cursor: pointer; transition: background-color .15s ease, border-color .15s ease; }
.operations-page button:hover:not(:disabled) { background: #f3f4f5; border-color: #9da5af; }
.operations-page button.primary-action { background: #22262c; border-color: #22262c; color: #fff; }
.operations-page button.primary-action:hover:not(:disabled) { background: #3b4149; }
.operations-page button:disabled { cursor: not-allowed; opacity: .55; }
.operations-page :is(button, input, select, textarea, summary, [tabindex]):focus-visible { outline: 2px solid #286bb1; outline-offset: 3px; }
.operations-page ::selection { background: #d5e8fa; color: #183957; }
.operations-note { color: #536176; font-size: 13px; line-height: 1.7; }
.operations-filters { display: flex; align-items: flex-end; flex-wrap: wrap; gap: 12px; padding: 18px; margin-bottom: 20px; border: 1px solid #e2e5e9; border-radius: 10px; background: #fff; }
.operations-page label { display: flex; flex-direction: column; gap: 7px; font-size: 13px; }
.operations-filters label { flex: 1 1 140px; }
.operations-filters .operations-keyword { flex-basis: 240px; }
.operations-page :is(input, select, textarea) { width: 100%; min-width: 0; padding: 9px 10px; border: 1px solid #d3d7dc; border-radius: 6px; background: #fff; color: #25344a; font: inherit; caret-color: #286bb1; }
.operations-page input::placeholder, .operations-page textarea::placeholder { color: #67758a; }
.operations-table-scroll { max-width: 100%; overflow: auto; scrollbar-color: #aab7c8 #eef2f6; }
.operations-table { width: 100%; border-collapse: collapse; background: #fff; font-size: 13px; font-variant-numeric: tabular-nums; text-align: left; }
.operations-table { min-width: 1000px; table-layout: fixed; font-size: 14px; }
.operations-table-scroll:has(> .operations-table) { border: 1px solid #e2e5e9; border-radius: 10px; }
.operations-table tbody td { padding: 10px 16px; vertical-align: middle; }
.operations-table thead th { padding: 13px 16px; font-size: 13px; background: #f8f9fa; color: #626a74; }
.operations-table tbody tr:last-child td { border-bottom: 0; }
.operations-time { white-space: normal; color: #626a74; font-size: 13px; }
.operations-actor, .operations-content { font-weight: 500; }
.operations-table .operations-row-actions { flex-wrap: nowrap; gap: 4px; }
.operations-row-actions .record-detail-action, .operations-row-actions .record-undo-action { border-color: transparent; background: transparent; padding: 6px 8px; }
.operations-row-actions .record-detail-action { color: #245c91; }
.operations-row-actions .record-undo-action { color: #626a74; }
.operations-page :is(td, th) { padding: 14px 12px; border-bottom: 1px solid #dfe6ed; vertical-align: top; line-height: 1.6; }
.operations-page thead th { background: #f1f5f9; color: #43546b; font-weight: 600; }
.operations-table .operations-content { white-space: normal; }
.operations-content .change-link { display:block; text-align:left; white-space:normal; overflow-wrap:anywhere; border:0; background:transparent; padding:0; min-height:0; color:#253853; line-height:1.6; }
.operations-content .change-link:hover:not(:disabled) { text-decoration:underline; background:transparent; }
.confirm-card { width:min(420px, 94vw); padding:22px; background:#fff; border-radius:10px; box-shadow:0 12px 48px #18283a26; }
.confirm-card h2 { margin:0; font-size:19px; }
.confirm-card p { line-height:1.65; }
.confirm-card .operations-row-actions { justify-content:flex-end; }
.operations-table tbody tr:hover { background: #f6f9fc; }
.operations-related { max-width: 320px; overflow-wrap: anywhere; }
.operations-content { max-width: 300px; overflow-wrap: anywhere; }
.operations-related span { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; color: #59626e; font-size: 13px; }
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
.business-detail h3 { margin: 0 0 10px; font-size: 17px; }
.business-detail ul { margin: 0; padding: 0; list-style: none; }
.business-detail li { display: grid; grid-template-columns: minmax(130px, 22%) 1fr; gap: 18px; align-items: baseline; padding: 12px 0; border-top: 1px solid #e2e5e9; font-size: 16px; line-height: 1.55; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
.business-detail strong { font-weight: 600; }
.business-detail button { margin-top: 8px; font-size: 14px; }
.operation-detail-heading p, .operation-undo-area p { font-size: 15px; }
@media (max-width: 600px) { .business-detail li { grid-template-columns: 1fr; gap: 2px; } }
.operation-undo-area { background: #f5f8fb; padding: 18px; border-radius: 8px; margin: 22px 0 30px; }
.operation-undo-area p { margin: 0 0 14px; line-height: 1.7; }
.operation-undo-area p:last-child { margin-bottom: 0; }
.operation-undo-area h3 { margin-top: 0; }
.operation-undo-area textarea { margin-bottom: 14px; resize: vertical; }
.operation-blockers { padding-left: 18px; margin-bottom: 0; }
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
