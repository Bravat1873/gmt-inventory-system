<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { CircleCheck, Refresh, Right, WarningFilled } from '@element-plus/icons-vue'
import { loadDashboard, subscribeDashboard, type DashboardSnapshot, type ProductException } from '../api/dashboard'
import DashboardTrendChart from './DashboardTrendChart.vue'
import DashboardFlow from './DashboardFlow.vue'
import type { ModuleKey } from '../modules/module-config'

const emit = defineEmits<{ navigate: [module: ModuleKey, keyword: string] }>()
const data = ref<DashboardSnapshot>()
const days = ref(30)
const loading = ref(true)
const error = ref('')
const live = ref(false)
const selected = ref('')
let closeEvents: (() => void) | undefined
const stages = computed(() => data.value ? [
  { key: 'DEMAND', label: '订单需求', value: data.value.pendingDeliveryQuantity, caption: `${data.value.pendingStock} 单待齐货`, target: 'order' as ModuleKey },
  { key: 'INVENTORY', label: '可用库存', value: Math.max(data.value.actualInventory - data.value.lockedInventory, 0), caption: `锁定 ${data.value.lockedInventory.toLocaleString()}`, target: 'inventory' as ModuleKey },
  { key: 'SUPPLY', label: '在途采购', value: data.value.inTransitInventory, caption: `${data.value.pendingPurchasePayment} 单待付款`, target: 'purchase' as ModuleKey },
  { key: 'DELIVERY', label: '待发货', value: data.value.pendingShipment, caption: '已齐货订单', target: 'order' as ModuleKey }
] : [])
const exceptions = computed(() => selected.value && selected.value !== 'DELIVERY' ? (data.value?.exceptions ?? []).filter(item => item.category === selected.value) : data.value?.exceptions ?? [])
const activeStage = computed(() => stages.value.find(item => item.key === selected.value))
function categoryName(value: string) { return ({ DEMAND: '需求缺口', INVENTORY: '库存风险', SUPPLY: '在途保障' } as Record<string, string>)[value] ?? value }
function target(item: ProductException): ModuleKey { return item.category === 'SUPPLY' ? 'purchase' : item.category === 'INVENTORY' ? 'inventory' : 'order' }
function selectStage(value: string) { selected.value = selected.value === value ? '' : value }
function navigateStage() { if (activeStage.value) emit('navigate', activeStage.value.target, '') }
async function refresh() {
  loading.value = true
  try { data.value = await loadDashboard(days.value); error.value = ''; connect() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '看板数据加载失败' }
  finally { loading.value = false }
}
function connect() {
  closeEvents?.(); live.value = false
  closeEvents = subscribeDashboard(days.value, value => { data.value = value; live.value = true; error.value = '' }, () => { live.value = false })
}
watch(days, refresh)
onMounted(refresh)
onBeforeUnmount(() => closeEvents?.())
</script>

<template>
  <section class="dashboard-page lightning-dashboard">
    <header class="lightning-dashboard-toolbar">
      <div class="dashboard-title"><h1>业务概览</h1><p>销售、库存、采购与交付动态</p></div>
      <div class="dashboard-head-actions">
        <span class="live-state" :class="{ online: live }"><i></i>{{ live ? '数据实时更新' : '正在连接' }}</span>
        <div class="period-tabs" aria-label="趋势周期"><button v-for="n in [7, 30, 90]" :key="n" :class="{ active: days === n }" :aria-pressed="days === n" @click="days = n">{{ n }}天</button></div>
        <button class="icon-action" title="刷新看板" aria-label="刷新看板" :disabled="loading" @click="refresh"><Refresh /></button>
      </div>
    </header>
    <div v-if="error" class="dashboard-error">{{ error }}<button @click="refresh">重试</button></div>
    <div v-if="loading && !data" class="dashboard-loading">正在汇总业务数据...</div>
    <template v-else-if="data">
      <section class="lightning-metric-grid">
        <button class="pulse-primary" @click="emit('navigate', 'order', '')"><span>今日销售额</span><strong>¥{{ Number(data.todaySalesAmount).toLocaleString() }}</strong><small>今日新增 {{ data.todayOrders }} 笔订单</small></button>
        <button @click="emit('navigate', 'order', '')"><span>缺货产品</span><strong :class="{ 'danger-value': data.shortageProducts > 0 }">{{ data.shortageProducts }}</strong><small>缺口 {{ data.shortageQuantity.toLocaleString() }} 件</small></button>
        <button @click="emit('navigate', 'finance', '')"><span>待收款</span><strong>{{ data.pendingReceipt }}</strong><small>客户回款待处理</small></button>
        <button @click="emit('navigate', 'purchase', '')"><span>待付采购</span><strong>{{ data.pendingPurchasePayment }}</strong><small>供应商付款待办</small></button>
        <button @click="emit('navigate', 'afterSales', '')"><span>处理中售后</span><strong>{{ data.activeAfterSales }}</strong><small>退换货事项</small></button>
      </section>
      <section class="lightning-panel lightning-flow-panel">
        <div class="section-heading"><div><h2>订单到交付</h2><p>点击业务节点，查看对应产品待办</p></div><div class="flow-balance" :class="{ negative: data.supplyDemandSurplus < 0 }">供需余量 <strong>{{ data.supplyDemandSurplus > 0 ? '+' : '' }}{{ data.supplyDemandSurplus.toLocaleString() }}<small>件</small></strong></div></div>
        <DashboardFlow :stages="stages" :selected="selected" @select="selectStage" />
      </section>
      <div class="lightning-work-grid">
        <section class="lightning-panel trend-panel">
          <div class="section-heading"><div><h2>业务趋势</h2><p>近 {{ days }} 天 · 按日汇总</p></div><button class="text-action" @click="emit('navigate', 'order', '')">查看订单 <Right /></button></div>
          <DashboardTrendChart :data="data.trend" />
        </section>
        <section class="lightning-panel action-panel">
          <div class="section-heading"><div><h2>产品待办 <span class="exception-count">{{ exceptions.length }}</span></h2><p>按紧急程度排序</p></div><button v-if="selected" class="text-action" @click="selected = ''">查看全部</button></div>
          <div v-if="activeStage" class="active-filter"><span>正在查看：{{ activeStage.label }}</span><button @click="navigateStage">进入业务 <Right /></button></div>
          <div class="action-list">
            <article v-for="item in exceptions.slice(0, 7)" :key="`${item.category}-${item.productId}`" class="action-row">
              <div class="action-row-top"><WarningFilled class="risk-dot" :class="`risk-${item.category.toLowerCase()}`" /><strong>{{ categoryName(item.category) }}</strong><b :class="{ negative: item.supplyDemandSurplus < 0 }">{{ item.supplyDemandSurplus }}</b></div>
              <div class="product-identifiers"><span>客户料号 {{ item.customerPartNumber || '—' }}</span><span>型号 {{ item.model || '—' }}</span><span>产品编号 {{ item.productCode || '—' }}</span></div>
              <div class="action-row-foot"><span>库存 {{ item.actualQuantity }} · 在途 {{ item.inTransitQuantity }} · 待交付 {{ item.pendingDeliveryQuantity }}</span><button @click="emit('navigate', target(item), item.customerPartNumber || item.productCode)">处理 <Right /></button></div>
            </article>
            <div v-if="!exceptions.length" class="empty-state"><CircleCheck /><strong>{{ selected ? '该节点暂无产品待办' : '暂无产品待办' }}</strong><span>{{ selected ? '可切换其他节点，或查看全部待办' : '有产品异常时，会在这里显示处理事项' }}</span></div>
          </div>
        </section>
      </div>
    </template>
  </section>
</template>

<style scoped>
.lightning-dashboard{--slds-brand:#2364a6;--slds-brand-dark:#164778;--slds-border:#e1e6ed;--slds-text:#25344a;--slds-muted:#667181;--slds-surface:#fff;--slds-danger:#b44343;font-variant-numeric:tabular-nums}
.lightning-dashboard-toolbar{justify-content:space-between;align-items:center;gap:20px;margin-bottom:24px;min-height:52px}.dashboard-title h1{margin:0;font-size:26px;font-weight:650;letter-spacing:-.02em}.dashboard-title p{margin:7px 0 0;color:var(--slds-muted);font-size:13px}.dashboard-head-actions{gap:12px}.live-state{font-size:12px}.live-state.online i{box-shadow:none;background:#3e806a}.period-tabs{padding:3px;border-color:#e1e6ed;background:#edf0f4;border-radius:7px}.period-tabs button{height:32px;border-radius:5px;cursor:pointer}.period-tabs button.active{color:#164778;background:#fff;box-shadow:0 1px 2px rgb(25 40 60 / 8%)}.icon-action{border-radius:7px;color:#536579}.icon-action:disabled{opacity:.5;cursor:wait}
.lightning-metric-grid{grid-template-columns:minmax(220px,1.3fr) repeat(4,minmax(100px,1fr));margin-bottom:20px;border-radius:12px;box-shadow:none;overflow:hidden}.lightning-metric-grid>button{padding:20px 22px;border-top:0;cursor:pointer}.lightning-metric-grid .pulse-primary{padding-top:20px;border-top:0;background:#f4f8fc}.lightning-metric-grid span{font-size:13px}.lightning-metric-grid small{font-size:11px;margin-top:8px}.lightning-metric-grid strong,.lightning-metric-grid .pulse-primary strong{margin-top:10px;font-size:28px;font-weight:650}.lightning-metric-grid .pulse-primary strong{color:#164778}
.lightning-panel{border-radius:12px;box-shadow:none}.lightning-flow-panel{padding:20px 22px;margin-bottom:20px}.section-heading{align-items:center;gap:16px}.section-heading h2{font-size:17px;font-weight:650;line-height:1.4}.section-heading>div>p{margin:5px 0 0;font-size:12px;color:var(--slds-muted);line-height:1.5}.section-heading>.text-action{display:inline-flex;align-items:center;gap:4px;white-space:nowrap;font-size:12px;font-weight:500;cursor:pointer}.section-heading>.text-action svg{width:13px}.flow-balance{display:flex;align-items:center;gap:10px;font-size:12px}.flow-balance strong{margin:0;font-size:20px;font-weight:600}.flow-balance small{margin-left:5px;font-size:11px;font-weight:400}
:deep(.lightning-flow){margin-top:18px;grid-template-columns:minmax(0,1fr) 28px minmax(0,1fr) 28px minmax(0,1fr) 28px minmax(0,1fr)}
:deep(.lightning-flow-stage){min-height:76px;padding:12px;border-color:transparent;background:transparent;gap:12px;border-radius:8px;cursor:pointer}
:deep(.lightning-flow-stage:hover){background:#f5f8fb;border-color:#e1e6ed}:deep(.lightning-flow-stage.active){background:#edf4fb;border-color:#c3d8ee;box-shadow:none}:deep(.flow-step){width:30px;height:30px;border:1px solid #d4dfeb;border-radius:8px;background:#f0f5fa;color:#2364a6;font-size:12px}:deep(.flow-copy strong){font-size:23px;font-weight:650}:deep(.flow-copy strong em){margin-left:5px;color:#667181;font-size:11px;font-style:normal;font-weight:400}:deep(.flow-copy b){margin-top:6px;font-size:12px;font-weight:500}:deep(.flow-copy small){margin-top:5px;font-size:11px}:deep(.flow-connector){color:#a6b3c2}
.lightning-work-grid{grid-template-columns:minmax(0,1.65fr) minmax(300px,.85fr);gap:20px}.trend-panel,.action-panel{padding:22px}.action-panel{max-height:530px}.exception-count{display:inline-flex;align-items:center;justify-content:center;min-width:24px;height:23px;margin-left:5px;padding:0 6px;border-radius:5px;background:#eef2f6;color:#536579;font-size:12px;font-weight:500}.action-list{margin-top:18px;flex:1}.empty-state{min-height:260px;height:100%;padding:30px 15px;gap:10px;text-align:center;font-size:12px;line-height:1.6}.empty-state svg{width:34px;height:34px;margin-bottom:4px;color:#6f8c7c}.empty-state strong{font-size:15px;font-weight:600;color:#35455a}.empty-state span{max-width:27ch}.risk-dot{width:13px;height:13px;background:transparent;border-radius:0}.risk-demand{color:var(--slds-danger)}.risk-inventory{color:#947245}.risk-supply{color:var(--slds-brand)}.action-row{padding:15px 0}.action-row-top{grid-template-columns:13px 1fr auto}.product-identifiers{line-height:1.6}.action-row-foot{line-height:1.6}.active-filter{border-radius:6px;border-color:#d9e4ef;background:#f5f8fc}
.lightning-dashboard :deep(button:focus-visible){outline:2px solid #2364a6;outline-offset:3px}.lightning-dashboard :deep(::selection){background:#d5e6f7;color:#164778}
@media(max-width:1200px){.lightning-work-grid{grid-template-columns:minmax(0,1.5fr) minmax(260px,1fr)}.lightning-metric-grid>button{padding:18px 15px}.lightning-metric-grid{grid-template-columns:1.2fr repeat(4,minmax(90px,1fr))}}
@media(max-width:1000px){.lightning-work-grid{grid-template-columns:1fr}.action-panel{max-height:none}.empty-state{min-height:170px}.lightning-metric-grid{grid-template-columns:repeat(4,minmax(0,1fr))}.lightning-metric-grid .pulse-primary{grid-column:1/-1;border-bottom:1px solid var(--slds-border)}.lightning-metric-grid>button:nth-child(2){border-left:0}.lightning-metric-grid>button:nth-child(4){border-left:1px solid var(--slds-border)}}
@media(max-width:720px){.lightning-dashboard-toolbar{align-items:flex-start;flex-direction:column;gap:16px}.dashboard-head-actions{width:100%;gap:10px;justify-content:space-between}.live-state{width:auto}.dashboard-title h1{font-size:23px}.lightning-metric-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.lightning-metric-grid>button:nth-child(odd):not(:first-child){border-left:1px solid var(--slds-border)}.lightning-metric-grid>button:nth-child(even){border-left:0}.lightning-metric-grid>button:nth-child(n+4){border-top:1px solid var(--slds-border)}.lightning-flow-panel,.trend-panel,.action-panel{padding:18px}.flow-balance{flex-direction:column;gap:4px;align-items:flex-end}.section-heading{align-items:flex-start}:deep(.lightning-flow){grid-template-columns:minmax(0,1fr)}:deep(.flow-connector){height:20px}:deep(.lightning-flow-stage){min-height:70px}.empty-state{min-height:180px}}
@media(max-width:720px){
 :global(.app-shell:has(.lightning-dashboard)){display:flex;flex-direction:column}
 :global(.app-shell:has(.lightning-dashboard) .sidebar){position:relative;inset:auto;width:100%;padding:12px 14px;border-right:0;border-bottom:1px solid #e1e6ed;flex-shrink:0}
 :global(.app-shell:has(.lightning-dashboard) .sidebar::before){top:12px;left:14px;width:28px;height:28px}
 :global(.app-shell:has(.lightning-dashboard) .sidebar::after){top:18px;left:51px;font-size:13px}
 :global(.app-shell:has(.lightning-dashboard) .nav-list){margin-top:40px;flex-direction:row;gap:5px;overflow-x:auto;padding-bottom:2px}
 :global(.app-shell:has(.lightning-dashboard) .nav-group){display:flex;gap:3px;flex-shrink:0}
 :global(.app-shell:has(.lightning-dashboard) .nav-item),:global(.app-shell:has(.lightning-dashboard) .nav-subitem){flex-shrink:0;width:auto;white-space:nowrap;padding:9px 12px;margin:0;font-size:12px}
 :global(.app-shell:has(.lightning-dashboard) main){grid-column:auto;flex:1;min-height:0;height:auto;width:100%}
 :global(.app-shell:has(.lightning-dashboard) .content){height:auto;padding:22px 14px}
 :global(.app-shell:has(.lightning-dashboard) .current-user){position:absolute;top:18px;right:14px;bottom:auto;left:auto;font-size:11px;gap:7px}
 .period-tabs button{white-space:nowrap;padding-inline:12px}.empty-state span{max-width:none}
}
</style>








