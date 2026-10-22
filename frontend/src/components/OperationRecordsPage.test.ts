import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import OperationRecordsPage from './OperationRecordsPage.vue'
import type { OperationDetail, OperationRecord } from '../api/operation-records'

const api = vi.hoisted(() => ({ loadOperations: vi.fn(), loadOperation: vi.fn(), undoOperation: vi.fn() }))
vi.mock('../api/operation-records', () => api)
const row: OperationRecord = { id: 12, action: '订单管理 · 修改', moduleName: '订单管理', httpMethod: 'PUT', requestPath: '/api/orders/4', actorId: 2, actorName: '业务员甲', actorUsername: 'owner', operatedAt: '2026-10-22T14:10:05', status: 'SUCCESS', changeCount: 2, affectedModules: '订单管理、库存管理', relatedRecords: '订单号：DD20261000004；客户：测试客户；产品编号：LOCK-01', changeSummary: [{ table: 'inventory_balance', type: 'UPDATE', count: 1, fields: ['locked_quantity'] }] }
const details = (overrides: Partial<OperationDetail> = {}): OperationDetail => ({ operation: { ...row }, changes: [{ id: 50, module: '库存管理', table: 'inventory_balance', recordKey: '{"id":4}', label: '门锁库存', relatedInfo: '订单号：DD20261000004；发货单号：FH20261000001；产品编号：LOCK-01', type: 'UPDATE', preserved: false, fields: [{ field: 'locked_quantity', before: '3', after: '5' }] }], changePage: 1, changePageSize: 100, canUndo: true, undoReason: '', blockers: [], ...overrides })
const owner = { id: 2, username: 'owner', displayName: '业务员甲', role: 'USER' as const }
beforeEach(() => {
  vi.clearAllMocks()
  api.loadOperations.mockResolvedValue({ items: [{ ...row }], total: 1, page: 1, pageSize: 20 })
  api.loadOperation.mockResolvedValue(details())
  api.undoOperation.mockResolvedValue({ reversalOperationId: 15 })
})
it('shows the operator, time, associated records and both affected modules', async () => {
  const wrapper = mount(OperationRecordsPage, { props: { currentUser: owner } }); await flushPromises()
  expect(wrapper.text()).toContain('业务员甲'); expect(wrapper.text()).toContain('2026-10-22 14:10:05')
  expect(wrapper.text()).toContain('订单管理、库存管理'); expect(wrapper.text()).toContain('DD20261000004')
  expect(wrapper.text()).toContain('客户：测试客户'); expect(wrapper.text()).toContain('锁定库存')
  await wrapper.findAll('button').find(b => b.text() === '详情')!.trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('发货单号：FH20261000001'); expect(wrapper.text()).toContain('产品编号：LOCK-01')
  expect(wrapper.text()).toContain('锁定库存'); expect(wrapper.find('.operations-diff').text()).toContain('3'); expect(wrapper.find('.operations-diff').text()).toContain('5')
})
it('allows owners to undo only after reviewing changes and providing a reason', async () => {
  const wrapper = mount(OperationRecordsPage, { props: { currentUser: owner } }); await flushPromises()
  const undo = wrapper.findAll('button').find(b => b.text() === '撤回')!; expect(undo.attributes('disabled')).toBeUndefined()
  await undo.trigger('click'); await flushPromises(); expect(api.undoOperation).not.toHaveBeenCalled()
  await wrapper.find('.operation-undo-area form').trigger('submit'); expect(wrapper.text()).toContain('请填写撤回原因')
  await wrapper.find('textarea').setValue('数量录入错误'); await wrapper.find('.operation-undo-area form').trigger('submit'); await flushPromises()
  expect(api.undoOperation).toHaveBeenCalledWith(12, '数量录入错误')
  expect(wrapper.emitted('message')?.[0]?.[0]).toContain('关联变更已全部恢复')
})
it('disables undo for other users while administrators can review any operation', async () => {
  const wrapper = mount(OperationRecordsPage, { props: { currentUser: { ...owner, id: 3 } } }); await flushPromises()
  expect(wrapper.findAll('button').find(b => b.text() === '撤回')!.attributes('disabled')).toBeDefined()
  await wrapper.setProps({ currentUser: { ...owner, id: 1, role: 'ADMIN' } })
  expect(wrapper.findAll('button').find(b => b.text() === '撤回')!.attributes('disabled')).toBeUndefined()
})
it('explains dependent operations and opens their details instead of bypassing the guard', async () => {
  api.loadOperation.mockResolvedValue(details({ canUndo: false, undoReason: '请先处理操作 #14', blockers: [{ id: 14, action: '发货', actorName: '仓管', operatedAt: '' }] }))
  const wrapper = mount(OperationRecordsPage, { props: { currentUser: owner } }); await flushPromises()
  await wrapper.findAll('button').find(b => b.text() === '撤回')!.trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('请先处理操作 #14'); expect(wrapper.find('textarea').exists()).toBe(false)
  await wrapper.find('.operation-blockers button').trigger('click'); await flushPromises()
  expect(api.loadOperation).toHaveBeenLastCalledWith(14, 1); expect(api.undoOperation).not.toHaveBeenCalled()
})
it('keeps conflict messages visible and refreshes eligibility after failed undo', async () => {
  const wrapper = mount(OperationRecordsPage, { props: { currentUser: owner } }); await flushPromises()
  await wrapper.findAll('button').find(b => b.text() === '撤回')!.trigger('click'); await flushPromises()
  api.undoOperation.mockRejectedValue(new Error('关联数据已发生变化'))
  api.loadOperation.mockResolvedValue(details({ canUndo: false, undoReason: '后续操作已使用数据' }))
  await wrapper.find('textarea').setValue('有误'); await wrapper.find('.operation-undo-area form').trigger('submit'); await flushPromises()
  expect(wrapper.find('[role="alert"]').text()).toContain('关联数据已发生变化')
  expect(wrapper.text()).toContain('后续操作已使用数据')
})
it('passes all filters, rejects reversed dates and retains filters for pagination', async () => {
  api.loadOperations.mockResolvedValue({ items: [{ ...row }], total: 45, page: 1, pageSize: 20 })
  const wrapper = mount(OperationRecordsPage, { props: { currentUser: owner } }); await flushPromises()
  const inputs = wrapper.findAll('input'); await inputs[0].setValue('DD01'); await inputs[1].setValue('张'); await inputs[2].setValue('2026-10-22'); await inputs[3].setValue('2026-10-01')
  await wrapper.find('form').trigger('submit'); expect(wrapper.text()).toContain('开始日期不能晚于结束日期')
  await inputs[3].setValue('2026-10-23'); await wrapper.find('form').trigger('submit'); await flushPromises()
  await wrapper.findAll('button').find(b => b.text() === '下一页')!.trigger('click'); await flushPromises()
  const params = api.loadOperations.mock.lastCall?.[0] as URLSearchParams
  expect(params.get('page')).toBe('2'); expect(params.get('keyword')).toBe('DD01'); expect(params.get('actor')).toBe('张'); expect(params.get('from')).toBe('2026-10-22')
})
