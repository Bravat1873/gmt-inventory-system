import { onMounted, onUnmounted, ref, type Ref } from 'vue'

export function useUnsavedChangesGuard(close: () => void, saving: Ref<boolean>) {
  const dirty = ref(false)

  function markDirty() {
    dirty.value = true
  }

  function markClean() {
    dirty.value = false
  }

  function requestClose() {
    if (saving.value) return
    if (dirty.value && !window.confirm('当前修改尚未保存，确定放弃吗？')) return
    close()
  }

  return { dirty, markDirty, markClean, requestClose }
}

export function useGlobalDialogCloseGuard() {
  const dirtyDialogs = new WeakSet<Element>()
  const dialogFor = (target: EventTarget | null) => target instanceof Element ? target.closest('.dialog-card') : null
  const markDirty = (event: Event) => { const dialog = dialogFor(event.target); if (dialog) dirtyDialogs.add(dialog) }
  const markClean = (event: Event) => { const dialog = dialogFor(event.target); if (dialog) dirtyDialogs.delete(dialog) }
  const handleClick = (event: MouseEvent) => {
    const button = event.target instanceof Element ? event.target.closest('button') : null
    const dialog = dialogFor(button)
    if (!button || !dialog) return
    const label = button.textContent?.trim() ?? ''
    const closesDialog = ['关闭', '取消', '取消操作'].includes(label)
    if (!closesDialog) return
    if (dirtyDialogs.has(dialog) && !window.confirm('当前修改尚未保存，确定放弃吗？')) {
      event.preventDefault()
      event.stopImmediatePropagation()
      return
    }
    dirtyDialogs.delete(dialog)
  }
  const handleKeydown = (event: KeyboardEvent) => {
    if (event.key !== 'Escape') return
    const dialogs = Array.from(document.querySelectorAll<HTMLElement>('.dialog-card'))
    const closeButton = dialogs.at(-1)?.querySelector<HTMLButtonElement>('[data-dialog-close], header button')
    if (!closeButton || closeButton.disabled) return
    event.preventDefault()
    closeButton.click()
  }
  onMounted(() => {
    document.addEventListener('input', markDirty, true)
    document.addEventListener('change', markDirty, true)
    document.addEventListener('dialog-clean', markClean, true)
    document.addEventListener('click', handleClick, true)
    document.addEventListener('keydown', handleKeydown, true)
  })
  onUnmounted(() => {
    document.removeEventListener('input', markDirty, true)
    document.removeEventListener('change', markDirty, true)
    document.removeEventListener('dialog-clean', markClean, true)
    document.removeEventListener('click', handleClick, true)
    document.removeEventListener('keydown', handleKeydown, true)
  })
}
