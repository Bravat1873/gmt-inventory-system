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
const legacyMaterialNumber = '物料' + '编号'

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

it('uses 客户料号 in its product search prompt', async () => {
  const wrapper = mount(SupplierDialog)
  await flushPromises()

  expect((wrapper.get('[data-test="supplier-product-picker"] input').element as HTMLInputElement).placeholder).toContain('客户料号')
  expect(wrapper.text()).not.toContain(legacyMaterialNumber)
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

it('shows full product name on hover and edits multiple purchase infos', async () => {
  api.getSupplier.mockResolvedValueOnce({
    id: 201, supplierName: '供应商', version: 0,
    products: [{ skuId: 101, skuCode: 'P90-001', productName: '非常长的完整产品名称',
      purchaseInfos: [
        { id: 11, purchasePrice: 220, moq: 5, leadTimeDays: 7, updatedAt: '2026-08-14T10:30:00', version: 0 },
        { id: 10, purchasePrice: 210, moq: 10, leadTimeDays: 9, updatedAt: '2026-08-13T10:30:00', version: 0 }
      ] }]
  })
  const wrapper = mount(SupplierDialog, { props: { row: { id: 201 } } })
  await flushPromises()

  const name = wrapper.get('[data-test="supplier-product-name-101"]')
  expect(name.attributes('title')).toContain('非常长的完整产品名称')
  expect(wrapper.findAll('[data-test^="purchase-info-row-"]')).toHaveLength(2)
  await wrapper.get('[data-test="add-purchase-info-101"]').trigger('click')
  expect(wrapper.findAll('[data-test^="purchase-info-row-"]')).toHaveLength(3)
})

it('shows purchase-info deletion only when the same product has multiple records', async () => {
  api.getSupplier.mockResolvedValueOnce({
    id: 201,
    supplierName: '供应商',
    version: 0,
    products: [{
      skuId: 101,
      skuCode: 'P90-001',
      productName: 'P90 智能锁',
      purchaseInfos: [
        { id: 11, purchasePrice: 220, moq: 5, leadTimeDays: 7, updatedAt: '2026-08-14T10:30:00', version: 0 },
        { id: 10, purchasePrice: 210, moq: 10, leadTimeDays: 9, updatedAt: '2026-08-13T10:30:00', version: 0 }
      ]
    }]
  })
  const wrapper = mount(SupplierDialog, { props: { row: { id: 201 } } })
  await flushPromises()

  const deleteActions = wrapper.findAll('[data-test^="remove-purchase-info-"]')
  expect(deleteActions).toHaveLength(2)
  expect(deleteActions.every(action => action.text() === '删除采购信息')).toBe(true)

  await deleteActions[1].trigger('click')
  expect(wrapper.findAll('[data-test^="remove-purchase-info-"]')).toHaveLength(0)
  expect(wrapper.get('[data-test="remove-supplier-product-101"]').text()).toBe('移除产品')
})

it('places the remove-product action in the operation column', async () => {
  const wrapper = mount(SupplierDialog)
  await flushPromises()
  const picker = wrapper.get('[data-test="supplier-product-picker"]')
  await picker.get('input').setValue('P90')
  document.body.querySelector<HTMLButtonElement>('[data-test="fuzzy-option-101"]')?.click()
  await flushPromises()
  await wrapper.get('[data-test="add-supplier-product"]').trigger('click')

  const remove = wrapper.get('[data-test="remove-supplier-product-101"]')
  expect(Array.from(remove.element.closest('tr')!.children).indexOf(remove.element.closest('td')!)).toBe(5)
  expect(wrapper.get('[data-test="supplier-product-name-101"]').element.parentElement?.textContent).not.toContain('移除产品')
})

it('renders an associated product with blank editable purchase fields when purchase infos are empty', async () => {
  api.getSupplier.mockResolvedValueOnce({
    id: 201,
    supplierName: '待补填供应商',
    version: 0,
    products: [{ skuId: 101, skuCode: 'P90-001', productName: 'P90 智能锁', purchaseInfos: [] }]
  })
  const wrapper = mount(SupplierDialog, { props: { row: { id: 201 } } })
  await flushPromises()

  expect(wrapper.get('[data-test="supplier-product-name-101"]').text()).toContain('P90-001')
  const row = wrapper.get('[data-test="purchase-info-row-101-0"]')
  const inputs = row.findAll('input')
  expect(inputs).toHaveLength(3)
  expect(inputs.map(input => (input.element as HTMLInputElement).value)).toEqual(['', '', ''])

  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(api.updateSupplier).toHaveBeenLastCalledWith(201, expect.objectContaining({
    products: [{
      skuId: 101,
      purchaseInfos: [{ purchasePrice: null, moq: null, leadTimeDays: null }]
    }]
  }))
})

it('allows blank purchase fields to be completed later and submits numbers', async () => {
  api.getSupplier.mockResolvedValueOnce({
    id: 201,
    supplierName: '待补填供应商',
    version: 0,
    products: [{ skuId: 101, skuCode: 'P90-001', purchaseInfos: [] }]
  })
  const wrapper = mount(SupplierDialog, { props: { row: { id: 201 } } })
  await flushPromises()

  const inputs = wrapper.get('[data-test="purchase-info-row-101-0"]').findAll('input')
  await inputs[0].setValue('12.5')
  await inputs[1].setValue('20')
  await inputs[2].setValue('7')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.updateSupplier).toHaveBeenLastCalledWith(201, expect.objectContaining({
    products: [{
      skuId: 101,
      purchaseInfos: [{ purchasePrice: 12.5, moq: 20, leadTimeDays: 7 }]
    }]
  }))
})
