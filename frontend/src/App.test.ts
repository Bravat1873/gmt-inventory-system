import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'
import EntityDialog from './components/EntityDialog.vue'
import ModuleListPage from './components/ModuleListPage.vue'
import SupplierDialog from './components/SupplierDialog.vue'
import ProductCodeRulesDialog from './components/ProductCodeRulesDialog.vue'

const api = vi.hoisted(() => ({
  loadModule: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 10, totalPages: 0 }),
  postAction: vi.fn(),
  createManualPurchase: vi.fn(),
  createEntity: vi.fn(), updateEntity: vi.fn(), createOrder: vi.fn(), updateOrder: vi.fn(), getOrder: vi.fn(),
  loadSupplierOptions: vi.fn().mockResolvedValue([]), loadSupplierProducts: vi.fn().mockResolvedValue([]),
  loadOrderSkus: vi.fn().mockResolvedValue([]), createSupplier: vi.fn(), updateSupplier: vi.fn(), getSupplier: vi.fn(),
  loadProductCodeRules: vi.fn().mockResolvedValue([]), createProductCodeRule: vi.fn(), updateProductCodeRule: vi.fn(), deleteProductCodeRule: vi.fn()
}))
vi.mock('./api/workbench', () => api)

const auth = vi.hoisted(() => ({ currentUser: vi.fn().mockResolvedValue({ id: 1, username: 'admin', displayName: '管理员', role: 'ADMIN' }), logout: vi.fn() }))
vi.mock('./api/auth', () => ({ ...auth, login: vi.fn() }))

describe('连续导航和浏览器地址状态', () => {
  beforeEach(() => {
    history.replaceState(null, '', '/?module=order&page=1')
    api.postAction.mockReset()
    auth.currentUser.mockReset().mockResolvedValue({ id: 1, username: 'admin', displayName: '管理员', role: 'ADMIN' })
  })

  it('八个菜单连续显示且没有常用操作和刷新按钮', async () => {
    const wrapper = mount(App)
    await flushPromises()
    expect(wrapper.findAll('.nav-list>.nav-item')).toHaveLength(8)
    expect(wrapper.get('h1').text()).toBe('订单管理')
    expect(wrapper.text()).toContain('供应商管理')
    expect(wrapper.text()).not.toContain('常用操作')
    expect(wrapper.text()).not.toContain('该功能将在下一阶段开放')
    expect(wrapper.text()).not.toContain('刷新')
  })

  it('导航写入地址，浏览器刷新后仍在当前模块', async () => {
    const wrapper = mount(App)
    await flushPromises()
    await wrapper.get('[data-module="inventory"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('h1').text()).toBe('库存管理')
    expect(location.search).toContain('module=inventory')
    wrapper.unmount()
    const reopened = mount(App)
    await flushPromises()
    expect(reopened.get('h1').text()).toBe('库存管理')
  })

  it.each(['ADMIN', 'FINANCE'] as const)('%s 用户的产品资料支持导入与手工新增', async role => {
    auth.currentUser.mockResolvedValue({ id: 1, username: role.toLowerCase(), displayName: role, role })
    const wrapper = mount(App)
    await flushPromises()
    await wrapper.get('[data-module="product"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('导入产品')
    expect(wrapper.text()).toContain('手工新增')
    await wrapper.get('[data-test="primary-action"]').trigger('click')
    expect(wrapper.get('[aria-label="Excel 导入面板"]').text()).toContain('导入产品')
  })

  it('普通用户看不到产品导入主操作', async () => {
    history.replaceState(null, '', '/?module=product&page=1')
    auth.currentUser.mockResolvedValue({ id: 3, username: 'regular-user', displayName: '普通用户', role: 'USER' })
    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.find('[data-test="primary-action"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('手工新增')
  })

  it('产品管理子菜单使用正常中文并打开可识别的编号规则页面', async () => {
    history.replaceState(null, '', '/?module=product&page=1')
    api.loadProductCodeRules.mockResolvedValueOnce([{ id: 1, category: 'BRAND', code: 'BR', displayName: 'STANLEY', enabled: true, sortOrder: 10, version: 0, updatedAt: '2026-08-11T16:00:00' }])
    const wrapper = mount(App)
    await flushPromises()
    const submenu = wrapper.get('.nav-subitem')
    expect(submenu.text()).toBe('产品编号规则')
    await submenu.trigger('click')
    await flushPromises()
    const rules = wrapper.getComponent(ProductCodeRulesDialog)
    expect(rules.get('h1').text()).toBe('产品编号规则')
    expect(rules.text()).toContain('维护产品编号各组成部分的可选值与显示顺序')
    expect(rules.get('label[for="product-rule-code"]').text()).toContain('规则代码')
    expect(rules.get('label[for="product-rule-name"]').text()).toContain('规则名称')
    expect(rules.get('label[for="product-rule-sort-order"]').text()).toContain('显示顺序')
    expect(rules.find('#product-rule-enabled').exists()).toBe(false)
    expect(rules.text()).toContain('修改时间')
    expect(rules.text()).toContain('2026-08-11 16:00:00')
    expect(rules.text()).not.toContain('状态')
  })
  it('App guard rejects a programmatic COST import action from a regular user', async () => {
    history.replaceState(null, '', '/?module=product&page=1')
    auth.currentUser.mockResolvedValue({ id: 3, username: 'regular-user', displayName: '普通用户', role: 'USER' })
    const wrapper = mount(App)
    await flushPromises()

    wrapper.getComponent(ModuleListPage).vm.$emit('action')
    await flushPromises()
    expect(wrapper.find('[aria-label="Excel 导入面板"]').exists()).toBe(false)
  })

  it('opens SupplierDialog instead of EntityDialog for a supplier manual add', async () => {
    history.replaceState(null, '', '/?module=supplier&page=1')
    const wrapper = mount(App)
    await flushPromises()

    wrapper.getComponent(ModuleListPage).vm.$emit('manual')
    await flushPromises()

    expect(wrapper.findComponent(SupplierDialog).exists()).toBe(true)
    expect(wrapper.findComponent(EntityDialog).exists()).toBe(false)
  })

  it('生成采购打开手工采购表单，不弹出浏览器确认框', async () => {
    history.replaceState(null, '', '/?module=purchase&page=1')
    const confirm = vi.spyOn(window, 'confirm')
    const wrapper = mount(App)
    await flushPromises()
    await wrapper.get('[data-test="primary-action"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="dialog"]').text()).toContain('手工采购')
    expect(confirm).not.toHaveBeenCalled()
    expect(api.postAction).not.toHaveBeenCalled()
    confirm.mockRestore()
  })

  it('新增用户打开用户表单，并提供联系电话字段', async () => {
    history.replaceState(null, '', '/?module=user&page=1')
    const wrapper = mount(App)
    await flushPromises()
    await wrapper.get('[data-test="primary-action"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="dialog"]').text()).toContain('新增用户')
    expect(wrapper.text()).toContain('联系电话')
  })

  it.each(['FINANCE', 'USER'] as const)('%s 不能新增或修改用户账号', async role => {
    history.replaceState(null, '', '/?module=user&page=1')
    auth.currentUser.mockResolvedValue({ id: 3, username: role.toLowerCase(), displayName: role, role })
    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.find('[data-test="primary-action"]').exists()).toBe(false)
    wrapper.getComponent(ModuleListPage).vm.$emit('action')
    wrapper.getComponent(ModuleListPage).vm.$emit('edit', { id: 3, username: 'staff', displayName: '员工', version: 0 })
    await flushPromises()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })

  it('opens the selected product gallery and clears it when closed', async () => {
    history.replaceState(null, '', '/?module=product&page=1')
    const wrapper = mount(App, {
      global: {
        stubs: {
          ProductGalleryDialog: {
            props: ['productId', 'initialImageId'],
            emits: ['close'],
            template: '<div data-test="gallery-dialog-stub" :data-product-id="productId" :data-image-id="initialImageId"><button data-test="gallery-close" @click="$emit(\'close\')">close</button></div>'
          }
        }
      }
    })
    await flushPromises()

    wrapper.getComponent(ModuleListPage).vm.$emit('gallery', { id: 7, primaryImageId: 9 })
    await flushPromises()
    expect(wrapper.get('[data-test="gallery-dialog-stub"]').attributes('data-product-id')).toBe('7')
    expect(wrapper.get('[data-test="gallery-dialog-stub"]').attributes('data-image-id')).toBe('9')

    await wrapper.get('[data-test="gallery-close"]').trigger('click')
    expect(wrapper.find('[data-test="gallery-dialog-stub"]').exists()).toBe(false)
  })
})
