import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import InvoiceDialog from './InvoiceDialog.vue'

const { deleteInvoice, loadInvoices, saveInvoice } = vi.hoisted(() => ({
  deleteInvoice: vi.fn(),
  loadInvoices: vi.fn(),
  saveInvoice: vi.fn()
}))

vi.mock('../api/workbench', () => ({ deleteInvoice, loadInvoices, saveInvoice }))
vi.mock('./ChineseDatePicker.vue', () => ({
  default: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' }
}))

it('shows existing invoice history and adds a new invoice', async () => {
  loadInvoices.mockResolvedValue([
    { id: 32, invoiceNo: 'XS-F-002', invoiceDate: '2026-08-11', taxInclusiveAmount: 70, remark: '第二张发票' },
    { id: 31, invoiceNo: 'XS-F-001', invoiceDate: '2026-08-10', taxInclusiveAmount: 30, remark: '首张发票' }
  ])
  saveInvoice.mockResolvedValue({ id: 33, invoiceNo: 'XS-F-003' })
  const wrapper = mount(InvoiceDialog, { props: { type: 'SALES', businessId: 10 } })
  await flushPromises()

  expect(wrapper.text()).toContain('XS-F-002')
  expect(wrapper.text()).toContain('XS-F-001')

  await wrapper.get('[data-test="invoice-no"]').setValue('XS-F-003')
  await wrapper.get('[data-test="invoice-amount"]').setValue('10.5')
  await wrapper.get('form').trigger('submit.prevent')
  await flushPromises()

  expect(saveInvoice).toHaveBeenCalledWith('SALES', 10, expect.objectContaining({ invoiceNo: 'XS-F-003', taxInclusiveAmount: 10.5 }))
})

it('shows original and finance confirmed invoice values in history', async () => {
  loadInvoices.mockResolvedValue([
    {
      id: 1,
      invoiceNo: 'RAW-001',
      confirmedInvoiceNo: 'FINAL-001',
      taxInclusiveAmount: 122,
      confirmedAmount: 120,
      reviewStatus: 'APPROVED',
      reviewRemark: '财务确认'
    }
  ])
  const wrapper = mount(InvoiceDialog, { props: { type: 'PURCHASE', businessId: 8 } })
  await flushPromises()

  expect(wrapper.text()).toContain('RAW-001')
  expect(wrapper.text()).toContain('FINAL-001')
  expect(wrapper.text()).toContain('¥ 120.00')
  expect(wrapper.text()).toContain('已通过')
})

it('deletes only the selected invoice by id', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  loadInvoices.mockResolvedValue([{ id: 31, invoiceNo: 'XS-F-001', invoiceDate: '2026-08-10', taxInclusiveAmount: 30 }])
  deleteInvoice.mockResolvedValue(undefined)
  const wrapper = mount(InvoiceDialog, { props: { type: 'SALES', businessId: 10 } })
  await flushPromises()

  await wrapper.get('[data-test="delete-invoice-31"]').trigger('click')
  await flushPromises()

  expect(deleteInvoice).toHaveBeenCalledWith('SALES', 10, 31)
})
