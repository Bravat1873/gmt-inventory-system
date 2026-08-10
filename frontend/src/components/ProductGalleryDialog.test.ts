import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import { loadProductImages } from '../api/workbench'
import ProductGalleryDialog from './ProductGalleryDialog.vue'

vi.mock('../api/workbench', () => ({ loadProductImages: vi.fn() }))

const images = [
  { id: 11, productId: 7, originalFilename: 'P90-front.jpg', contentType: 'image/jpeg', fileSize: 100, primary: true, sortOrder: 1, contentUrl: '/api/product-images/11/content' },
  { id: 12, productId: 7, originalFilename: 'P90-side.webp', contentType: 'image/webp', fileSize: 200, primary: false, sortOrder: 2, contentUrl: '/api/product-images/12/content' }
]

beforeEach(() => vi.mocked(loadProductImages).mockResolvedValue(images))

it('opens at the requested image and supports gallery navigation', async () => {
  const wrapper = mount(ProductGalleryDialog, { props: { productId: 7, initialImageId: 12 } })
  await flushPromises()

  expect(wrapper.get('[role="dialog"]').attributes('aria-modal')).toBe('true')
  expect(wrapper.get('[data-test="gallery-main-image"]').attributes()).toMatchObject({
    src: '/api/product-images/12/content',
    alt: '产品图片 2/2：P90-side.webp'
  })

  await wrapper.get('button[aria-label="上一张图片"]').trigger('click')
  expect(wrapper.get('[data-test="gallery-main-image"]').attributes('alt')).toBe('产品图片 1/2：P90-front.jpg')

  await wrapper.get('button[aria-label="下一张图片"]').trigger('click')
  expect(wrapper.get('[data-test="gallery-main-image"]').attributes('alt')).toBe('产品图片 2/2：P90-side.webp')

  await wrapper.get('button[aria-label="查看图片 P90-front.jpg"]').trigger('click')
  expect(wrapper.get('[data-test="gallery-main-image"]').attributes('alt')).toBe('产品图片 1/2：P90-front.jpg')
  expect(wrapper.text()).toContain('主图')

  await wrapper.get('button[aria-label="关闭产品图库"]').trigger('click')
  expect(wrapper.emitted('close')).toHaveLength(1)
})

it('disables navigation when the product only has one image', async () => {
  vi.mocked(loadProductImages).mockResolvedValue([images[0]])
  const wrapper = mount(ProductGalleryDialog, { props: { productId: 7 } })
  await flushPromises()

  expect(wrapper.get('button[aria-label="上一张图片"]').attributes()).toHaveProperty('disabled')
  expect(wrapper.get('button[aria-label="下一张图片"]').attributes()).toHaveProperty('disabled')
})
