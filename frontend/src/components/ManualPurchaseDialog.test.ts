import { mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import ManualPurchaseDialog from './ManualPurchaseDialog.vue'

vi.mock('../api/workbench', () => ({
  createPurchase: vi.fn(),
  loadSuppliers: vi.fn().mockResolvedValue([]),
  loadProducts: vi.fn().mockResolvedValue([]),
}))

it('shows the expected arrival date in a Chinese-friendly format', async () => {
  const wrapper = mount(ManualPurchaseDialog)

  await wrapper.get('input[type="date"]').setValue('2026-08-04')

  expect(wrapper.get('[data-test="expected-arrival-display"]').text()).toBe('2026年8月4日')
})
