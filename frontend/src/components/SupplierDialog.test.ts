import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import SupplierDialog from './SupplierDialog.vue'

const api = vi.hoisted(() => ({
  loadOrderSkus: vi.fn().mockResolvedValue([
    { id: 101, skuCode: 'P90-001', productName: 'P90 智能锁', model: 'P90', unit: '件' },
    { id: 102, skuCode: 'M3-001', productName: 'M3 智能锁', model: 'M3', unit: '件' }
  ]),
  createSupplier: vi.fn().mockResolvedValue({ id: 201 }),
  updateSupplier: vi.fn(),
  getSupplier: vi.fn()
}))

vi.mock('../api/workbench', () => api)

it('renders product suggestions above the scrollable supplier dialog', async () => {
  const wrapper = mount(SupplierDialog, { attachTo: document.body })
  await flushPromises()
  await wrapper.get('[data-test="supplier-name"]').setValue('贝朗供应商')
  const picker = wrapper.get('[data-test="supplier-product-picker"]')
  await picker.get('input').setValue('P90')

  const matchingOption = document.body.querySelector('[data-test="fuzzy-option-101"]')
  expect(matchingOption?.textContent).toContain('P90-001')
  expect(document.body.querySelector('[data-test="fuzzy-option-102"]')).toBeNull()
  expect(matchingOption?.closest('[data-test="supplier-product-picker"]')).toBeNull()

  matchingOption?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await flushPromises()
  await wrapper.get('[data-test="add-supplier-product"]').trigger('click')

  expect(wrapper.text()).toContain('P90-001')
  expect(wrapper.text()).not.toContain('供应商 ID')
  expect(wrapper.text()).not.toContain('产品 ID')
  wrapper.unmount()
})
