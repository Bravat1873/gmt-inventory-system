import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import ChineseDatePicker from './ChineseDatePicker.vue'

it('将原生日期值显示为中文日期', async () => {
  const wrapper = mount(ChineseDatePicker, { props: { modelValue: '' } })
  await wrapper.get('input[type="date"]').setValue('2026-08-06')

  expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['2026-08-06'])
  await wrapper.setProps({ modelValue: '2026-08-06' })
  expect(wrapper.get('[data-test="date-display"]').text()).toContain('2026年8月6日')
})
