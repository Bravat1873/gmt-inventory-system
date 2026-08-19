import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import ProductIdentityDisplay from './ProductIdentityDisplay.vue'

it('shows the complete product identity in the required order', () => {
  const wrapper = mount(ProductIdentityDisplay, { props: {
    productCode: 'BR_VERY_LONG_D51YZH70WPSS-A',
    customerPartNumber: 'D1213K-D51-CUSTOMER-LONG',
    model: 'D51-GEN2',
  } })

  expect(wrapper.text()).toMatch(/产品编号[\s\S]*BR_VERY_LONG_D51YZH70WPSS-A[\s\S]*客户料号[\s\S]*D1213K-D51-CUSTOMER-LONG[\s\S]*型号[\s\S]*D51-GEN2/)
  expect(wrapper.text()).not.toContain('产品名称')
})

it('uses a visible placeholder for every missing identity value', () => {
  const wrapper = mount(ProductIdentityDisplay)
  expect(wrapper.findAll('.product-identity-value').map(node => node.text())).toEqual(['—', '—', '—'])
})
