import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import OrderPaymentDialog from './OrderPaymentDialog.vue'
import { getOrder, postAction } from '../api/workbench'
import { loadCustomerFundOverview } from '../api/customer-funds'
vi.mock('../api/workbench',()=>({getOrder:vi.fn(),postAction:vi.fn()}))
vi.mock('../api/customer-funds',()=>({loadCustomerFundOverview:vi.fn()}))
beforeEach(()=>{vi.clearAllMocks();vi.mocked(getOrder).mockResolvedValue({id:10,customerId:1,receivableAmount:100,receivedAmount:20});vi.mocked(loadCustomerFundOverview).mockResolvedValue({customerId:1,balance:500,orderOutstandingAmount:80,coverageRatio:100,pendingAmount:0,insufficient:false});vi.mocked(postAction).mockResolvedValue({})})
const open=()=>mount(OrderPaymentDialog,{props:{order:{id:10,orderNo:'SO-001',customerName:'测试客户'}}})
it('keeps unpaid orders unchanged and pays only after an explicit selection',async()=>{
 const wrapper=open();await flushPromises()
 expect((wrapper.get('[data-test="payment-amount"]').element as HTMLInputElement).value).toBe('80')
 await wrapper.get('form').trigger('submit');expect(postAction).not.toHaveBeenCalled();expect(wrapper.emitted('close')).toHaveLength(1)
 await wrapper.get('[data-test="order-payment-choice"]').setValue('true');await wrapper.get('form').trigger('submit');await flushPromises()
 expect(postAction).toHaveBeenCalledWith('/api/finance/orders/10/pay',{expectedAmount:80,amount:80});expect(wrapper.emitted('saved')).toHaveLength(1)
})
it('allows a partial payment when the balance cannot cover the whole order',async()=>{
 vi.mocked(loadCustomerFundOverview).mockResolvedValueOnce({customerId:1,balance:50,orderOutstandingAmount:80,coverageRatio:50,pendingAmount:0,insufficient:true})
 const wrapper=open();await flushPromises()
 await wrapper.get('[data-test="order-payment-choice"]').setValue('true')
 await wrapper.get('[data-test="payment-amount"]').setValue('30')
 expect(wrapper.get('[data-test="submit-order-payment"]').attributes('disabled')).toBeUndefined()
 await wrapper.get('form').trigger('submit');await flushPromises()
 expect(postAction).toHaveBeenCalledWith('/api/finance/orders/10/pay',{expectedAmount:80,amount:30})
 expect(wrapper.emitted('message')?.[0]?.[0]).toContain('仍有未付金额')
})
it.each(['0','-1','81','','0.00001'])('rejects invalid payment %s without submitting',async(value)=>{
 const wrapper=open();await flushPromises()
 await wrapper.get('[data-test="order-payment-choice"]').setValue('true')
 await wrapper.get('[data-test="payment-amount"]').setValue(value)
 await wrapper.get('form').trigger('submit')
 expect(postAction).not.toHaveBeenCalled()
 expect(wrapper.get('[data-test="submit-order-payment"]').attributes('disabled')).toBeDefined()
})
it('keeps the default remaining amount at database precision for decimal totals',async()=>{
 vi.mocked(getOrder).mockResolvedValueOnce({id:10,customerId:1,receivableAmount:100.1,receivedAmount:20.01})
 const wrapper=open();await flushPromises()
 expect((wrapper.get('[data-test="payment-amount"]').element as HTMLInputElement).value).toBe('80.09')
 await wrapper.get('[data-test="order-payment-choice"]').setValue('true')
 await wrapper.get('form').trigger('submit');await flushPromises()
 expect(postAction).toHaveBeenCalledWith('/api/finance/orders/10/pay',{expectedAmount:80.09,amount:80.09})
})
it('blocks insufficient funds and reports server conflicts',async()=>{
 vi.mocked(loadCustomerFundOverview).mockResolvedValueOnce({customerId:1,balance:50,orderOutstandingAmount:80,coverageRatio:50,pendingAmount:0,insufficient:true})
 const wrapper=open();await flushPromises();await wrapper.get('[data-test="order-payment-choice"]').setValue('true')
 expect(wrapper.get('[data-test="submit-order-payment"]').attributes('disabled')).toBeDefined();await wrapper.get('form').trigger('submit');expect(postAction).not.toHaveBeenCalled()
 const second=open();await flushPromises();vi.mocked(postAction).mockRejectedValueOnce(new Error('订单已支付'))
 await second.get('[data-test="order-payment-choice"]').setValue('true');await second.get('form').trigger('submit');await flushPromises()
 expect(second.text()).toContain('订单已支付');expect(second.emitted('saved')).toBeUndefined()
})
