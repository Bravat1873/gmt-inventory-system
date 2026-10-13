import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import FundOperationDialog from './FundOperationDialog.vue'
import * as api from '../api/customer-funds'
vi.mock('../api/customer-funds',()=>({loadCustomerFundOrders:vi.fn().mockResolvedValue([]),submitCustomerDeposit:vi.fn().mockResolvedValue(1)}))
const customers=[{id:7,customerCode:'C007',customerName:'测试客户',enabled:true,balance:500}]
beforeEach(()=>vi.clearAllMocks())
it('sends explicit refunds with a signed amount and actual payment time',async()=>{
 const wrapper=mount(FundOperationDialog,{props:{customers,customerId:7}});await flushPromises()
 await wrapper.get('[data-test="fund-direction"]').setValue('OUT');await wrapper.get('[data-test="fund-operation-amount"]').setValue('25.5')
 await wrapper.get('[data-test="fund-source"]').setValue('FOREX')
 await wrapper.get('[data-test="fund-payment-time"]').setValue('2026-10-12T09:20')
 await wrapper.get('form').trigger('submit');await flushPromises()
 expect(api.submitCustomerDeposit).toHaveBeenCalledWith(7,expect.objectContaining({amount:-25.5,paymentDate:'2026-10-12',paymentAt:'2026-10-12T09:20:00',fundSource:'FOREX'}))
 expect(wrapper.emitted('saved')).toBeTruthy()
})
it('prevents over-balance refunds and missing customers',async()=>{
 const wrapper=mount(FundOperationDialog,{props:{customers,customerId:7}});await flushPromises()
 await wrapper.get('[data-test="fund-direction"]').setValue('OUT');await wrapper.get('[data-test="fund-operation-amount"]').setValue('501');await wrapper.get('form').trigger('submit')
 expect(api.submitCustomerDeposit).not.toHaveBeenCalled();expect(wrapper.text()).toContain('不能超过')
 const empty=mount(FundOperationDialog,{props:{customers}});await flushPromises();await empty.get('form').trigger('submit');expect(empty.text()).toContain('请选择客户')
})
