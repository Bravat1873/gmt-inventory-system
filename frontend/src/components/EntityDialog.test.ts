import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import EntityDialog from './EntityDialog.vue'

const api = vi.hoisted(() => ({
  createEntity: vi.fn(),
  updateEntity: vi.fn(),
  loadOrderSkus: vi.fn(),
  uploadProductImages: vi.fn(),
  loadProductImages: vi.fn(),
  setPrimaryProductImage: vi.fn(),
  reorderProductImages: vi.fn(),
  deleteProductImage: vi.fn()
}))

vi.mock('../api/workbench', () => api)

beforeEach(() => {
  vi.clearAllMocks()
  api.loadOrderSkus.mockResolvedValue([])
  api.loadProductImages.mockResolvedValue([])
  api.uploadProductImages.mockResolvedValue([])
})

const productFile = (name = 'product.jpg') => new File(['image'], name, { type: 'image/jpeg' })

async function chooseProductImages(wrapper: ReturnType<typeof mount>, files: File[]) {
  const input = wrapper.get('[data-test="product-image-input"]')
  Object.defineProperty(input.element, 'files', { configurable: true, value: files })
  await input.trigger('change')
}

it('saves a new product before uploading its selected images', async () => {
  const selectedFiles = [productFile('primary.jpg'), productFile('detail.jpg')]
  api.createEntity.mockResolvedValue({ id: 81 })
  const wrapper = mount(EntityDialog, { props: { module: 'product', currentUserRole: 'FINANCE' } })
  await chooseProductImages(wrapper, selectedFiles)

  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.createEntity).toHaveBeenCalledWith('product', expect.not.objectContaining({ images: expect.anything() }))
  expect(api.uploadProductImages).toHaveBeenCalledWith(81, selectedFiles)
  expect(api.createEntity.mock.invocationCallOrder[0]).toBeLessThan(api.uploadProductImages.mock.invocationCallOrder[0])
  expect(wrapper.emitted('saved')).toHaveLength(1)
})

it('keeps failed product images for retry after the product has been saved', async () => {
  const selectedFiles = [productFile('retry.jpg')]
  api.createEntity.mockResolvedValue({ id: 81 })
  api.uploadProductImages.mockRejectedValue(new Error('网络中断'))
  const wrapper = mount(EntityDialog, { props: { module: 'product', currentUserRole: 'FINANCE' } })
  await chooseProductImages(wrapper, selectedFiles)

  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(wrapper.emitted('saved')).toHaveLength(1)
  expect(wrapper.emitted('message')?.at(-1)?.[0]).toContain('产品已保存，部分图片上传失败')
  expect(wrapper.findAll('[data-test="pending-image-card"]')).toHaveLength(1)
})

it('uploads selected images directly to an existing product id after editing', async () => {
  const selectedFiles = [productFile('new-detail.webp')]
  api.updateEntity.mockResolvedValue({ id: 42, version: 4 })
  const wrapper = mount(EntityDialog, {
    props: { module: 'product', currentUserRole: 'FINANCE', row: { id: 42, model: 'P90', version: 3 } }
  })
  await flushPromises()
  await chooseProductImages(wrapper, selectedFiles)

  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.updateEntity).toHaveBeenCalledWith('product', 42, expect.any(Object))
  expect(api.uploadProductImages).toHaveBeenCalledWith(42, selectedFiles)
})

it('includes the loaded version when saving an existing record', async () => {
  api.updateEntity.mockResolvedValue({ id: 7, version: 4 })
  const wrapper = mount(EntityDialog, {
    props: { module: 'customer', row: { id: 7, customerName: 'Customer A', version: 3 } }
  })

  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.updateEntity).toHaveBeenCalledWith('customer', 7, expect.objectContaining({ customerName: 'Customer A', version: 3 }))
})

it('shows complete customer details and locks the existing customer code', () => {
  const wrapper = mount(EntityDialog, {
    props: { module: 'customer', row: { id: 7, customerCode: 'C0007', customerName: 'Customer A', version: 3 } }
  })

  expect(wrapper.text()).toContain('客户编码')
  expect(wrapper.text()).toContain('业务联系人')
  expect(wrapper.text()).toContain('订单联系人')
  expect(wrapper.text()).toContain('财务联系人')
  expect(wrapper.text()).toContain('纳税人识别号')
  expect(wrapper.text()).toContain('开户银行')
  expect(wrapper.get('[data-test="customer-code"]').attributes('disabled')).toBeDefined()
})

it('产品弹窗只显示产品列表字段并自动计算价格差异', async () => {
  const wrapper = mount(EntityDialog, { props: { module: 'product', currentUserRole: 'FINANCE' } })
  expect(wrapper.text()).not.toContain('供应商编号')
  expect(wrapper.text()).not.toContain('采购单价')
  expect(wrapper.text()).not.toContain('最小起订量')
  expect(wrapper.text()).not.toContain('交货天数')
  expect(wrapper.text()).toContain('差异：转厂价-原成本')

  const inputs=wrapper.findAll('input')
  await inputs.find(input=>input.attributes('type')==='number' && input.element.parentElement?.textContent?.includes('成本单价'))!.setValue('100')
  await inputs.find(input=>input.attributes('type')==='number' && input.element.parentElement?.textContent?.includes('转厂价格'))!.setValue('135')
  expect(wrapper.get('[data-test="price-difference"]').attributes('value')).toBe('35')
})

it('allows finance users to edit and submit both product prices', async () => {
  api.createEntity.mockResolvedValue({ id: 1 })
  const wrapper = mount(EntityDialog, {
    props: { module: 'product', currentUserRole: 'FINANCE' }
  })

  const currentCost = wrapper.get('[data-test="product-current-cost"]')
  const factoryPrice = wrapper.get('[data-test="product-factory-price"]')
  expect(currentCost.attributes('disabled')).toBeUndefined()
  expect(factoryPrice.attributes('disabled')).toBeUndefined()

  await currentCost.setValue('100')
  await factoryPrice.setValue('135')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.createEntity).toHaveBeenLastCalledWith('product', expect.objectContaining({ currentCost: 100, factoryPrice: 135 }))
})

it('shows product prices read-only to ordinary users and omits them from the payload', async () => {
  api.updateEntity.mockResolvedValue({ id: 7, version: 4 })
  const wrapper = mount(EntityDialog, {
    props: {
      module: 'product',
      currentUserRole: 'USER',
      row: { id: 7, model: 'P90', currentCost: 100, factoryPrice: 135, version: 3 }
    }
  })

  const currentCost = wrapper.get('[data-test="product-current-cost"]')
  const factoryPrice = wrapper.get('[data-test="product-factory-price"]')
  expect((currentCost.element as HTMLInputElement).value).toBe('100')
  expect((factoryPrice.element as HTMLInputElement).value).toBe('135')
  expect(currentCost.attributes('disabled')).toBeDefined()
  expect(factoryPrice.attributes('disabled')).toBeDefined()
  expect(wrapper.get('[data-test="product-price-permission-hint"]').text()).toBe('仅财务或管理员可修改')

  await wrapper.get('form').trigger('submit')
  await flushPromises()

  const payload = api.updateEntity.mock.calls.at(-1)?.[2]
  expect(payload).not.toHaveProperty('currentCost')
  expect(payload).not.toHaveProperty('factoryPrice')
})

it('allows administrators to select and submit a user role with Chinese labels', async () => {
  api.updateEntity.mockResolvedValue({ id: 7, version: 4 })
  const wrapper = mount(EntityDialog, {
    props: {
      module: 'user',
      currentUserRole: 'ADMIN',
      row: { id: 7, username: 'finance', displayName: '财务员', role: 'USER', version: 3 }
    }
  })

  const role = wrapper.get('[data-test="user-role"]')
  expect(role.attributes('disabled')).toBeUndefined()
  expect(role.text()).toContain('管理员')
  expect(role.text()).toContain('财务')
  expect(role.text()).toContain('普通用户')
  await role.setValue('FINANCE')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.updateEntity).toHaveBeenLastCalledWith('user', 7, expect.objectContaining({ role: 'FINANCE' }))
})

it('shows but does not submit the role control for non-administrators', async () => {
  api.updateEntity.mockResolvedValue({ id: 7, version: 4 })
  const wrapper = mount(EntityDialog, {
    props: {
      module: 'user',
      currentUserRole: 'FINANCE',
      row: { id: 7, username: 'finance', displayName: '财务员', role: 'FINANCE', version: 3 }
    }
  })

  const role = wrapper.get('[data-test="user-role"]')
  expect(role.attributes('disabled')).toBeDefined()
  expect((role.element as HTMLSelectElement).value).toBe('FINANCE')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.updateEntity.mock.calls.at(-1)?.[2]).not.toHaveProperty('role')
})

it('shows exactly three roles and defaults a new user to USER', async () => {
  api.createEntity.mockResolvedValue({ id: 8 })
  const wrapper = mount(EntityDialog, {
    props: { module: 'user', currentUserRole: 'ADMIN' }
  })

  const role = wrapper.get('[data-test="user-role"]')
  expect(role.findAll('option').map(option => option.attributes('value'))).toEqual(['ADMIN', 'FINANCE', 'USER'])
  expect((role.element as HTMLSelectElement).value).toBe('USER')
  await wrapper.get('[data-test="user-username"]').setValue('new-user')
  await wrapper.get('[data-test="user-display-name"]').setValue('新用户')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.createEntity.mock.calls.at(-1)?.[1]).toMatchObject({ role: 'USER' })
})

it('shows the initial password in plaintext and prevents login autofill', async () => {
  api.createEntity.mockResolvedValue({ id: 8 })
  const wrapper = mount(EntityDialog, { props: { module: 'user', currentUserRole: 'ADMIN' } })

  const password = wrapper.get('[data-test="user-password"]')
  expect(wrapper.get('form').attributes('autocomplete')).toBe('off')
  expect(wrapper.get('input[type="tel"]').attributes('autocomplete')).toBe('off')
  expect(password.attributes('type')).toBe('text')
  expect(password.attributes('autocomplete')).toBe('new-password')
  expect(password.attributes('required')).toBeDefined()
  expect(wrapper.text()).toContain('初始密码')

  await wrapper.get('[data-test="user-username"]').setValue('simple-user')
  await wrapper.get('[data-test="user-display-name"]').setValue('简单用户')
  await password.setValue('123')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.createEntity).toHaveBeenLastCalledWith('user', expect.objectContaining({ password: '123' }))
  expect((password.element as HTMLInputElement).value).toBe('')
})

it('keeps an existing password when reset is blank and submits a nonblank reset', async () => {
  api.updateEntity.mockResolvedValue({ id: 7, version: 4 })
  const wrapper = mount(EntityDialog, {
    props: { module: 'user', currentUserRole: 'ADMIN', row: { id: 7, username: 'staff', displayName: '员工', role: 'USER', version: 3 } }
  })

  const password = wrapper.get('[data-test="user-password"]')
  expect(password.attributes('required')).toBeUndefined()
  expect(wrapper.text()).toContain('重置密码')
  expect(wrapper.text()).toContain('留空表示不修改')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(api.updateEntity.mock.calls.at(-1)?.[2]).not.toHaveProperty('password')

  await password.setValue('456')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(api.updateEntity.mock.calls.at(-1)?.[2]).toEqual(expect.objectContaining({ password: '456' }))
})

it('库存弹窗使用页面库存字段而不暴露内部调整原因', () => {
  const wrapper = mount(EntityDialog, { props: { module: 'inventory' } })

  expect(wrapper.text()).toContain('物料编号 SKU')
  expect(wrapper.text()).toContain('实际库存数量')
  expect(wrapper.text()).toContain('可用库存数量')
  expect(wrapper.text()).toContain('已锁定数量')
  expect(wrapper.text()).not.toContain('入/出库摘要')
  expect(wrapper.get('[data-test="add-inventory-movement"]').text()).toContain('新增明细')
  expect(wrapper.text()).toContain('供应商')
  expect(wrapper.text()).toContain('备注')
  expect(wrapper.text()).not.toContain('调整原因')
})

it('库存修改弹窗以动态列表编辑任意地点锁定数量', async () => {
  api.updateEntity.mockResolvedValue({ id: 7 })
  const wrapper = mount(EntityDialog, { props: { module: 'inventory', row: {
    id: 7, skuId: 1, skuCode: 'P50', actualQuantity: 20, availableQuantity: 10,
    lockedQuantity: 10, inTransitQuantity: 0, version: 2,
    lockedAllocations: [{ lockSource: '新加坡', quantity: 2 }, { lockSource: '越南', quantity: 3 }]
  } } })
  await flushPromises()

  expect(wrapper.findAll('[data-test="locked-allocation-row"]')).toHaveLength(2)
  expect((wrapper.findAll('[data-test="locked-allocation-source"]')[0].element as HTMLInputElement).value).toBe('新加坡')
  await wrapper.findAll('[data-test="remove-locked-allocation"]')[0].trigger('click')
  await wrapper.get('[data-test="add-locked-allocation"]').trigger('click')
  const sources = wrapper.findAll('[data-test="locked-allocation-source"]')
  const quantities = wrapper.findAll('[data-test="locked-allocation-quantity"]')
  await sources[1].setValue('香港')
  await quantities[1].setValue('4')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.updateEntity).toHaveBeenCalledWith('inventory', 7, expect.objectContaining({
    lockedQuantity: 10,
    lockedAllocations: [{ lockSource: '越南', quantity: 3 }, { lockSource: '香港', quantity: 4 }]
  }))
})
it('新增库存允许编辑产品字段与库存汇总字段，并将选择的产品编号提交保存', async () => {
  api.loadOrderSkus.mockResolvedValue([
    {
      id: 18,
      skuCode: 'SKU-P90',
      productName: 'P90 智能锁',
      model: 'P90',
      configuration: '可视对讲',
      productVersion: '工程款',
      color: '宇宙黑',
      lockBody: '6068',
      unit: '套'
    }
  ])
  api.createEntity.mockResolvedValue({ id: 1 })
  const wrapper = mount(EntityDialog, { attachTo: document.body, props: { module: 'inventory' } })
  await flushPromises()

  const picker = wrapper.get('[data-test="inventory-product-picker"]')
  await picker.get('input').setValue('P90')
  const option = document.body.querySelector<HTMLElement>('[data-test="fuzzy-option-18"]')
  expect(option?.textContent).toContain('P90')
  option?.click()
  await flushPromises()

  const editable = ['inventory-model', 'inventory-configuration', 'inventory-color', 'inventory-lock-body', 'inventory-unit', 'inventory-actual-quantity', 'inventory-available-quantity', 'inventory-locked-quantity']
  editable.forEach(testId => expect(wrapper.get(`[data-test="${testId}"]`).attributes('disabled')).toBeUndefined())
  expect((wrapper.get('[data-test="inventory-model"]').element as HTMLInputElement).value).toBe('P90')
  expect((wrapper.get('[data-test="inventory-unit"]').element as HTMLInputElement).value).toBe('套')

  await wrapper.get('[data-test="inventory-available-quantity"]').setValue('12')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.createEntity).toHaveBeenCalledWith('inventory', expect.objectContaining({
    skuId: 18,
    skuCode: 'SKU-P90',
    model: 'P90',
    availableQuantity: 12
  }))
  wrapper.unmount()
})

it('defaults and submits the product material type while preserving edit values', async () => {
  api.createEntity.mockResolvedValue({ id: 1 })
  const wrapper = mount(EntityDialog, { props: { module: 'product', currentUserRole: 'FINANCE' } })
  await flushPromises()
  const select = wrapper.get('[data-test="product-material-type"]')
  expect((select.element as HTMLSelectElement).value).toBe('FINISHED_PRODUCT')
  expect(select.findAll('option').map(option => option.text())).toEqual(['成品', '零件'])
  await select.setValue('PART')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(api.createEntity).toHaveBeenCalledWith('product', expect.objectContaining({ materialType: 'PART' }))

  const editing = mount(EntityDialog, {
    props: { module: 'product', currentUserRole: 'FINANCE', row: { id: 2, materialType: 'PART', version: 0 } }
  })
  expect((editing.get('[data-test="product-material-type"]').element as HTMLSelectElement).value).toBe('PART')
})