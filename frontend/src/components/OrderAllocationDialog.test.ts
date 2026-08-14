import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import OrderAllocationDialog from './OrderAllocationDialog.vue'

const updateOrderAllocations = vi.hoisted(() => vi.fn())
vi.mock('../api/workbench', () => ({ updateOrderAllocations }))

describe('OrderAllocationDialog', () => {
  beforeEach(() => updateOrderAllocations.mockReset().mockResolvedValue({}))
  it('submits the manually selected quantity with the current version', async () => {
    const wrapper = mount(OrderAllocationDialog, { props: { allocation: { id: 8, version: 3, status: 'READY_TO_SHIP', adjustable: true, items: [
      { lineNo: 10000, skuCode: 'P50', productName: '智能锁', quantity: 5, shippedQuantity: 0, lockedQuantity: 5, uncoveredQuantity: 0, actualQuantity: 10, availableQuantity: 5 }
    ] } } })
    expect(wrapper.text()).toContain('订单数量')
    expect(wrapper.text()).toContain('已发货数量')
    expect(wrapper.text()).toContain('实际库存数量')
    expect(wrapper.text()).toContain('本单锁定数量')
    expect(wrapper.text()).toContain('未锁定库存数量')
    expect(wrapper.text()).not.toContain('当前锁定')
    expect(wrapper.text()).not.toContain('可用库存')
    await wrapper.get('[data-test="allocation-10000"]').setValue('2')
    await wrapper.get('.primary-action').trigger('click')
    expect(updateOrderAllocations).toHaveBeenCalledWith(8, 3, [{ lineNo: 10000, lockedQuantity: 2 }])
    expect(wrapper.emitted('saved')).toHaveLength(1)
  })
})
