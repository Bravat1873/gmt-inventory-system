import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import DashboardTrendChart from './DashboardTrendChart.vue'
import { init } from 'echarts'
const chart=vi.hoisted(()=>({setOption:vi.fn(),resize:vi.fn(),dispose:vi.fn()}))
vi.mock('echarts',()=>({init:vi.fn(()=>chart)}))
beforeEach(()=>{
 vi.clearAllMocks()
 vi.stubGlobal('ResizeObserver',class {observe(){} disconnect(){}})
 vi.stubGlobal('matchMedia',()=>({matches:false}))
})
afterEach(()=>vi.unstubAllGlobals())
const points=[{date:'2026-10-12',orderCount:2,salesAmount:1200.5,purchaseQuantity:10.5,shipmentQuantity:4,afterSalesCount:0},{date:'2026-10-13',orderCount:3,salesAmount:900,purchaseQuantity:20,shipmentQuantity:8,afterSalesCount:0}]
it('separates units and switches the plotted values without dropping fractional quantities',async()=>{
 const wrapper=mount(DashboardTrendChart,{props:{data:points}})
 expect(init).toHaveBeenCalledOnce()
 expect(wrapper.text()).toContain('2,100.5')
 expect(chart.setOption.mock.lastCall![0].series[0].data).toEqual([1200.5,900])
 await wrapper.get('[data-trend-metric="purchaseQuantity"]').trigger('click');await flushPromises()
 expect(wrapper.get('.trend-caption').text()).toContain('采购数量 · 件')
 const option=chart.setOption.mock.lastCall![0]
 expect(option.series[0]).toMatchObject({type:'bar',data:[10.5,20]})
 expect(Array.isArray(option.yAxis)).toBe(false)
 expect(wrapper.text()).toContain('30.5')
 wrapper.unmount()
 expect(chart.dispose).toHaveBeenCalledOnce()
})
it('renders an honest empty state and preserves daily values for inspection',()=>{
 const wrapper=mount(DashboardTrendChart,{props:{data:points.map(p=>({...p,orderCount:0,salesAmount:0,purchaseQuantity:0,shipmentQuantity:0}))}})
 expect(wrapper.text()).toContain('所选周期暂无业务记录')
 expect(wrapper.get('.dashboard-chart').classes()).toContain('chart-hidden')
 expect(wrapper.findAll('tbody tr')).toHaveLength(2)
 wrapper.unmount()
})
