import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import OverflowText from './OverflowText.vue'

it('renders reusable overflow text content', () => {
  const wrapper = mount(OverflowText, { props: { value: 'BR_C51YZH60W 的完整物料编号' } })
  expect(wrapper.get('[data-test="overflow-text"]')).toBeTruthy()
})