import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import InvoiceDialog from './InvoiceDialog.vue'

const { deleteInvoice, loadFinanceRecords, loadInvoices, saveInvoice } = vi.hoisted(() => ({
  deleteInvoice: vi.fn(),
  loadFinanceRecords: vi.fn(),
  loadInvoices: vi.fn(),
  saveInvoice: vi.fn()
}))

vi.mock('../api/workbench', () => ({ deleteInvoice, loadFinanceRecords, loadInvoices, saveInvoice }))
vi.mock('./ChineseDatePicker.vue', () => ({
  default: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' }
}))

beforeEach(() => {
  vi.resetAllMocks()
  loadFinanceRecords.mockResolvedValue([])
})

it('shows existing invoice history and adds a new invoice', async () => {
  loadInvoices.mockResolvedValue([
    { id: 32, invoiceNo: 'XS-F-002', invoiceDate: '2026-08-11', taxInclusiveAmount: 70, remark: '第二张发票' },
    { id: 31, invoiceNo: 'XS-F-001', invoiceDate: '2026-08-10', taxInclusiveAmount: 30, remark: '首张发票' }
  ])
  saveInvoice.mockResolvedValue({ id: 33, invoiceNo: 'XS-F-003' })
  const wrapper = mount(InvoiceDialog, { props: { type: 'SALES', businessId: 10, businessNo: 'DD20260800001' } })
  await flushPromises()

  expect(wrapper.get('.invoice-dialog-header').text()).toContain('关联销售订单：DD20260800001')
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
  const wrapper = mount(InvoiceDialog, { props: { type: 'PURCHASE', businessId: 8, businessNo: 'CG20260800001' } })
  await flushPromises()

  expect(wrapper.text()).toContain('RAW-001')
  expect(wrapper.text()).toContain('FINAL-001')
  expect(wrapper.text()).toContain('¥ 120.00')
  expect(wrapper.text()).toContain('已通过')
})

it('prefills a purchase invoice with the latest payment and preserves a manual amount', async () => {
  loadInvoices.mockResolvedValue([])
  loadFinanceRecords.mockResolvedValue([
    { id: 12, amount: 200, occurredAt: '2026-08-12T10:00:00' },
    { id: 11, amount: 100, occurredAt: '2026-08-11T10:00:00' }
  ])
  saveInvoice.mockResolvedValue({ id: 33, invoiceNo: 'CG-F-003' })
  const wrapper = mount(InvoiceDialog, { props: { type: 'PURCHASE', businessId: 8, businessNo: 'CG20260800001' } })
  await flushPromises()

  expect((wrapper.get('[data-test="invoice-amount"]').element as HTMLInputElement).value).toBe('200')

  await wrapper.get('[data-test="invoice-no"]').setValue('CG-F-003')
  await wrapper.get('[data-test="invoice-amount"]').setValue('180')
  await wrapper.get('form').trigger('submit.prevent')
  await flushPromises()

  expect(saveInvoice).toHaveBeenCalledWith('PURCHASE', 8, expect.objectContaining({ invoiceNo: 'CG-F-003', taxInclusiveAmount: 180 }))
})

it('leaves the invoice amount blank when there are no finance records', async () => {
  loadInvoices.mockResolvedValue([])
  loadFinanceRecords.mockResolvedValue([])
  const wrapper = mount(InvoiceDialog, { props: { type: 'SALES', businessId: 10, businessNo: 'DD20260800001' } })
  await flushPromises()

  expect((wrapper.get('[data-test="invoice-amount"]').element as HTMLInputElement).value).toBe('')
})

it('prefills a sales invoice from the latest receipt original amount', async () => {
  loadInvoices.mockResolvedValue([])
  loadFinanceRecords.mockResolvedValue([
    { id: 22, amount: 320, confirmedAmount: 290, occurredAt: '2026-08-12T10:00:00' },
    { id: 21, amount: 100, confirmedAmount: 100, occurredAt: '2026-08-11T10:00:00' }
  ])
  saveInvoice.mockResolvedValue({ id: 34, invoiceNo: 'DD-F-003' })
  const wrapper = mount(InvoiceDialog, { props: { type: 'SALES', businessId: 10, businessNo: 'DD20260800001' } })
  await flushPromises()

  expect((wrapper.get('[data-test="invoice-amount"]').element as HTMLInputElement).value).toBe('320')

  await wrapper.get('[data-test="invoice-no"]').setValue('DD-F-003')
  await wrapper.get('form').trigger('submit.prevent')
  await flushPromises()

  expect(saveInvoice).toHaveBeenCalledWith('SALES', 10, expect.objectContaining({ invoiceNo: 'DD-F-003', taxInclusiveAmount: 320 }))
})

it('deletes only the selected invoice by id', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  loadInvoices.mockResolvedValue([{ id: 31, invoiceNo: 'XS-F-001', invoiceDate: '2026-08-10', taxInclusiveAmount: 30 }])
  deleteInvoice.mockResolvedValue(undefined)
  const wrapper = mount(InvoiceDialog, { props: { type: 'SALES', businessId: 10, businessNo: 'DD20260800001' } })
  await flushPromises()

  await wrapper.get('[data-test="delete-invoice-31"]').trigger('click')
  await flushPromises()

  expect(deleteInvoice).toHaveBeenCalledWith('SALES', 10, 31)
})
