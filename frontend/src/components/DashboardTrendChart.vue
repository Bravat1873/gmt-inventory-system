<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { DataLine } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import type { TrendPoint } from '../api/dashboard'

const props = defineProps<{ data: TrendPoint[] }>()
const metrics = [
  { key: 'salesAmount', label: '销售金额', unit: '元' },
  { key: 'orderCount', label: '订单数', unit: '笔' },
  { key: 'purchaseQuantity', label: '采购数量', unit: '件' },
  { key: 'shipmentQuantity', label: '发货数量', unit: '件' }
] as const
const selected = ref<typeof metrics[number]['key']>('salesAmount')
const active = computed(() => metrics.find(metric => metric.key === selected.value)!)
const values = computed(() => props.data.map(point => Number(point[selected.value])))
const hasValues = computed(() => values.value.some(value => value !== 0))
const allEmpty = computed(() => !props.data.some(point => metrics.some(metric => Number(point[metric.key]) !== 0)))
const dateRange = computed(() => props.data.length ? `${props.data[0].date} 至 ${props.data[props.data.length - 1].date}` : '当前周期')
const total = (key: typeof selected.value) => props.data.reduce((sum, point) => sum + Number(point[key]), 0)
const format = (value: number, money = false) => (money ? '¥ ' : '') + value.toLocaleString('zh-CN', { maximumFractionDigits: money ? 2 : 4 })
const host = ref<HTMLDivElement>()
let chart: echarts.ECharts | undefined
let observer: ResizeObserver | undefined

function render() {
  if (!chart || !host.value) return
  const money = selected.value === 'salesAmount'
  chart.setOption({
    animation: !window.matchMedia('(prefers-reduced-motion: reduce)').matches,
    animationDurationUpdate: 250,
    textStyle: { fontFamily: 'Microsoft YaHei, sans-serif' },
    tooltip: {
      trigger: 'axis', backgroundColor: '#fffff8', borderWidth: 0,
      textStyle: { color: '#25344a', fontSize: 12 }, extraCssText: 'box-shadow:none;',
      axisPointer: { type: 'line', lineStyle: { color: '#a9b5c3', type: 'dashed' } },
      valueFormatter: (value: unknown) => `${format(Number(value), money)}${money ? '' : ` ${active.value.unit}`}`
    },
    grid: { left: 8, right: 32, top: 16, bottom: 8, containLabel: true },
    xAxis: {
      type: 'category', boundaryGap: !money, data: props.data.map(point => point.date.slice(5)),
      axisLine: { lineStyle: { color: '#dbe1e8' } }, axisTick: { show: false },
      axisLabel: { color: '#667181', hideOverlap: true, margin: 14,
        interval: Math.max(0, Math.ceil(props.data.length / (host.value.clientWidth < 500 ? 4 : 8)) - 1) }
    },
    yAxis: {
      type: 'value', min: 0, splitNumber: 4, minInterval: money ? undefined : 1,
      axisLine: { show: false }, axisTick: { show: false }, splitLine: { show: false },
      axisLabel: { color: '#667181', formatter: (value: number) => Math.abs(value) >= 10000 ? `${Number((value / 10000).toFixed(1))}万` : value.toLocaleString() }
    },
    series: [{
      name: active.value.label, type: money ? 'line' : 'bar', data: values.value,
      smooth: false, showSymbol: props.data.length <= 7, symbolSize: 5, barMaxWidth: 18,
      lineStyle: { width: 2, color: '#2364a6' }, itemStyle: { color: '#2364a6', borderRadius: [3, 3, 0, 0] },
      emphasis: { itemStyle: { color: '#164778' } }
    }]
  }, true)
}
onMounted(() => { if (host.value) { chart = echarts.init(host.value); observer = new ResizeObserver(() => { chart?.resize(); render() }); observer.observe(host.value); render() } })
watch([() => props.data, selected], render, { deep: true, flush: 'post' })
onBeforeUnmount(() => { observer?.disconnect(); chart?.dispose() })
</script>
<template>
  <div class="trend-view">
    <div class="trend-metrics" aria-label="选择趋势指标">
      <button v-for="metric in metrics" :key="metric.key" :data-trend-metric="metric.key" :class="{ active: selected===metric.key }" :aria-pressed="selected===metric.key" @click="selected=metric.key">
        <span>{{ metric.label }}</span><strong>{{ format(total(metric.key), metric.key==='salesAmount') }}<small v-if="metric.key!=='salesAmount'">{{ metric.unit }}</small></strong>
      </button>
    </div>
    <div class="trend-caption"><span>{{ active.label }} · {{ active.unit }}</span><span>{{ dateRange }}</span></div>
    <div class="trend-plot">
      <div ref="host" class="dashboard-chart" :class="{ 'chart-hidden': !hasValues }" role="img" :aria-label="`${active.label}按日趋势，周期合计${format(total(selected))}${active.unit}。每日数值见下方明细。`"></div>
      <div v-if="!hasValues" class="trend-empty"><DataLine /><strong>{{ allEmpty ? '所选周期暂无业务记录' : `所选周期暂无${active.label}变动` }}</strong><p>订单、采购和发货数据会在办理后显示在这里</p></div>
    </div>
    <details v-if="data.length" class="trend-data"><summary>查看每日明细</summary><div class="trend-data-scroll"><table><thead><tr><th>日期</th><th>销售金额（元）</th><th>订单（笔）</th><th>采购（件）</th><th>发货（件）</th></tr></thead><tbody><tr v-for="point in data" :key="point.date"><td>{{ point.date }}</td><td>{{ format(point.salesAmount) }}</td><td>{{ format(point.orderCount) }}</td><td>{{ format(point.purchaseQuantity) }}</td><td>{{ format(point.shipmentQuantity) }}</td></tr></tbody></table></div></details>
  </div>
</template>

<style scoped>
.trend-metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));margin-top:22px;border-bottom:1px solid #e5e9ef;gap:12px}
.trend-metrics button{min-width:0;padding:0 0 16px;border:0;border-bottom:2px solid transparent;text-align:left;background:transparent;color:#667181;cursor:pointer}
.trend-metrics button.active{border-bottom-color:#2364a6;color:#164778}.trend-metrics button:hover{color:#164778}.trend-metrics span{display:block;font-size:12px}.trend-metrics strong{display:block;margin-top:8px;color:#25344a;font-size:21px;font-variant-numeric:tabular-nums;overflow-wrap:anywhere}.trend-metrics small{margin-left:5px;font-size:11px;font-weight:400;color:#667181}
.trend-caption{display:flex;justify-content:space-between;gap:12px;margin:20px 0 8px;font-size:11px;color:#667181}.trend-caption>span:first-child{font-weight:600}.trend-plot{position:relative;min-width:0}.dashboard-chart{height:240px}.chart-hidden{visibility:hidden}.trend-empty{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;gap:12px;padding:20px;color:#667181}.trend-empty svg{width:34px;height:34px;color:#7a8b9f}.trend-empty strong{font-size:15px;font-weight:600;color:#35455a}.trend-empty p{margin:0;max-width:100%;font-size:12px;line-height:1.7}
.trend-data{border-top:1px solid #e5e9ef;padding-top:12px;font-size:12px;color:#667181}.trend-data summary{cursor:pointer;width:fit-content;padding:4px 0}.trend-data-scroll{max-height:240px;overflow:auto;margin-top:12px}.trend-data table{width:100%;border-collapse:collapse;white-space:nowrap}.trend-data td,.trend-data th{padding:9px 12px;text-align:right;border-bottom:1px solid #edf0f4;font-size:12px;font-weight:400}.trend-data td:first-child,.trend-data th:first-child{text-align:left}.trend-data th{color:#35455a}.trend-metrics button:focus-visible,.trend-data summary:focus-visible{outline:2px solid #2364a6;outline-offset:3px}
@media(max-width:600px){.trend-metrics{grid-template-columns:repeat(2,minmax(0,1fr));row-gap:14px}.trend-metrics strong{font-size:19px}.trend-caption{flex-wrap:wrap}.dashboard-chart{height:230px}}
</style>



