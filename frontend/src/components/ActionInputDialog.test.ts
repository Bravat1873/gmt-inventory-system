import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import ActionInputDialog from './ActionInputDialog.vue'

it('在白色弹窗内校验并提交发票号码', async () => {
  const wrapper = mount(ActionInputDialog, { props: { title: '开具销售发票', label: '销售发票号码', placeholder: '请输入销售发票号码' } })

  await wrapper.get('form').trigger('submit')
  expect(wrapper.text()).toContain('请填写销售发票号码')

  await wrapper.get('input').setValue('INV-20260806')
  await wrapper.get('form').trigger('submit')
  expect(wrapper.emitted('confirm')?.[0]).toEqual(['INV-20260806'])
})
