<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import type { TrendPoint } from '../api/dashboard'

const props = defineProps<{ data: TrendPoint[] }>()
const host = ref<HTMLDivElement>()
let chart: echarts.ECharts | undefined
let observer: ResizeObserver | undefined

function render() {
  if (!chart) return
  chart.setOption({
    animationDuration: 450,
    tooltip: { trigger: 'axis', backgroundColor: '#181818', borderWidth: 0, textStyle: { color: '#fdfefe' } },
    legend: { top: 0, right: 4, itemWidth: 10, itemHeight: 6, textStyle: { color: '#596273' } },
    grid: { left: 42, right: 34, top: 42, bottom: 26 },
    xAxis: { type: 'category', boundaryGap: true, data: props.data.map(x => x.date.slice(5)), axisLine: { lineStyle: { color: '#d9dee7' } }, axisLabel: { color: '#7b8494' } },
    yAxis: [
      { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: '#eef1f5' } }, axisLabel: { color: '#7b8494' } },
      { type: 'value', splitLine: { show: false }, axisLabel: { color: '#7b8494', formatter: (v: number) => v >= 10000 ? `${Math.round(v / 10000)}万` : String(v) } }
    ],
    series: [
      { name: '订单数', type: 'line', smooth: true, symbolSize: 4, data: props.data.map(x => x.orderCount), lineStyle: { width: 1, color: '#032d60' }, itemStyle: { color: '#032d60' }, areaStyle: { color: 'rgba(1,118,211,.06)' } },
      { name: '采购数量', type: 'bar', barWidth: 4, barGap: '-100%', barCategoryGap: '92%', data: props.data.map(x => x.purchaseQuantity), itemStyle: { color: '#0176d3', borderRadius: [2,2,0,0] } },
      { name: '发货数量', type: 'bar', barWidth: 4, barGap: '-100%', barCategoryGap: '92%', data: props.data.map(x => x.shipmentQuantity), itemStyle: { color: '#5a8fc7', borderRadius: [2,2,0,0] } },
      { name: '销售金额', type: 'line', yAxisIndex: 1, showSymbol: false, data: props.data.map(x => x.salesAmount), lineStyle: { width: 1, type: 'dashed', color: '#9050e9' }, itemStyle: { color: '#9050e9' } }
    ]
  }, true)
}
onMounted(() => { if (host.value) { chart = echarts.init(host.value); observer = new ResizeObserver(() => chart?.resize()); observer.observe(host.value); render() } })
watch(() => props.data, render, { deep: true })
onBeforeUnmount(() => { observer?.disconnect(); chart?.dispose() })
</script>
<template><div ref="host" class="dashboard-chart" aria-label="业务趋势图"></div></template>



