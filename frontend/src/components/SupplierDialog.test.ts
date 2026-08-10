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
it('submits the complete supplier profile fields without losing text formatting', async () => {
  const wrapper = mount(SupplierDialog)
  await flushPromises()

  const values: Record<string, string> = {
    'manufacturer-category': '生产厂商',
    'manufacturer-type': '战略供应商',
    'supplier-location': '浙江',
    'product-attribute': '智能锁',
    'short-name': '威欧希',
    'supplier-name': '浙江威欧希科技股份有限公司',
    'contact-name': '王女士',
    'contact-title': '销售经理',
    phone: ' 0571 01234567 ',
    address: '杭州市滨江区',
    currency: 'CNY',
    'tax-registration-no': ' 0012345000 ',
    'bank-address': '中国银行杭州分行',
    'bank-account': ' 0012 3456 7890 '
  }
  for (const [name, value] of Object.entries(values)) {
    await wrapper.get(`[data-test="${name}"]`).setValue(value)
  }
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.createSupplier).toHaveBeenCalledWith(expect.objectContaining({
    manufacturerCategory: '生产厂商',
    manufacturerType: '战略供应商',
    supplierLocation: '浙江',
    productAttribute: '智能锁',
    shortName: '威欧希',
    supplierName: '浙江威欧希科技股份有限公司',
    contactName: '王女士',
    contactTitle: '销售经理',
    phone: ' 0571 01234567 ',
    address: '杭州市滨江区',
    currency: 'CNY',
    taxRegistrationNo: ' 0012345000 ',
    bankAddress: '中国银行杭州分行',
    bankAccount: ' 0012 3456 7890 '
  }))
})
