import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import DashboardFlow from './DashboardFlow.vue'

describe('DashboardFlow', () => {
  const stages = [
    { key: 'DEMAND', label: '订单需求', value: 2000, caption: '2 单待齐货' },
    { key: 'INVENTORY', label: '可用库存', value: 0, caption: '锁定 0' },
    { key: 'SUPPLY', label: '在途采购', value: 1540, caption: '1 单待付款' },
    { key: 'DELIVERY', label: '待发货', value: 0, caption: '已齐货订单' }
  ]

  it('完整显示四个业务节点并发出选择事件', async () => {
    const wrapper = mount(DashboardFlow, { props: { stages, selected: '' } })
    expect(wrapper.text()).toContain('订单需求')
    expect(wrapper.text()).toContain('1,540')
    expect(wrapper.findAll('[data-flow-stage]')).toHaveLength(4)
    await wrapper.find('[data-flow-stage="SUPPLY"]').trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual(['SUPPLY'])
  })
})
