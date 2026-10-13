import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import CustomerFundsPage from './CustomerFundsPage.vue'
import * as api from '../api/customer-funds'
const pending={id:1,customerId:7,customerName:'测试客户',customerCode:'C007',operationNo:'ZJ20261000001',amount:100,direction:'IN' as const,fundSource:'FOREX' as const,status:'PENDING',submittedByName:'登记员',submittedAt:'2026-10-12T10:00:01',paymentAt:'2026-10-11T14:05:06'}
vi.mock('../api/customer-funds',()=>({
 loadFundCustomers:vi.fn(),loadFundOperations:vi.fn(),reviewCustomerFundRequest:vi.fn(),reverseCustomerFund:vi.fn(),
 loadCustomerFundOrders:vi.fn().mockResolvedValue([]),submitCustomerDeposit:vi.fn(),
 canReviewCustomerFunds:(role:string)=>['ADMIN','FINANCE'].includes(role)
 ,fundSourceLabel:(source?:string)=>source==='FOREX'?'外汇':source==='DOMESTIC'?'国内':'未填写'
}))
beforeEach(()=>{
 vi.clearAllMocks()
 vi.mocked(api.loadFundCustomers).mockResolvedValue([{id:7,customerCode:'C007',customerName:'测试客户',enabled:true,balance:500}])
 vi.mocked(api.loadFundOperations).mockResolvedValue({items:[pending],total:1,page:1,pageSize:10,summary:{pendingReceipt:100,pendingRefund:20,confirmedReceipt:500,confirmedRefund:0}})
 vi.mocked(api.reviewCustomerFundRequest).mockResolvedValue({})
})
it('renders required fields, separate totals and customer navigation',async()=>{
 const wrapper=mount(CustomerFundsPage,{props:{currentUserRole:'FINANCE'}});await flushPromises()
 for(const label of ['操作编号','客户名称','资金来源','收付款类型','操作时间','状态','人员','打款时间','资金确认','待确认收款','待确认退款'])expect(wrapper.text()).toContain(label)
 expect(wrapper.text()).toContain('ZJ20261000001');expect(wrapper.text()).toContain('2026-10-11 14:05:06');expect(wrapper.text()).toContain('登记员')
 await wrapper.get('[data-test="fund-customer-link"]').trigger('click');expect(wrapper.emitted('navigateCustomer')?.[0]).toEqual(['测试客户'])
})
it('finance confirms adjusted amounts with a reason and refreshes shared data',async()=>{
 const wrapper=mount(CustomerFundsPage,{props:{currentUserRole:'FINANCE'}});await flushPromises()
 await wrapper.get('[data-test="confirm-funds"]').trigger('click')
 await wrapper.get('[data-test="fund-confirmed-amount"]').setValue('90')
 await wrapper.get('[data-test="submit-fund-review"]').trigger('submit');expect(api.reviewCustomerFundRequest).not.toHaveBeenCalled()
 expect(wrapper.text()).toContain('必须填写原因')
 await wrapper.get('[data-test="fund-review-reason"]').setValue('银行到账90')
 await wrapper.get('[data-test="submit-fund-review"]').trigger('submit');await flushPromises()
 expect(api.reviewCustomerFundRequest).toHaveBeenCalledWith(1,true,'银行到账90',90)
 expect(api.loadFundCustomers).toHaveBeenCalledTimes(2)
})
it('requires rejection reasons and hides confirmation from ordinary users',async()=>{
 const finance=mount(CustomerFundsPage,{props:{currentUserRole:'FINANCE'}});await flushPromises()
 await finance.get('[data-test="reject-funds"]').trigger('click');await finance.get('[data-test="submit-fund-review"]').trigger('submit')
 expect(api.reviewCustomerFundRequest).not.toHaveBeenCalled();expect(finance.text()).toContain('驳回时必须填写原因')
 const user=mount(CustomerFundsPage,{props:{currentUserRole:'USER'}});await flushPromises()
 expect(user.find('[data-test="confirm-funds"]').exists()).toBe(false)
})
it('queries balance ledger separately from bank receipts',async()=>{
 const wrapper=mount(CustomerFundsPage,{props:{currentUserRole:'FINANCE'}});await flushPromises()
 await wrapper.findAll('.fund-tabs button')[1].trigger('click');await flushPromises()
 expect(api.loadFundOperations).toHaveBeenLastCalledWith(expect.any(URLSearchParams),true)
 expect(wrapper.get('thead').text()).toContain('变动前余额');expect(wrapper.get('thead').text()).not.toContain('资金确认')
})

it('shows the stored foreign/domestic source and sends the source filter',async()=>{
 const wrapper=mount(CustomerFundsPage,{props:{currentUserRole:'FINANCE'}});await flushPromises()
 expect(wrapper.get('tbody').text()).toContain('外汇')
 expect(wrapper.get('tbody').text()).not.toContain('客户预存款')
 await wrapper.get('[data-test="fund-source-filter"]').setValue('DOMESTIC')
 await wrapper.get('.funds-filters').trigger('submit');await flushPromises()
 expect(vi.mocked(api.loadFundOperations).mock.lastCall![0].get('fundSource')).toBe('DOMESTIC')
})
