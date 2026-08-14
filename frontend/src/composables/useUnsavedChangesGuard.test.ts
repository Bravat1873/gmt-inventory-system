import { defineComponent, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { useGlobalDialogCloseGuard, useUnsavedChangesGuard } from './useUnsavedChangesGuard'

describe('useUnsavedChangesGuard', () => {
  it('closes immediately when clean', () => {
    const close = vi.fn()
    const guard = useUnsavedChangesGuard(close, ref(false))
    guard.requestClose()
    expect(close).toHaveBeenCalledOnce()
  })

  it('asks before discarding dirty changes', () => {
    const close = vi.fn()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const guard = useUnsavedChangesGuard(close, ref(false))
    guard.markDirty()
    guard.requestClose()
    expect(confirm).toHaveBeenCalledWith('当前修改尚未保存，确定放弃吗？')
    expect(close).not.toHaveBeenCalled()
    confirm.mockReturnValue(true)
    guard.requestClose()
    expect(close).toHaveBeenCalledOnce()
    confirm.mockRestore()
  })

  it('does not close while saving', () => {
    const close = vi.fn()
    const guard = useUnsavedChangesGuard(close, ref(true))
    guard.markDirty()
    guard.requestClose()
    expect(close).not.toHaveBeenCalled()
  })
  it('allows close when a business primary action is disabled', async () => {
    const close = vi.fn()
    const Host = defineComponent({
      setup() { useGlobalDialogCloseGuard(); return { close } },
      template: '<div class="dialog-card"><button class="primary-action" disabled>确认登记</button><button data-test="close" @click="close">关闭</button></div>'
    })
    const wrapper = mount(Host, { attachTo: document.body })
    await wrapper.get('[data-test="close"]').trigger('click')
    expect(close).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('closes the top dialog with Escape through its normal close button', async () => {
    const close = vi.fn()
    const Host = defineComponent({
      setup() { useGlobalDialogCloseGuard(); return { close } },
      template: '<div class="dialog-card"><button data-dialog-close @click="close">关闭</button></div>'
    })
    const wrapper = mount(Host, { attachTo: document.body })
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await Promise.resolve()
    expect(close).toHaveBeenCalledOnce()
    wrapper.unmount()
  })
})

it('does not mark a dialog dirty merely by clicking a review button and supports marking a submitted form clean', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  const wrapper = mount(defineComponent({
    setup() { useGlobalDialogCloseGuard() },
    template: '<section class="dialog-card"><input><button class="review">通过</button><button class="close">关闭</button></section>'
  }), { attachTo: document.body })
  await wrapper.get('.review').trigger('click')
  await wrapper.get('.close').trigger('click')
  expect(confirm).not.toHaveBeenCalled()
  await wrapper.get('input').trigger('input')
  wrapper.get('.dialog-card').element.dispatchEvent(new CustomEvent('dialog-clean', { bubbles: true }))
  await wrapper.get('.close').trigger('click')
  expect(confirm).not.toHaveBeenCalled()
  wrapper.unmount()
})