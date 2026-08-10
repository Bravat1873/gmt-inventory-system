import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  deleteProductImage,
  loadProductImages,
  reorderProductImages,
  setPrimaryProductImage,
  uploadProductImages
} from './workbench'

function stubFetch(data: unknown[] = []) {
  const fetch = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({ success: true, data })
  })
  vi.stubGlobal('fetch', fetch)
  return fetch
}

afterEach(() => vi.unstubAllGlobals())

describe('product image API', () => {
  it('loads product images', async () => {
    const fetch = stubFetch()

    await loadProductImages(7)

    expect(fetch).toHaveBeenCalledWith('/api/products/7/images', expect.objectContaining({
      credentials: 'include'
    }))
  })

  it('uploads multipart files without forcing a JSON content type', async () => {
    const fetch = stubFetch()
    const file = new File(['x'], 'a.jpg', { type: 'image/jpeg' })

    await uploadProductImages(7, [file])

    const options = fetch.mock.calls.at(-1)?.[1] as RequestInit
    expect(fetch).toHaveBeenCalledWith('/api/products/7/images', expect.objectContaining({
      method: 'POST',
      body: expect.any(FormData)
    }))
    expect(options.headers).not.toHaveProperty('Content-Type')
    expect((options.body as FormData).getAll('files')).toEqual([file])
  })

  it('marks an image as primary', async () => {
    const fetch = stubFetch()

    await setPrimaryProductImage(7, 11)

    expect(fetch).toHaveBeenCalledWith('/api/products/7/images/11/primary', expect.objectContaining({ method: 'PUT' }))
  })

  it('reorders images by id', async () => {
    const fetch = stubFetch()

    await reorderProductImages(7, [13, 11])

    expect(fetch).toHaveBeenCalledWith('/api/products/7/images/order', expect.objectContaining({
      method: 'PUT',
      body: JSON.stringify({ imageIds: [13, 11] })
    }))
  })

  it('deletes an image', async () => {
    const fetch = stubFetch()

    await deleteProductImage(7, 11)

    expect(fetch).toHaveBeenCalledWith('/api/products/7/images/11', expect.objectContaining({ method: 'DELETE' }))
  })
})
