import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ProductImagePicker from './ProductImagePicker.vue'
import type { ProductImage } from '../api/workbench'

const api = vi.hoisted(() => ({
  loadProductImages: vi.fn(),
  setPrimaryProductImage: vi.fn(),
  reorderProductImages: vi.fn(),
  deleteProductImage: vi.fn()
}))

vi.mock('../api/workbench', () => api)

const image = (id: number, primary = false): ProductImage => ({
  id,
  productId: 7,
  originalFilename: `image-${id}.jpg`,
  contentType: 'image/jpeg',
  fileSize: 1024,
  primary,
  sortOrder: id - 1,
  contentUrl: `/api/product-images/${id}/content`
})

const file = (name: string, type = 'image/jpeg', size = 32) => {
  const value = new File(['image'], name, { type })
  Object.defineProperty(value, 'size', { configurable: true, value: size })
  return value
}

async function choose(wrapper: ReturnType<typeof mount>, files: File[]) {
  const input = wrapper.get('[data-test="product-image-input"]')
  Object.defineProperty(input.element, 'files', { configurable: true, value: files })
  await input.trigger('change')
}

beforeEach(() => {
  vi.clearAllMocks()
  api.loadProductImages.mockResolvedValue([])
  api.setPrimaryProductImage.mockImplementation(async (_productId: number, imageId: number) => [image(1, imageId === 1), image(2, imageId === 2)])
  api.reorderProductImages.mockImplementation(async (_productId: number, imageIds: number[]) => imageIds.map((id, index) => ({ ...image(id, id === 1), sortOrder: index })))
  api.deleteProductImage.mockResolvedValue([image(2, true)])
  Object.defineProperty(URL, 'createObjectURL', { configurable: true, writable: true, value: vi.fn((value: File) => `blob:${value.name}`) })
  Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, writable: true, value: vi.fn() })
})

describe('ProductImagePicker', () => {
  it('accepts jpeg png and webp files and previews them', async () => {
    const wrapper = mount(ProductImagePicker, { props: { modelValue: [] } })
    const files = [file('a.jpg'), file('b.png', 'image/png'), file('c.webp', 'image/webp')]

    await choose(wrapper, files)

    expect(wrapper.findAll('[data-test="pending-image-card"]')).toHaveLength(3)
    expect(wrapper.findAll('[data-test="pending-image-preview"]').map(item => item.attributes('src'))).toEqual([
      'blob:a.jpg', 'blob:b.png', 'blob:c.webp'
    ])
    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toEqual(files)
  })

  it('rejects a file over 5MB with a Chinese message', async () => {
    const wrapper = mount(ProductImagePicker, { props: { modelValue: [] } })

    await choose(wrapper, [file('large.jpg', 'image/jpeg', 5 * 1024 * 1024 + 1)])

    expect(wrapper.findAll('[data-test="pending-image-card"]')).toHaveLength(0)
    expect(wrapper.emitted('message')?.at(-1)?.[0]).toContain('不能超过 5MB')
  })

  it('rejects an unsupported type', async () => {
    const wrapper = mount(ProductImagePicker, { props: { modelValue: [] } })

    await choose(wrapper, [file('vector.svg', 'image/svg+xml')])

    expect(wrapper.emitted('message')?.at(-1)?.[0]).toContain('仅支持 JPG、PNG 和 WebP')
  })

  it('never allows more than ten existing plus pending images', async () => {
    api.loadProductImages.mockResolvedValue(Array.from({ length: 9 }, (_, index) => image(index + 1, index === 0)))
    const wrapper = mount(ProductImagePicker, { props: { productId: 7, modelValue: [] } })
    await flushPromises()

    await choose(wrapper, [file('tenth.jpg'), file('eleventh.jpg')])

    expect(wrapper.findAll('[data-test="pending-image-card"]')).toHaveLength(1)
    expect(wrapper.emitted('message')?.at(-1)?.[0]).toContain('最多 10 张')
  })

  it('uses the first pending image as the initial primary preview', async () => {
    const wrapper = mount(ProductImagePicker, { props: { modelValue: [] } })

    await choose(wrapper, [file('primary.jpg'), file('detail.jpg')])

    expect(wrapper.get('[data-test="pending-image-card"] [data-test="primary-badge"]').text()).toBe('主图')
  })

  it('revokes object URLs when removed and unmounted', async () => {
    const wrapper = mount(ProductImagePicker, { props: { modelValue: [] } })
    await choose(wrapper, [file('removed.jpg'), file('unmounted.jpg')])

    await wrapper.findAll('[data-test="remove-pending-image"]')[0].trigger('click')
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:removed.jpg')

    wrapper.unmount()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:unmounted.jpg')
  })

  it('sets primary deletes and reorders existing images through the API', async () => {
    api.loadProductImages.mockResolvedValue([image(1, true), image(2)])
    const wrapper = mount(ProductImagePicker, { props: { productId: 7, modelValue: [] } })
    await flushPromises()

    await wrapper.findAll('[data-test="set-primary-existing"]')[1].trigger('click')
    await flushPromises()
    expect(api.setPrimaryProductImage).toHaveBeenCalledWith(7, 2)

    await wrapper.findAll('[data-test="move-existing-left"]')[1].trigger('click')
    await flushPromises()
    expect(api.reorderProductImages).toHaveBeenCalledWith(7, [2, 1])

    await wrapper.findAll('[data-test="remove-existing-image"]')[0].trigger('click')
    await flushPromises()
    expect(api.deleteProductImage).toHaveBeenCalledWith(7, 2)
    expect(wrapper.emitted('changed')).toHaveLength(3)
  })
})
