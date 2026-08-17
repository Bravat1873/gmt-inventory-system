import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import ProcurementConfigurationAlert from './ProcurementConfigurationAlert.vue'

const { loadUnconfiguredProcurementShortages } = vi.hoisted(() => ({
  loadUnconfiguredProcurementShortages: vi.fn()
}))
vi.mock('../api/workbench', () => ({ loadUnconfiguredProcurementShortages }))

beforeEach(() => loadUnconfiguredProcurementShortages.mockReset())

it('does not let the expanded alert be compressed by the purchase page flex layout', () => {
  const source = readFileSync(resolve(process.cwd(), 'src/components/ProcurementConfigurationAlert.vue'), 'utf8')
  const rootStyles = source.match(/\.procurement-configuration-alert\s*\{([^}]*)\}/s)?.[1] ?? ''
  const detailStyles = source.match(/\.procurement-alert-details\s*\{([^}]*)\}/s)?.[1] ?? ''

  expect(rootStyles).toMatch(/flex:\s*0\s+0\s+auto/)
  expect(detailStyles).toMatch(/overflow:\s*auto/)
})

it('summarizes and expands unconfigured shortages without changing table rows', async () => {
  loadUnconfiguredProcurementShortages.mockResolvedValue([
    { skuId: 70, skuCode: 'F70', productName: 'F70', shortageQuantity: 200, orderNumbers: ['DD20260800002'] },
    { skuId: 90, skuCode: 'P90', productName: 'P90', shortageQuantity: 2, orderNumbers: ['SO20260801', 'SO20260802'] }
  ])
  const wrapper = mount(ProcurementConfigurationAlert)
  await flushPromises()

  expect(wrapper.text()).toContain('2 个缺货产品尚未配置有效供应商采购信息')
  expect(wrapper.find('[data-test="procurement-alert-details"]').exists()).toBe(false)
  await wrapper.get('[data-test="toggle-procurement-alert"]').trigger('click')
  expect(wrapper.get('[data-test="procurement-alert-details"]').text()).toContain('DD20260800002')
  expect(wrapper.text()).toContain('缺口 200')
})

it('navigates to supplier management and stays hidden when there is no alert', async () => {
  loadUnconfiguredProcurementShortages.mockResolvedValue([
    { skuId: 70, skuCode: 'F70', productName: 'F70', shortageQuantity: 200, orderNumbers: ['DD20260800002'] }
  ])
  const wrapper = mount(ProcurementConfigurationAlert)
  await flushPromises()
  await wrapper.get('[data-test="toggle-procurement-alert"]').trigger('click')
  await wrapper.get('[data-test="open-supplier-management"]').trigger('click')
  expect(wrapper.emitted('navigateSupplier')).toBeTruthy()

  loadUnconfiguredProcurementShortages.mockResolvedValue([])
  const empty = mount(ProcurementConfigurationAlert)
  await flushPromises()
  expect(empty.html()).toBe('<!--v-if-->')
})
