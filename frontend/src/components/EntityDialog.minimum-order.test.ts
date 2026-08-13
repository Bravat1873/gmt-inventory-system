import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import EntityDialog from './EntityDialog.vue'

const api = vi.hoisted(() => ({
  createEntity: vi.fn(), updateEntity: vi.fn(), uploadProductImages: vi.fn(),
  loadOrderSkus: vi.fn().mockResolvedValue([]), loadProductCodeRules: vi.fn().mockResolvedValue([])
}))
vi.mock('../api/workbench', () => api)

it('defaults and submits the product sales minimum order quantity independently from supplier MOQ', async () => {
  api.createEntity.mockResolvedValue({ id: 1 })
  const wrapper = mount(EntityDialog, { props: { module: 'product', currentUserRole: 'ADMIN' } })
  await flushPromises()
  const input = wrapper.get('[data-test="sales-minimum-order-quantity"]')
  expect((input.element as HTMLInputElement).value).toBe('1')
  expect(input.attributes('min')).toBe('1')
  await input.setValue('6')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(api.createEntity).toHaveBeenCalledWith('product', expect.objectContaining({ salesMinimumOrderQuantity: 6 }))
  wrapper.unmount()
})
