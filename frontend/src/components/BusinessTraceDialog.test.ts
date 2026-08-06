import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import BusinessTraceDialog from './BusinessTraceDialog.vue'

it('does not render linked-order navigation in the business timeline', () => {
  const wrapper = mount(BusinessTraceDialog, {
    props: { trace: { type: 'order', title: '订单业务全景', header: {}, details: [], timeline: [{ occurredAt: '2026-08-06 10:00:00', title: '订单创建', description: '已创建', linkType: 'order', linkId: 1 }] } }
  })

  expect(wrapper.find('[data-test="trace-link"]').exists()).toBe(false)
})
