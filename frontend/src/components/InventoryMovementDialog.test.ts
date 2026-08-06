import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import InventoryMovementDialog from './InventoryMovementDialog.vue'

it('lists imported inbound and outbound movements by date', () => {
 const wrapper = mount(InventoryMovementDialog, {
  props: {
   movements: [
    { date: '2026-06-22', direction: '出库', quantity: 7, sourceColumn: 'T:0622出库' },
    { date: '2026-08-04', direction: '入库', quantity: 9, sourceColumn: 'U:0804入库' },
   ],
  },
 })

 expect(wrapper.text()).toContain('2026-06-22')
 expect(wrapper.text()).toContain('出库')
 expect(wrapper.text()).toContain('2026-08-04')
 expect(wrapper.text()).toContain('入库')
  expect(wrapper.find('[data-test="close-movements"]').exists()).toBe(true)
})
