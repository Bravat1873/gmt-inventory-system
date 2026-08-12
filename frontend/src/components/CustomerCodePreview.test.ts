import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import CustomerDialog from './CustomerDialog.vue'

const api = vi.hoisted(() => ({ getCustomer: vi.fn(), createCustomer: vi.fn(), updateCustomer: vi.fn(), loadOrderSkus: vi.fn() }))
vi.mock('../api/workbench', () => api)

it('previews and submits the generated customer code inputs', async () => {
  api.loadOrderSkus.mockResolvedValue([])
  api.createCustomer.mockResolvedValue({ id: 1 })
  const wrapper = mount(CustomerDialog)
  await flushPromises()

  await wrapper.get('[data-test="customer-type"]').setValue('EXPORT')
  await wrapper.get('[data-test="taxpayer-id"]').setValue('91350100MABTRQEC91')

  const code = wrapper.get('[data-test="customer-code"]')
  expect((code.element as HTMLInputElement).readOnly).toBe(true)
  expect((code.element as HTMLInputElement).value).toBe('B.MABTRQEC91')

  await wrapper.get('[data-test="customer-name"]').setValue('外销客户')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(api.createCustomer).toHaveBeenCalledWith(expect.objectContaining({
    customerType: 'EXPORT',
    taxpayerId: '91350100MABTRQEC91'
  }))
})
