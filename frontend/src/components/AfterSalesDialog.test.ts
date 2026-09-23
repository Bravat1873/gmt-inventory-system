import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import AfterSalesDialog from './AfterSalesDialog.vue'
import * as api from '../api/after-sales'

vi.mock('../api/after-sales', () => ({
  loadAfterSalesOrderOptions: vi.fn().mockResolvedValue([{ id: 1, orderNo: 'DD1', customerName: '客户' }]),
  loadAfterSalesOrderLines: vi.fn().mockResolvedValue([
    { salesOrderItemId: 10, skuId: 20, productCode: 'A', shippedQuantity: 2, availableReturnQuantity: 2, salePrice: 15 },
    { salesOrderItemId: 11, skuId: 21, productCode: 'B', shippedQuantity: 1, availableReturnQuantity: 1, salePrice: 20 },
  ]),
  createAfterSales: vi.fn().mockResolvedValue({ id: 1 }),
  updateAfterSales: vi.fn(),
}))
vi.mock('../api/workbench', () => ({ loadOrderSkus: vi.fn().mockResolvedValue([]) }))

it('selects all return lines and submits signed unit prices', async () => {
  const wrapper = mount(AfterSalesDialog)
  await flushPromises()
  await wrapper.get('select').setValue('1')
  await flushPromises()
  await wrapper.get('.after-sales-select-all input').setValue(true)
  expect(wrapper.findAll('.after-sales-line input[type="checkbox"]').every(input => (input.element as HTMLInputElement).checked)).toBe(true)
  await wrapper.findAll('.after-sales-line input[aria-label="退回单价"]')[0].setValue('-12.5')
  await wrapper.get('textarea').setValue('退货')
  await wrapper.get('.dialog-actions .primary-action').trigger('click')
  await flushPromises()
  expect(api.createAfterSales).toHaveBeenCalledWith(expect.objectContaining({
    returnLines: [
      expect.objectContaining({ salesOrderItemId: 10, returnUnitPrice: -12.5 }),
      expect.objectContaining({ salesOrderItemId: 11, returnUnitPrice: 20 }),
    ],
  }))
})
