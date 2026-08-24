import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import BusinessTraceDialog from './BusinessTraceDialog.vue'
const legacyMaterialNumber = '物料' + '编号'

it('does not render linked-order navigation in the business timeline', () => {
  const wrapper = mount(BusinessTraceDialog, {
    props: { trace: { type: 'order', title: '订单业务全景', header: {}, details: [], timeline: [{ occurredAt: '2026-08-06 10:00:00', title: '订单创建', description: '已创建', linkType: 'order', linkId: 1 }] } }
  })

  expect(wrapper.find('[data-test="trace-link"]').exists()).toBe(false)
})

it('renders complete detail values in a product panel', () => {
  const wrapper = mount(BusinessTraceDialog, {
    props: { trace: { type: 'order', title: '订单业务全景', header: {}, details: [{ customerPartNumber: 'BR_C51YZH60W', configuration: '一段很长的完整规格型号' }], timeline: [] } }
  })
  expect(wrapper.findAll('.trace-detail-panel')).toHaveLength(1)
  expect(wrapper.get('.trace-detail-panel').text()).toContain('BR_C51YZH60W')
  expect(wrapper.get('.trace-detail-panel').text()).toContain('一段很长的完整规格型号')
  expect(wrapper.findAll('[data-test="overflow-text"]')).toHaveLength(0)
})
it('labels sales order and inventory quantities consistently', () => {
  const wrapper = mount(BusinessTraceDialog, {
    props: { trace: { type: 'order', title: '订单业务全景', header: {}, details: [{ quantity: 10, shippedQuantity: 4, remainingQuantity: 6, lockedQuantity: 5, uncoveredQuantity: 1, actualQuantity: 12, inTransitQuantity: 3, pendingDeliveryQuantity: 9, availableQuantity: 7 }], timeline: [] } }
  })
  expect(wrapper.text()).toContain('订单数量')
  expect(wrapper.text()).toContain('已发货数量')
  expect(wrapper.text()).toContain('未发货数量')
  expect(wrapper.text()).toContain('本单锁定数量')
  expect(wrapper.text()).toContain('缺货数量')
  expect(wrapper.text()).toContain('实际库存数量')
  expect(wrapper.text()).toContain('在途数量')
  expect(wrapper.text()).toContain('全局未发货数量')
  expect(wrapper.text()).toContain('未锁定库存数量')
  expect(wrapper.text()).not.toContain('可用库存')
})
it('uses 客户料号 as the business-trace field label', () => {
  const wrapper = mount(BusinessTraceDialog, {
    props: { trace: { type: 'order', title: '订单业务全景', header: {}, details: [{ customerPartNumber: 'BR_C51YZH60W' }], timeline: [] } }
  })

  expect(wrapper.text()).toContain('客户料号')
  expect(wrapper.text()).not.toContain(legacyMaterialNumber)
})

it('shows the business order remark in its own section', () => {
  const wrapper = mount(BusinessTraceDialog, {
    props: { trace: { type: 'order', title: '订单业务全景', header: { remark: '客户要求分批发货，并提前一天通知。' }, details: [], timeline: [] } }
  })

  expect(wrapper.text()).toContain('订单备注')
  expect(wrapper.text()).toContain('客户要求分批发货，并提前一天通知。')
})

it('keeps the order remark section visible when the order has no remark', () => {
  const wrapper = mount(BusinessTraceDialog, {
    props: { trace: { type: 'order', title: '订单业务全景', header: {}, details: [], timeline: [] } }
  })

  expect(wrapper.text()).toContain('订单备注')
  expect(wrapper.text()).toContain('暂无备注')
})

