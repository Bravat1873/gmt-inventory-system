import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import BusinessTraceDialog from './BusinessTraceDialog.vue'

it('does not render linked-order navigation in the business timeline', () => {
  const wrapper = mount(BusinessTraceDialog, {
    props: { trace: { type: 'order', title: '订单业务全景', header: {}, details: [], timeline: [{ occurredAt: '2026-08-06 10:00:00', title: '订单创建', description: '已创建', linkType: 'order', linkId: 1 }] } }
  })

  expect(wrapper.find('[data-test="trace-link"]').exists()).toBe(false)
})

it('uses the shared overflow text treatment for every detail cell', () => {
  const wrapper = mount(BusinessTraceDialog, {
    props: { trace: { type: 'order', title: '订单业务全景', header: {}, details: [{ skuCode: 'BR_C51YZH60W', configuration: '一段很长的完整规格型号' }], timeline: [] } }
  })
  expect(wrapper.findAll('[data-test="overflow-text"]')).toHaveLength(2)
})
it('labels sales order remaining quantity as unshipped quantity', () => {
  const wrapper = mount(BusinessTraceDialog, {
    props: { trace: { type: 'order', title: '订单业务全景', header: {}, details: [{ remainingQuantity: 3 }], timeline: [] } }
  })
  expect(wrapper.text()).toContain('未发货数量')
})