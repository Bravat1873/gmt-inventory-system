import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import EntityDialog from './EntityDialog.vue'

const api = vi.hoisted(() => ({
  createEntity: vi.fn(),
  updateEntity: vi.fn(),
  loadProductCodeRules: vi.fn(),
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
  api.loadProductCodeRules.mockResolvedValue([])
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

it('产品弹窗只显示产品基础字段并移除固定价格字段', () => {
  const wrapper = mount(EntityDialog, { props: { module: 'product', currentUserRole: 'FINANCE' } })
  expect(wrapper.text()).not.toContain('供应商编号')
  expect(wrapper.text()).not.toContain('采购单价')
  expect(wrapper.text()).toContain('销售最小起订量')
  expect(wrapper.text()).not.toContain('交货天数')
  expect(wrapper.text()).not.toContain('成本单价（含税）')
  expect(wrapper.text()).not.toContain('转厂价格')
  expect(wrapper.text()).not.toContain('差异：转厂价-原成本')
})

it('修改产品时客户编号可编辑且不显示重复的旧颜色和锁体字段', async () => {
  const wrapper = mount(EntityDialog, {
    props: {
      module: 'product',
      currentUserRole: 'FINANCE',
      row: { id: 7, customerPartNumber: 'OLD-CODE', model: 'D51', color: '11', lockBody: '11', version: 2 }
    }
  })
  await flushPromises()

  const customerPartNumber = wrapper.get('[data-test="customer-part-number"]')
  expect(customerPartNumber.attributes('disabled')).toBeUndefined()
  expect(wrapper.find('[data-test="product-legacy-color"]').exists()).toBe(false)
  expect(wrapper.find('[data-test="product-legacy-lock-body"]').exists()).toBe(false)

  await customerPartNumber.setValue('NEW-CODE')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.updateEntity).toHaveBeenLastCalledWith('product', 7, expect.objectContaining({ customerPartNumber: 'NEW-CODE' }))
  expect(api.updateEntity.mock.calls.at(-1)?.[2]).not.toHaveProperty('color')
  expect(api.updateEntity.mock.calls.at(-1)?.[2]).not.toHaveProperty('lockBody')
})
it('按品牌型号物料颜色锁体类型语言实时生成只读物料规格并提交产品配置', async () => {
  api.loadProductCodeRules.mockResolvedValue([
    { id: 1, category: 'BRAND', code: 'SXSEL', displayName: 'STANLEY', enabled: true },
    { id: 2, category: 'BODY_COLOR', code: 'YZH', displayName: '宇宙黑', enabled: true },
    { id: 3, category: 'LOCK_TYPE', code: '70', displayName: '7068', enabled: true },
    { id: 4, category: 'LANGUAGE', code: 'C', displayName: '中文版', enabled: true }
  ])
  api.updateEntity.mockResolvedValue({ id: 7, version: 3 })
  const wrapper = mount(EntityDialog, { props: { module: 'product', currentUserRole: 'FINANCE', row: {
    id: 7, model: 'D51-GEN2', brandRuleId: 1, bodyColorRuleId: 2, lockTypeRuleId: 3, languageRuleId: 4, version: 2
  } } })
  await flushPromises()

  expect(wrapper.text()).toContain('客户料号')
  expect(wrapper.text()).toContain('物料颜色')
  const specification = wrapper.findAll('textarea').find(input => input.element.parentElement?.textContent?.includes('物料规格'))!
  expect(specification.attributes('disabled')).toBeDefined()
  expect((specification.element as HTMLTextAreaElement).value).toBe('STANLEY / D51-GEN2 / 宇宙黑 / 7068 / 中文版')
  const productConfiguration = wrapper.findAll('textarea').find(input => input.element.parentElement?.textContent?.includes('产品配置'))!
  await productConfiguration.setValue('可视对讲 + 指纹')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.updateEntity).toHaveBeenLastCalledWith('product', 7, expect.objectContaining({ productConfiguration: '可视对讲 + 指纹' }))
  expect(api.updateEntity.mock.calls.at(-1)?.[2]).not.toHaveProperty('configuration')
})
it('hides fixed product prices and shows every supplier quote in the edit dialog', async () => {
  const wrapper = mount(EntityDialog, {
    props: {
      module: 'product', currentUserRole: 'ADMIN',
      row: {
        id: 7, model: 'P90', salesMinimumOrderQuantity: 2, version: 3,
        currentCost: 100, factoryPrice: 135, priceDifference: 35,
        supplierQuotes: [
          { supplierId: 1, supplierName: '供应商甲', purchasePrice: 100 },
          { supplierId: 2, supplierName: '供应商乙', purchasePrice: 105.5 }
        ]
      }
    }
  })

  expect(wrapper.text()).not.toContain('成本单价（含税）')
  expect(wrapper.text()).not.toContain('转厂价格')
  expect(wrapper.text()).not.toContain('差异：转厂价-原成本')
  expect(wrapper.text()).toContain('销售最小起订量')
  const quotes = wrapper.get('[data-test="product-supplier-quotes"]')
  expect(quotes.findAll('span').map(item => item.text())).toEqual(['供应商甲：¥100', '供应商乙：¥105.5'])
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

  expect(wrapper.text()).toContain('产品编号')
  expect(wrapper.text()).toContain('实际库存数量')
  expect(wrapper.text()).toContain('未锁定库存数量')
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
    id: 7, skuId: 1, customerPartNumber: 'P50', actualQuantity: 20, availableQuantity: 10,
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
it('shows inventory product search results with product code first', async () => {
  api.loadOrderSkus.mockResolvedValue([{ id: 19, productCode: 'BR_D51', customerPartNumber: 'D1213K-D51', model: 'D51-GEN2' }])
  const wrapper = mount(EntityDialog, { attachTo: document.body, props: { module: 'inventory' } })
  await flushPromises()
  await wrapper.get('[data-test="inventory-product-picker"] input').setValue('BR_D51')
  expect(document.body.querySelector('[data-test="fuzzy-option-19"]')?.textContent)
    .toMatch(/产品编号：BR_D51[\s\S]*客户料号：D1213K-D51[\s\S]*型号：D51-GEN2/)
  document.body.querySelector<HTMLElement>('[data-test="fuzzy-option-19"]')?.click()
  await flushPromises()
  expect((wrapper.get('[data-test="inventory-product-picker"] input').element as HTMLInputElement).value).toBe('BR_D51')
  wrapper.unmount()
})
it('新增库存从产品管理同步只读产品资料并提交产品关联', async () => {
  api.loadOrderSkus.mockResolvedValue([{
    id: 18,
    productCode: 'SXSEL_P90',
    customerPartNumber: 'SKU-P90',
    productName: 'P90 智能锁',
    model: 'P90',
    productType: 'SMART_LOCK',
    productConfiguration: '可视对讲 + 指纹',
    configuration: 'STANLEY / P90 / 宇宙黑 / 7068 / 中文版',
    unit: '套'
  }])
  api.createEntity.mockResolvedValue({ id: 1 })
  const wrapper = mount(EntityDialog, { attachTo: document.body, props: { module: 'inventory' } })
  await flushPromises()

  const picker = wrapper.get('[data-test="inventory-product-picker"]')
  await picker.get('input').setValue('SXSEL_P90')
  document.body.querySelector<HTMLElement>('[data-test="fuzzy-option-18"]')?.click()
  await flushPromises()

  expect(wrapper.text()).toContain('产品编号')
  expect(wrapper.text()).toContain('产品类型')
  expect(wrapper.text()).toContain('产品配置')
  expect(wrapper.text()).toContain('物料规格')
  expect(wrapper.get('[data-test="inventory-model"]').attributes('disabled')).toBeDefined()
  const productType = wrapper.get('[data-test="inventory-product-type"]')
  expect(productType.element.tagName).toBe('SELECT')
  expect(productType.attributes('disabled')).toBeUndefined()
  expect((productType.element as HTMLSelectElement).value).toBe('SMART_LOCK')
  await productType.setValue('ENTRY_DOOR')
  expect((wrapper.get('[data-test="inventory-product-configuration"]').element as HTMLTextAreaElement).value).toBe('可视对讲 + 指纹')
  expect((wrapper.get('[data-test="inventory-configuration"]').element as HTMLTextAreaElement).value).toBe('STANLEY / P90 / 宇宙黑 / 7068 / 中文版')

  await wrapper.get('[data-test="inventory-available-quantity"]').setValue('12')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(api.createEntity).toHaveBeenCalledWith('inventory', expect.objectContaining({
    skuId: 18,
    customerPartNumber: 'SXSEL_P90',
    productType: 'ENTRY_DOOR',
    availableQuantity: 12
  }))
  expect(api.createEntity.mock.calls.at(-1)?.[1]).not.toHaveProperty('model')
  expect(api.createEntity.mock.calls.at(-1)?.[1]).not.toHaveProperty('configuration')
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
it('submits a custom product code suffix suggested by suffix rules', async () => {
  api.loadProductCodeRules.mockResolvedValue([
    { id: 90, category: 'SUFFIX', code: 'A', displayName: 'A', enabled: true }
  ])
  api.createEntity.mockResolvedValue({ id: 1 })
  const wrapper = mount(EntityDialog, { props: { module: 'product', currentUserRole: 'FINANCE' } })
  await flushPromises()
  const input = wrapper.get('[data-test="product-code-suffix"]')
  expect(input.attributes('list')).toBe('product-code-suffix-options')
  await input.setValue('???-X')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(api.createEntity).toHaveBeenCalledWith('product', expect.objectContaining({ codeSuffix: '???-X' }))
  expect(wrapper.get('#product-code-suffix-options').text()).toContain('A')
})

it('limits and submits a twelve digit EAN starting with 69', async () => {
  api.createEntity.mockResolvedValue({ id: 1 })
  const wrapper = mount(EntityDialog, { props: { module: 'product', currentUserRole: 'FINANCE' } })
  await flushPromises()
  const input = wrapper.get('[data-test="product-ean-code"]')
  expect(input.attributes('maxlength')).toBe('12')
  expect(input.attributes('pattern')).toBe('69[0-9]{10}')
  await input.setValue('690123456789')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(api.createEntity).toHaveBeenCalledWith('product', expect.objectContaining({ eanCode: '690123456789' }))
})
it('shows unlocked stock and read-only supply-demand values in inventory editor', async () => {
  const wrapper = mount(EntityDialog, { props: { module: 'inventory', row: {
    id: 7, skuId: 1, customerPartNumber: 'P50', actualQuantity: 20, availableQuantity: 10,
    lockedQuantity: 10, inTransitQuantity: 4, pendingDeliveryQuantity: 25,
    supplyDemandSurplus: -1, version: 2
  } } })
  await flushPromises()
  expect(wrapper.text()).toContain('未锁定库存数量')
  expect(wrapper.text()).not.toContain('可用库存数量')
  expect(wrapper.get('[data-test="inventory-pending-delivery-quantity"]').attributes('disabled')).toBeDefined()
  expect(wrapper.get('[data-test="inventory-supply-demand-surplus"]').attributes('disabled')).toBeDefined()
})


