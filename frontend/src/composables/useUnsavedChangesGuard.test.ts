import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { useUnsavedChangesGuard } from './useUnsavedChangesGuard'

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
})
