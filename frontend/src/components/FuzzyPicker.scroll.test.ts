import { flushPromises, mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import FuzzyPicker from './FuzzyPicker.vue'

it('closes the teleported options when horizontal scrolling moves the input outside the viewport', async () => {
  const wrapper = mount(FuzzyPicker, {
    attachTo: document.body,
    props: { modelValue: null, options: [{ id: 1, label: 'SKU-1' }], placeholder: '选择物料' }
  })
  const root = wrapper.get('.fuzzy-picker').element as HTMLElement
  root.getBoundingClientRect = () => ({ x: 20, y: 20, left: 20, top: 20, right: 220, bottom: 60, width: 200, height: 40, toJSON: () => ({}) })
  await wrapper.get('input').trigger('focus')
  await flushPromises()
  expect(wrapper.get('input').attributes('aria-expanded')).toBe('true')

  root.getBoundingClientRect = () => ({ x: -240, y: 20, left: -240, top: 20, right: -40, bottom: 60, width: 200, height: 40, toJSON: () => ({}) })
  window.dispatchEvent(new Event('scroll'))
  await flushPromises()

  expect(wrapper.get('input').attributes('aria-expanded')).toBe('false')
  expect(document.body.querySelector('.fuzzy-picker-options')).toBeNull()
  wrapper.unmount()
})
