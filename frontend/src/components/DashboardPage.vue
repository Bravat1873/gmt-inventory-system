<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Refresh, Right, WarningFilled } from '@element-plus/icons-vue'
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
      <div class="dashboard-head-actions">
        <span class="live-state" :class="{ online: live }"><i></i>{{ live ? '数据实时更新' : '正在连接' }}</span>
        <div class="period-tabs" aria-label="趋势周期"><button v-for="n in [7, 30, 90]" :key="n" :class="{ active: days === n }" @click="days = n">{{ n }}天</button></div>
        <button class="icon-action" title="刷新看板" aria-label="刷新看板" @click="refresh"><Refresh /></button>
      </div>
    </header>
    <div v-if="error" class="dashboard-error">{{ error }}<button @click="refresh">重试</button></div>
    <div v-if="loading && !data" class="dashboard-loading">正在汇总业务数据...</div>
    <template v-else-if="data">
      <section class="lightning-metric-grid">
        <button class="pulse-primary" @click="emit('navigate', 'order', '')"><span>今日销售额</span><strong>¥{{ Number(data.todaySalesAmount).toLocaleString() }}</strong><small>今日新增 {{ data.todayOrders }} 笔订单</small></button>
        <button @click="emit('navigate', 'order', '')"><span>缺货产品</span><strong class="danger-value">{{ data.shortageProducts }}</strong><small>缺口 {{ data.shortageQuantity.toLocaleString() }} 件</small></button>
        <button @click="emit('navigate', 'finance', '')"><span>待收款</span><strong>{{ data.pendingReceipt }}</strong><small>客户回款待处理</small></button>
        <button @click="emit('navigate', 'purchase', '')"><span>待付采购</span><strong>{{ data.pendingPurchasePayment }}</strong><small>供应商付款待办</small></button>
        <button @click="emit('navigate', 'afterSales', '')"><span>处理中售后</span><strong>{{ data.activeAfterSales }}</strong><small>退换货事项</small></button>
      </section>
      <section class="lightning-panel lightning-flow-panel">
        <div class="section-heading"><div><span>实时链路</span><h2>订单到交付</h2></div><div class="flow-balance" :class="{ negative: data.supplyDemandSurplus < 0 }">供需余量 <strong>{{ data.supplyDemandSurplus > 0 ? '+' : '' }}{{ data.supplyDemandSurplus.toLocaleString() }}</strong></div></div>
        <DashboardFlow :stages="stages" :selected="selected" @select="selectStage" />
      </section>
      <div class="lightning-work-grid">
        <section class="lightning-panel trend-panel">
          <div class="section-heading"><div><span>{{ days }} 天观察</span><h2>业务趋势</h2></div><p>订单数 / 采购数量 / 发货数量 / 销售金额</p></div>
          <DashboardTrendChart :data="data.trend" />
        </section>
        <section class="lightning-panel action-panel">
          <div class="section-heading"><div><span>按紧急程度排序</span><h2><WarningFilled /> 产品待办</h2></div><button v-if="selected" class="text-action" @click="selected = ''">查看全部</button></div>
          <div v-if="activeStage" class="active-filter"><span>正在查看：{{ activeStage.label }}</span><button @click="navigateStage">进入业务 <Right /></button></div>
          <div class="action-list">
            <article v-for="item in exceptions.slice(0, 7)" :key="`${item.category}-${item.productId}`" class="action-row">
              <div class="action-row-top"><span class="risk-dot" :class="`risk-${item.category.toLowerCase()}`"></span><strong>{{ categoryName(item.category) }}</strong><b :class="{ negative: item.supplyDemandSurplus < 0 }">{{ item.supplyDemandSurplus }}</b></div>
              <div class="product-identifiers"><span>客户料号 {{ item.customerPartNumber || '—' }}</span><span>型号 {{ item.model || '—' }}</span><span>产品编号 {{ item.productCode || '—' }}</span></div>
              <div class="action-row-foot"><span>库存 {{ item.actualQuantity }} · 在途 {{ item.inTransitQuantity }} · 待交付 {{ item.pendingDeliveryQuantity }}</span><button @click="emit('navigate', target(item), item.customerPartNumber || item.productCode)">处理 <Right /></button></div>
            </article>
            <div v-if="!exceptions.length" class="empty-state"><strong>当前链路运行平稳</strong><span>没有需要处理的产品异常</span></div>
          </div>
        </section>
      </div>
    </template>
  </section>
</template>








